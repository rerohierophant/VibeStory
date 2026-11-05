import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { Modal } from '@/components/Modal';
import { api, type Schema } from '@/lib/api';
import { useProject, useStore } from '@/lib/store';

type TargetType = 'SHOT' | 'CHARACTER' | 'SCENE';
type Preference = Schema['ImagePreference'];
type Recipe = Schema['ImageRecipe'];
type Creation = Schema['ImageCreation'];
type Editor = { recipe?: Recipe; originId?: string; name: string; template: string };
const base = '/api/image-experience';
const scopes = { TARGET: '仅此素材', PROJECT: '本项目同类图片', USER: '我所有项目的同类图片' };
const states: Record<string, string> = { QUEUED: '排队中', RUNNING: '生成中', SUCCEEDED: '已生成', FAILED: '生成失败', UNKNOWN: '结果未知', CANCELED: '已取消', MISSING: '结果已移除' };

export function ImageLearning({ projectId, targetType, targetId }: { projectId: string; targetType: TargetType; targetId: string }) {
  const { mode, workspaceReadOnly } = useStore();
  const project = useProject(projectId);
  const [open, setOpen] = useState(false);
  if (mode !== 'api' || project?.teamId) return null;
  return <>
    <Button size="sm" variant="ghost" disabled={workspaceReadOnly} onClick={() => setOpen(true)}>纠正与偏好</Button>
    {open && <LearningPanel key={`${targetType}-${targetId}`} targetType={targetType} targetId={targetId} onClose={() => setOpen(false)} />}
  </>;
}

function LearningPanel({ targetType, targetId, onClose }: { targetType: TargetType; targetId: string; onClose: () => void }) {
  const { user } = useStore();
  const cache = useQueryClient();
  const [source, setSource] = useState<string>();
  const [instruction, setInstruction] = useState('');
  const [recipeVersionId, setRecipeVersionId] = useState('');
  const [parameters, setParameters] = useState<Record<string, string>>({});
  const [remember, setRemember] = useState<{ id: string; rule: string; scope: keyof typeof scopes }>();
  const [editor, setEditor] = useState<Editor>();
  const [notice, setNotice] = useState('');
  const keys = useRef(new Map<string, string>());
  const context = useQuery({
    queryKey: ['image-experience', user?.id, targetType, targetId],
    queryFn: ({ signal }) => api<Schema['ImageExperienceContext']>(`${base}/targets/${targetType}/${targetId}`, 'GET', undefined, signal),
    refetchInterval: query => query.state.data?.creations?.some(item => ['QUEUED', 'RUNNING'].includes(item.taskState ?? '')) ? 1500 : false,
  });
  const data = context.data;
  const examples = data?.examples ?? [];
  const sourceTaskId = source ?? examples.find(item => item.selected)?.taskId ?? examples[0]?.taskId ?? '';
  const recipes = data?.recipes ?? [];
  const chosenRecipe = recipes.find(recipe => recipe.versions?.some(version => version.id === recipeVersionId));
  const version = chosenRecipe?.versions?.find(item => item.id === recipeVersionId);
  const mutation = useMutation({
    mutationFn: (action: () => Promise<void>) => action(),
    onSuccess: async () => {
      await cache.invalidateQueries({ queryKey: ['image-experience', user?.id] });
      await cache.invalidateQueries({ queryKey: ['workspace', user?.id] });
    },
  });
  const busy = mutation.isPending;

  function run(action: () => Promise<void>) {
    if (busy) return;
    setNotice('');
    mutation.mutate(action);
  }

  async function submitOnce<T>(path: string, payload: object) {
    const fingerprint = JSON.stringify({ path, payload });
    let requestKey = keys.current.get(fingerprint);
    if (!requestKey) { requestKey = crypto.randomUUID(); keys.current.set(fingerprint, requestKey); }
    const result = await api<T>(path, 'POST', { ...payload, requestKey });
    keys.current.delete(fingerprint);
    return result;
  }

  function generate() {
    run(async () => {
      const result = await submitOnce<Creation>(`${base}/creations`, { targetType, targetId,
        sourceTaskId: sourceTaskId || null, instruction: instruction.trim(), recipeVersionId: recipeVersionId || null, parameters });
      setSource(sourceTaskId);
      setInstruction('');
      setNotice(`已提交新候选。生成完成后可在下方比较并记录满意或不满意。任务 ${result.taskId}`);
    });
  }

  function decide(item: Creation, decision: 'ACCEPTED' | 'REJECTED') {
    run(async () => {
      await api(`${base}/creations/${item.id}/decision`, 'PUT', { decision });
      setNotice(decision === 'ACCEPTED' ? '已记录满意结果，可以保存可复用偏好或创作配方。' : '已记录不满意结果，原纠正和生成结果已保留。');
    });
  }

  function savePreference() {
    if (!remember) return;
    run(async () => {
      await api(`${base}/creations/${remember.id}/preferences`, 'POST', { scope: remember.scope, rule: remember.rule.trim() });
      setRemember(undefined);
      setNotice('偏好已保存。下次生成相同范围的同类图片时会自动参考。');
    });
  }

  function saveRecipe() {
    if (!editor) return;
    run(async () => {
      const saved = await submitOnce<Recipe>(`${base}/recipes`, { recipeId: editor.recipe?.id ?? null,
        expectedVersion: editor.recipe?.version ?? 0, targetType, name: editor.name.trim(), template: editor.template.trim(), originId: editor.originId ?? null });
      setEditor(undefined);
      setNotice(`已保存配方「${saved.name}」v${saved.version}，可在其他项目的同类素材上填参使用。`);
    });
  }

  const field = 'w-full rounded-card border border-stroke/30 bg-panel-2/40 px-3 py-2 text-sm text-fg';
  return <Modal open onClose={onClose} width="max-w-4xl" labelledBy="image-learning-title">
    <header className="flex items-center justify-between border-b border-stroke/20 px-6 py-4">
      <h2 id="image-learning-title" className="text-sm font-medium">{data?.name ?? '图片'} · 纠正与偏好</h2>
      <Button size="sm" variant="ghost" onClick={onClose}>关闭</Button>
    </header>
    <div className="max-h-[78vh] space-y-6 overflow-y-auto p-6">
      {context.isLoading && <p className="text-sm text-muted">正在读取图片与创作经验…</p>}
      {(context.error || mutation.error) && <p role="alert" className="text-sm text-error-soft">{context.error?.message ?? mutation.error?.message}</p>}
      {notice && <p role="status" className="text-sm text-fg">{notice}</p>}
      {data && <>
        <section aria-label="按要求生成图片" className="space-y-3">
          <label className="block space-y-1 text-xs text-muted">修改依据
            <select aria-label="修改依据" className={field} value={sourceTaskId} onChange={e => setSource(e.target.value)}>
              <option value="">从当前素材开始生成</option>
              {examples.map(item => <option key={item.taskId} value={item.taskId}>生成结果 #{item.taskId}{item.selected ? ' · 当前选定' : ''}</option>)}
            </select>
          </label>
          {!!sourceTaskId && examples.find(item => item.taskId === sourceTaskId)?.url && <img
            className="max-h-48 rounded-card object-contain" src={examples.find(item => item.taskId === sourceTaskId)?.url} alt="本次纠正的原图" />}
          <p className="text-xs text-muted">根据原生成要求和本次纠正重新生成。满意后可回到工作区，将候选选为基准或首帧。</p>
          <label className="block space-y-1 text-xs text-muted">创作配方
            <select aria-label="创作配方" className={field} value={recipeVersionId} onChange={e => { setRecipeVersionId(e.target.value); setParameters({}); }}>
              <option value="">沿用原图配方或按本次要求生成</option>
              {recipes.map(recipe => <optgroup key={recipe.id} label={recipe.name}>
                {recipe.versions?.map(item => <option key={item.id} value={item.id}>{recipe.name} · v{item.version}</option>)}
              </optgroup>)}
            </select>
          </label>
          {version && <div className="space-y-2 rounded-card border border-stroke/20 p-3">
            <p className="whitespace-pre-wrap text-xs text-muted">{version.template}</p>
            <p className="text-xs text-muted">{chosenRecipe?.steps?.join(' → ')}</p>
            {version.slots?.map(slot => <label key={slot} className="block space-y-1 text-xs text-muted">{slot}
              <input className={field} aria-label={`配方参数 ${slot}`} maxLength={1000} value={parameters[slot] ?? ''} onChange={e => setParameters({ ...parameters, [slot]: e.target.value })} />
            </label>)}
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setEditor({ recipe: chosenRecipe, originId: version.originId,
              name: chosenRecipe?.name ?? '', template: version.template ?? '' })}>编辑为新版本</Button>
          </div>}
          <label className="block space-y-1 text-xs text-muted">这次想怎么改
            <textarea className={field} aria-label="这次想怎么改" rows={3} maxLength={1000} value={instruction}
              placeholder="例如，脸部自然一点，衣服颜色降低饱和度" onChange={e => setInstruction(e.target.value)} />
          </label>
          <Button size="sm" disabled={busy || !instruction.trim() || version?.slots?.some(slot => !parameters[slot]?.trim())} onClick={generate}>按要求生成候选</Button>
        </section>

        <section aria-label="已确认的图片偏好" className="space-y-3 border-t border-stroke/20 pt-4">
          <h3 className="text-sm font-medium">下次自动参考的偏好</h3>
          {!data.preferences?.length && <p className="text-xs text-muted">满意结果的纠正可以保存为偏好，并选择适用范围。</p>}
          {data.preferences?.map(item => <PreferenceRow key={`${item.id}-${item.version}`} item={item} busy={busy} field={field} onSave={(rule, enabled) => run(async () => {
            await api(`${base}/preferences/${item.id}`, 'PUT', { expectedVersion: item.version, rule, enabled });
            setNotice('偏好已更新，已提交任务保留各自的生成依据。');
          })} />)}
        </section>

        <section aria-label="图片纠正记录" className="space-y-4 border-t border-stroke/20 pt-4">
          <h3 className="text-sm font-medium">生成与纠正记录</h3>
          {!data.creations?.length && <p className="text-xs text-muted">提交生成要求或在 Agent 对话中纠正图片后，原要求、结果与评价会显示在这里。</p>}
          {data.creations?.map(item => <article key={item.id} className="space-y-3 rounded-card border border-stroke/25 p-4">
            <p className="whitespace-pre-wrap text-sm">{item.instruction}</p>
            <div className="grid grid-cols-2 gap-3">
              {item.sourceUrl && <figure><img className="max-h-52 w-full rounded-card object-contain" src={item.sourceUrl} alt="纠正前的图片" /><figcaption className="mt-1 text-xs text-muted">原结果</figcaption></figure>}
              {item.resultUrl && <figure><img className="max-h-52 w-full rounded-card object-contain" src={item.resultUrl} alt="按要求生成的候选图片" /><figcaption className="mt-1 text-xs text-muted">新候选</figcaption></figure>}
            </div>
            <p className="text-xs text-muted">{states[item.taskState ?? ''] ?? item.taskState} · {item.decision === 'ACCEPTED' ? '已评价满意' : item.decision === 'REJECTED' ? '已评价不满意' : '待评价'}{item.error && ` · ${item.error}`}</p>
            {item.resultUrl && item.decision === 'PENDING' && <div className="flex gap-2">
              <Button size="sm" disabled={busy} onClick={() => decide(item, 'ACCEPTED')}>这版满意</Button>
              <Button size="sm" variant="outline" disabled={busy} onClick={() => decide(item, 'REJECTED')}>仍不满意</Button>
            </div>}
            {item.decision === 'ACCEPTED' && <div className="flex gap-2">
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setRemember({ id: item.id!, rule: item.instruction ?? '', scope: 'TARGET' })}>记为偏好</Button>
              <Button size="sm" variant="outline" disabled={busy} onClick={() => setEditor({ originId: item.id, name: '', template: item.instruction ?? '' })}>沉淀为配方</Button>
            </div>}
            <details className="text-xs text-muted"><summary className="cursor-pointer">查看本次生成依据</summary>
              <p className="mt-2 whitespace-pre-wrap">{item.prompt}</p>
              {item.learning?.preferences?.map(p => <p key={p.id} className="mt-1">偏好 v{p.version} · {p.rule}</p>)}
              {item.learning?.recipe && <p className="mt-1">配方 {item.learning.recipe.name} · v{item.learning.recipe.version}</p>}
            </details>
          </article>)}
        </section>

        {remember && <section aria-label="保存图片偏好" className="space-y-3 rounded-card border border-accent/40 p-4">
          <h3 className="text-sm font-medium">确认要记住的偏好</h3>
          <textarea aria-label="偏好内容" className={field} rows={3} maxLength={1000} value={remember.rule} onChange={e => setRemember({ ...remember, rule: e.target.value })} />
          <select aria-label="偏好适用范围" className={field} value={remember.scope} onChange={e => setRemember({ ...remember, scope: e.target.value as keyof typeof scopes })}>
            {Object.entries(scopes).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
          </select>
          <div className="flex gap-2"><Button size="sm" disabled={busy || !remember.rule.trim()} onClick={savePreference}>保存偏好</Button>
            <Button size="sm" variant="ghost" onClick={() => setRemember(undefined)}>取消</Button></div>
        </section>}

        {editor && <section aria-label="保存创作配方" className="space-y-3 rounded-card border border-accent/40 p-4">
          <h3 className="text-sm font-medium">{editor.recipe ? `更新配方「${editor.name}」` : '沉淀创作配方'}</h3>
          <label className="block space-y-1 text-xs text-muted">配方名称
            <input aria-label="配方名称" className={field} maxLength={120} disabled={!!editor.recipe} value={editor.name} onChange={e => setEditor({ ...editor, name: e.target.value })} />
          </label>
          <label className="block space-y-1 text-xs text-muted">可复用要求
            <textarea aria-label="配方要求" className={field} rows={4} maxLength={1000} value={editor.template} onChange={e => setEditor({ ...editor, template: e.target.value })} />
          </label>
          <p className="text-xs text-muted">人物与场景由下次选中的素材提供。可变文字可写成 {'{{lighting}}'}，运行时填入。请核对并移除本次专属描述。</p>
          <div className="flex gap-2"><Button size="sm" disabled={busy || !editor.name.trim() || !editor.template.trim()} onClick={saveRecipe}>保存配方版本</Button>
            <Button size="sm" variant="ghost" onClick={() => setEditor(undefined)}>取消</Button></div>
        </section>}
      </>}
    </div>
  </Modal>;
}

function PreferenceRow({ item, busy, field, onSave }: { item: Preference; busy: boolean; field: string; onSave: (rule: string, enabled: boolean) => void }) {
  const [rule, setRule] = useState(item.rule ?? '');
  return <div className="space-y-2 rounded-card border border-stroke/20 p-3">
    <p className="text-xs text-muted">{scopes[item.scope ?? 'TARGET']} · v{item.version} · {item.enabled ? '已启用' : '已停用'}</p>
    <textarea aria-label="编辑偏好" className={field} rows={2} maxLength={1000} value={rule} onChange={e => setRule(e.target.value)} />
    <div className="flex gap-2"><Button size="sm" variant="outline" disabled={busy || !rule.trim() || rule === item.rule} onClick={() => onSave(rule.trim(), !!item.enabled)}>保存修改</Button>
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => onSave(item.rule ?? '', !item.enabled)}>{item.enabled ? '停用偏好' : '启用偏好'}</Button></div>
  </div>;
}
