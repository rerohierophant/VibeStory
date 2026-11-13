import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { GenerationMark, type GenerationLoadingState } from '@/components/GenerationLoading';
import { Modal } from '@/components/Modal';
import { api, type Schema } from '@/lib/api';
import { useProject, useStore } from '@/lib/store';
import { blockedGenerationStyle } from './ShotReferenceHint';

export type ImageTarget = NonNullable<Schema['ImageExperienceContext']['targetType']>;
const base = '/api/image-experience';
const imageField = 'w-full rounded-card border border-stroke/30 bg-panel-2/40 px-3 py-2 text-sm text-fg';

type Props = {
  projectId: string;
  targetType: Exclude<ImageTarget, 'SHOT'>;
  targetId: string;
  name: string;
  hasImage: boolean;
  generation?: GenerationLoadingState;
  retryLabel?: string;
  initialLabel: string;
  disabled?: boolean;
  onGenerate: () => void;
};

export function ImageGenerationButton({
  projectId, targetType, targetId, name, hasImage, generation, retryLabel, initialLabel, disabled, onGenerate,
}: Props) {
  const { mode, workspaceReadOnly } = useStore();
  const project = useProject(projectId);
  const title = useId();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const canCustomize = hasImage && mode === 'api' && !!project && !project.teamId;
  const opensDialog = canCustomize && !retryLabel;
  const label = generation === 'queued' ? '排队中' : generation === 'running' ? '生成中'
    : generation === 'submitting' ? '正在提交…' : retryLabel ?? (hasImage ? '再生成一版' : initialLabel);

  return <>
    <Button size="sm" variant="outline"
      disabled={workspaceReadOnly || disabled || !!generation} aria-haspopup={opensDialog ? 'dialog' : undefined}
      onClick={() => opensDialog ? setOpen(true) : onGenerate()}>
      {generation && <GenerationMark state={generation} />}
      {label}
    </Button>
    {open && canCustomize && <Modal open onClose={() => { if (!pending) setOpen(false); }} width="max-w-lg" labelledBy={title}>
      <ImageRegenerationForm targetType={targetType} targetId={targetId}
        disabled={!!generation || !!disabled || !!workspaceReadOnly} onClose={() => setOpen(false)} onPendingChange={setPending}>
        <header>
          <h2 id={title} className="text-base font-medium">再生成一版</h2>
          <p className="mt-1 text-xs text-muted">{name} · 新图会保留为候选</p>
        </header>
      </ImageRegenerationForm>
    </Modal>}
  </>;
}

export function ImageRegenerationForm({ targetType, targetId, disabled, onClose, onPendingChange, children, generateDirectly, referenceHint }: {
  targetType: ImageTarget;
  targetId: string;
  disabled: boolean;
  onClose: () => void;
  onPendingChange: (pending: boolean) => void;
  children: ReactNode;
  generateDirectly?: () => void | Promise<unknown>;
  referenceHint?: ReactNode;
}) {
  const { user, performCreative } = useStore();
  const cache = useQueryClient();
  const inShotEditor = targetType === 'SHOT';
  const hintId = useId();
  const [showReferenceHint, setShowReferenceHint] = useState(false);
  const [source, setSource] = useState<string | undefined>(inShotEditor ? '' : undefined);
  const [instruction, setInstruction] = useState('');
  const [recipeVersionId, setRecipeVersionId] = useState('');
  const [parameters, setParameters] = useState<Record<string, string>>({});
  const [reference, setReference] = useState<File>();
  const [preview, setPreview] = useState<string>();
  const fileInput = useRef<HTMLInputElement>(null);
  const request = useRef<{ input: string; reference?: File; key: string }>(undefined);
  useEffect(() => {
    const url = reference ? URL.createObjectURL(reference) : undefined;
    setPreview(url);
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [reference]);
  const queryKey = ['image-experience', user?.id, targetType, targetId];
  const context = useQuery({
    queryKey,
    queryFn: ({ signal }) => api<Schema['ImageExperienceContext']>(`${base}/targets/${targetType}/${targetId}`, 'GET', undefined, signal),
    staleTime: 0,
    enabled: !generateDirectly,
  });
  const examples = (context.data?.examples ?? []).filter(item => item.url);
  const sourceTaskId = source ?? examples.find(item => item.selected)?.taskId ?? '';
  const original = examples.find(item => item.taskId === sourceTaskId);
  const recipes = context.data?.recipes ?? [];
  const version = recipes.flatMap(item => item.versions ?? []).find(item => item.id === recipeVersionId);
  const note = instruction.trim();
  const tooLong = new TextEncoder().encode(note).length > 1000;
  const referenceSupported = context.data?.referenceUploadSupported === true;
  const referenceInvalid = !!reference && (reference.size < 1 || reference.size > 5 * 1024 * 1024
    || !['image/jpeg', 'image/png'].includes(reference.type));
  const mutation = useMutation({
    mutationFn: async () => {
      if (generateDirectly) {
        await generateDirectly();
        return;
      }
      const payload = { targetType, targetId, sourceTaskId: original?.taskId ?? null, instruction: note,
        recipeVersionId: version?.id ?? null, parameters };
      const input = JSON.stringify(payload);
      if (request.current?.input !== input || request.current.reference !== reference) {
        request.current = { input, reference, key: crypto.randomUUID() };
      }
      const requestKey = request.current.key;
      if (!performCreative) return;
      const body = { ...payload, requestKey };
      if (reference) {
        const form = new FormData();
        form.append('request', new Blob([JSON.stringify(body)], { type: 'application/json' }));
        form.append('reference', reference);
        await performCreative(requestKey, () => api(`${base}/creations/with-reference`, 'POST', form));
      } else {
        await performCreative(requestKey, () => api(`${base}/creations`, 'POST', body));
      }
    },
    onSuccess: () => {
      onClose();
      if (!generateDirectly) void cache.invalidateQueries({ queryKey, refetchType: 'none' });
    },
  });
  useEffect(() => {
    onPendingChange(mutation.isPending);
    return () => onPendingChange(false);
  }, [mutation.isPending, onPendingChange]);

  const blocked = disabled || mutation.isPending || (!generateDirectly && (context.isFetching || context.isError || tooLong
    || (reference && (referenceInvalid || !referenceSupported))
    || (!!sourceTaskId && !original) || (!!recipeVersionId && !version)
    || version?.slots?.some(slot => !parameters[slot]?.trim())));

  function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled || mutation.isPending) return;
    if (referenceHint) setShowReferenceHint(true);
    else if (!blocked) mutation.mutate();
  }

  const options = !generateDirectly && <>
      {context.isPending && <p role="status" className="text-sm text-muted">正在读取生成依据…</p>}
      {examples.length > 0 && <label className="block space-y-1 text-xs text-muted">
        生成依据
        <select aria-label="生成依据" className={imageField} value={sourceTaskId} disabled={mutation.isPending} onChange={event => setSource(event.target.value)}>
          <option value="">使用当前设定</option>
          {examples.map(item => <option key={item.taskId} value={item.taskId}>
            {item.selected ? '沿用当前选定图片的设定' : `沿用历史生成结果 #${item.taskId} 的设定`}
          </option>)}
        </select>
      </label>}
      {original?.url && !reference && <img className="max-h-32 rounded-card object-contain" src={original.url} alt="生成依据图片" />}
      {targetType !== 'SHOT' && context.data && (
        <div className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" variant="outline" disabled={!referenceSupported || mutation.isPending}
              onClick={() => fileInput.current?.click()}>上传参考图</Button>
            <input ref={fileInput} className="hidden" type="file" accept="image/jpeg,image/png" aria-label="本次生成参考图"
              disabled={!referenceSupported || mutation.isPending}
              onChange={event => {
                const file = event.target.files?.[0];
                if (file) setReference(file);
                event.target.value = '';
              }} />
            {reference && <Button type="button" size="sm" variant="ghost" disabled={mutation.isPending}
              onClick={() => setReference(undefined)}>移除参考图</Button>}
          </div>
          <p className="text-xs text-muted">
            {referenceSupported ? '用于本次生成。支持 JPG、PNG，最大 5 MB。' : '当前图片模型仅支持人物参考图，场景按文字设定生成。'}
          </p>
          {reference && <p className="break-all text-xs text-muted">{reference.name}</p>}
          {referenceInvalid && <p role="alert" className="text-xs text-error-soft">请选择不超过 5 MB 的 JPG 或 PNG 图片。</p>}
          {preview && !referenceInvalid && <img src={preview} alt="本次生成参考图预览" className="max-h-28 rounded-card object-contain" />}
        </div>
      )}
      <label className="block space-y-1 text-xs text-muted">
        补充说明（选填）
        <textarea className={imageField} rows={3} maxLength={1000} disabled={mutation.isPending}
          placeholder="例如：光线柔和一点，保留原来的构图" value={instruction} onChange={event => setInstruction(event.target.value)} />
      </label>
      {tooLong && <p role="alert" className="text-xs text-error-soft">补充说明过长，请缩短后再生成。</p>}
      {recipes.length > 0 && <details className="space-y-3 text-xs text-muted">
        <summary className="cursor-pointer">使用创作配方（选填）</summary>
        <label className="block space-y-1">创作配方
          <select aria-label="创作配方" className={imageField} disabled={mutation.isPending} value={recipeVersionId}
            onChange={event => { setRecipeVersionId(event.target.value); setParameters({}); }}>
            <option value="">沿用生成依据</option>
            {recipes.map(recipe => <optgroup key={recipe.id} label={recipe.name}>
              {recipe.versions?.map(item => <option key={item.id} value={item.id}>{recipe.name} · v{item.version}</option>)}
            </optgroup>)}
          </select>
        </label>
        {version?.slots?.map(slot => <label key={slot} className="block space-y-1">配方参数 {slot}
          <input className={imageField} disabled={mutation.isPending} maxLength={1000}
            value={parameters[slot] ?? ''} onChange={event => setParameters({ ...parameters, [slot]: event.target.value })} />
        </label>)}
      </details>}
  </>;

  return (
    <form onSubmit={submit} className={inShotEditor ? 'flex min-h-0 flex-1 flex-col' : 'space-y-4 p-5'}>
      <div className={inShotEditor ? 'min-h-0 flex-1 space-y-5 overflow-y-auto scroll-thin p-5' : 'space-y-4'}>
        <fieldset disabled={mutation.isPending} className="min-w-0 space-y-5">{children}</fieldset>
        {inShotEditor && !generateDirectly ? (
          <details className="space-y-3 rounded-card border border-stroke/25 p-3 text-xs text-muted">
            <summary className="cursor-pointer">更多出图设置</summary>
            {options}
          </details>
        ) : options}
      </div>
      <footer className={inShotEditor ? 'space-y-2 border-t border-stroke/20 px-5 py-4' : 'space-y-2'}>
        {(mutation.error || context.error) && <p role="alert" className="text-sm text-error-soft">{(mutation.error ?? context.error)?.message}</p>}
        {referenceHint && showReferenceHint && <div id={hintId}>{referenceHint}</div>}
        <div className="flex items-center justify-end gap-2">
          {inShotEditor && <span className="mr-auto text-xs text-muted">
            {original ? '按所选历史设定生成' : '按当前镜头设定生成'}
          </span>}
          {!inShotEditor && <Button type="button" size="sm" variant="ghost" disabled={mutation.isPending} onClick={onClose}>取消</Button>}
          <Button type="submit" size="sm" disabled={referenceHint ? disabled || mutation.isPending : !!blocked}
            aria-disabled={!!referenceHint || undefined} aria-describedby={referenceHint && showReferenceHint ? hintId : undefined}
            className={referenceHint ? blockedGenerationStyle : undefined}>{mutation.isPending ? '正在提交…' : '生成新候选'}</Button>
        </div>
      </footer>
    </form>
  );
}
