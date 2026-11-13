import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { api, type Schema } from '@/lib/api';
import { useStore } from '@/lib/store';
import { CreativePlanProgress } from './CreativePlanProgress';
import { useCreativeAction } from './CreativePanels';
import { StoryInspirations } from './StoryInspirations';

type SourceKind = NonNullable<Schema['CreativeSource']['kind']>;
const IDEA_MAX_LENGTH = 3000;

function Step({ number, title, children }: { number: string; title: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-3 border-t border-stroke/20 py-5 sm:grid-cols-[10rem_minmax(0,1fr)]">
      <h3 className="flex items-baseline gap-2.5 text-sm font-medium">
        <span className="font-mono text-xs text-accent-soft">{number}</span>{title}
      </h3>
      <div className="min-w-0">{children}</div>
    </div>
  );
}

function AssetChecklist({ title, assets }: { title: string; assets: Schema['AssetDraft'][] }) {
  return (
    <div className="min-w-0 rounded-card border border-stroke/25 bg-panel-2/30 p-3">
      <h4 className="text-xs font-medium">{title} <span className="ml-1 text-muted">{assets.length}</span></h4>
      {assets.length ? (
        <ul className="mt-2 max-h-64 divide-y divide-stroke/15 overflow-auto scroll-thin">
          {assets.map((asset) => (
            <li key={asset.name} className="py-2 text-xs leading-5">
              <p className="break-words font-medium">{asset.name}</p>
              {asset.description && <p className="break-words text-muted">{asset.description}</p>}
            </li>
          ))}
        </ul>
      ) : <p className="mt-2 text-xs text-muted">这份草案没有提议{title}。</p>}
    </div>
  );
}

export function ProjectSetup({ projectId, hasEpisodes }: { projectId: string; hasEpisodes: boolean }) {
  const { user } = useStore();
  const base = `/api/projects/${projectId}/creative`;
  const key = ['creative-project', user?.id, projectId];
  const project = useQuery({
    queryKey: key, queryFn: ({ signal }) => api<Schema['CreativeProject']>(base, 'GET', undefined, signal),
    refetchInterval: (query) => {
      const state = query.state.data?.plan?.state;
      return state === 'QUEUED' || state === 'RUNNING' ? 600 : false;
    },
  });
  const action = useCreativeAction(key);
  const [choice, setChoice] = useState<SourceKind>();
  const [idea, setIdea] = useState('');
  const [notes, setNotes] = useState('');
  const [showInput, setShowInput] = useState(false);
  const [reading, setReading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const source = project.data?.source;
  const savedIdea = source?.kind === 'IDEA' ? source.ideaText : undefined;
  const plan = project.data?.plan;
  const mode = choice ?? source?.kind ?? 'MANUSCRIPT';
  const current = plan?.current ? plan : undefined;
  const working = plan?.state === 'QUEUED' || plan?.state === 'RUNNING';
  const submittingPlan = action.isPending && action.variables?.path === `${base}/plan`;
  const canReview = current?.state === 'READY' && !submittingPlan;
  const applied = current?.state === 'APPLIED';
  const busy = action.isPending || reading || working || project.isPending;
  const preview = useQuery({
    queryKey: [...key, 'plan', current?.id], enabled: !!current?.id && canReview,
    queryFn: ({ signal }) => api<Schema['CreativePlanPreview']>(`${base}/plan/${current?.id}`, 'GET', undefined, signal),
  });
  const draft = preview.data;
  const error = uploadError || (action.error ?? project.error ?? preview.error)?.message;
  const changingSource = !source || showInput;

  function storeSource(name: string, text: string, kind: SourceKind) {
    return action.mutateAsync({ path: `${base}/source`, data: { name, text, kind } }).then(() => {
      setShowInput(false);
      setChoice(undefined);
    }).catch(() => { /* The mutation displays the request error. */ });
  }

  async function upload(file?: File) {
    if (!file) return;
    setUploadError('');
    action.reset();
    setReading(true);
    try {
      if (!/\.(txt|md)$/i.test(file.name) || file.size > 1024 * 1024)
        throw new Error('请选择不超过 1 MiB 的 TXT 或 Markdown 文件');
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      await storeSource(file.name, text, 'MANUSCRIPT');
    } catch (cause) {
      setUploadError(cause instanceof Error ? cause.message : '无法读取文件');
    } finally { setReading(false); }
  }

  const content = (
    <div className="px-5 sm:px-6">
      <Step number="01" title="准备故事">
        {source && (
          <div className="rounded-card bg-panel-2/50 px-4 py-2.5">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="min-w-0 break-words text-sm">{source.name} <span className="ml-2 text-xs text-muted">{source.characters} 字</span></p>
              <Button size="sm" variant="ghost" disabled={busy} onClick={() => {
                setUploadError('');
                action.reset();
                if (!showInput) {
                  setChoice(undefined);
                  setIdea(savedIdea ?? '');
                }
                setShowInput(!showInput);
              }}>{source.kind === 'IDEA' ? (showInput ? '取消编辑' : '编辑想法') : (showInput ? '取消更换' : '更换来源')}</Button>
            </div>
            {savedIdea && !showInput && (
              <p data-testid="saved-story-idea" className="mt-1.5 whitespace-pre-wrap wrap-anywhere text-sm leading-6">{savedIdea}</p>
            )}
          </div>
        )}
        {changingSource && (
          <div className={source ? 'mt-4' : ''}>
            <div role="group" aria-label="故事来源" className="mb-3 inline-flex flex-wrap gap-1 rounded-card border border-stroke/20 bg-panel-2/40 p-1">
              {([{ kind: 'MANUSCRIPT', label: '已有剧本' }, { kind: 'IDEA', label: '只有故事想法' }] as const).map((option) => (
                <Button key={option.kind} size="sm" variant="ghost" disabled={busy} aria-pressed={mode === option.kind}
                  className={mode === option.kind ? 'bg-hover text-fg' : ''} onClick={() => {
                    setChoice(option.kind);
                    setUploadError('');
                    action.reset();
                  }}>{option.label}</Button>
              ))}
            </div>
            {mode === 'MANUSCRIPT' ? (
              <label className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-dashed border-stroke/40 bg-panel-2/20 p-4 text-xs text-muted">
                <span><span className="block font-medium text-fg">整稿文件</span><span className="mt-1 block">TXT 或 Markdown · 不超过 1 MiB</span></span>
                <input aria-label="整稿文件" type="file" accept=".txt,.md" disabled={busy}
                  className="max-w-full text-xs file:mr-2 file:rounded file:border-0 file:bg-hover file:px-3 file:py-2 file:text-fg"
                  onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void upload(file); }} />
              </label>
            ) : (
              <div className="space-y-2">
                <textarea aria-label="故事想法" className="field min-h-28 w-full resize-y text-sm leading-6"
                  placeholder="例如：一名夜班店员发现，每天最后一位顾客都来自不同的年份。"
                  maxLength={IDEA_MAX_LENGTH} value={idea} disabled={busy} onChange={event => setIdea(event.target.value)} />
                <StoryInspirations onSelect={setIdea} disabled={busy} />
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-xs text-muted">写下主角、冲突或故事发生的地方，至少 10 字。</p>
                  <Button size="sm" variant="outline" disabled={busy || idea.trim().length < 10 || idea.trim() === savedIdea}
                    onClick={() => { setUploadError(''); void storeSource('故事想法', idea.trim(), 'IDEA'); }}>保存想法</Button>
                </div>
              </div>
            )}
          </div>
        )}
      </Step>
      <Step number="02" title="审阅创作草案">
        {!source && <p className="text-sm leading-6 text-muted">导入剧本或保存想法后，这里会显示分集，以及建议先准备参考图的角色和场景。</p>}
        {source && !applied && (
          <div className="space-y-3">
            <label className="block text-xs text-muted">
              分集要求 <span className="text-muted/75">（选填）</span>
              <input className="field mt-1 w-full text-sm" value={notes} maxLength={500} disabled={busy || changingSource}
                placeholder="例如：分成三集，保留原来的结局" onChange={event => setNotes(event.target.value)} />
            </label>
            <div className="flex flex-wrap items-center gap-3">
              <Button size="sm" variant={canReview ? 'outline' : 'primary'} disabled={busy || changingSource}
                onClick={() => { setUploadError(''); action.mutate({ path: `${base}/plan`, data: { instruction: notes } }); }}>
                {working || submittingPlan ? '正在生成草案…' : canReview ? '重新生成草案' : '生成分集草案'}
              </Button>
              {!working && !submittingPlan && current?.summary && <p role="status" className="text-xs text-muted">{current.summary}</p>}
              {!submittingPlan && current?.error && <p role="alert" className="text-xs text-error-soft">{current.error}</p>}
            </div>
          </div>
        )}
        {(submittingPlan || (working && current)) && (
          <CreativePlanProgress key={current?.id}
            status={submittingPlan && !working ? '正在提交分集草案任务…'
              : [current?.state === 'RUNNING' ? '正在生成分集草案…' : '等待生成分集草案…', current?.summary].filter(Boolean).join(' · ')}
            snapshot={project.data?.planPreviewText ?? undefined} />
        )}
        {canReview && preview.isPending && <p role="status" className="mt-3 text-xs text-muted">正在读取草案…</p>}
        {canReview && draft && (
          <div className="mt-4 space-y-3">
            <h4 className="text-xs font-medium">分集 <span className="ml-1 text-muted">{draft.episodes?.length ?? 0}</span></h4>
            <ol className="max-h-80 space-y-2 overflow-auto scroll-thin">
              {draft.episodes?.map((episode, index) => (
                <li key={index} className="rounded-card border border-stroke/25 bg-panel-2/20 p-3 text-xs leading-5">
                  <p className="font-medium">{index + 1}. {episode.title}</p>
                  <p className="mt-1 whitespace-pre-wrap break-words text-muted">{episode.summary}</p>
                  <details className="mt-1 text-muted">
                    <summary className="w-fit cursor-pointer hover:text-fg">{episode.story ? '审阅本集剧本' : '核对原文边界'}</summary>
                    {episode.story
                      ? <p className="mt-1 whitespace-pre-wrap break-words">{episode.story}</p>
                      : <>
                        <p className="mt-1 whitespace-pre-wrap break-words">开头　{episode.opening}</p>
                        <p className="mt-1 whitespace-pre-wrap break-words">结尾　{episode.closing}</p>
                      </>}
                  </details>
                </li>
              ))}
            </ol>
            <div className="grid gap-2 sm:grid-cols-2">
              <AssetChecklist title="待出图角色" assets={draft.characters ?? []} />
              <AssetChecklist title="待出图场景" assets={draft.scenes ?? []} />
            </div>
          </div>
        )}
        {applied && <Badge tone="success">创作计划已确认</Badge>}
      </Step>
      {canReview && draft && (
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-stroke/20 py-4">
          <p className="text-xs leading-5 text-muted">{hasEpisodes
            ? '确认后追加分集和素材条目，已有分集和同名素材会保留。'
            : '确认后建立分集与素材条目，参考图可在角色库和场景库生成。'}</p>
          <Button disabled={busy || changingSource} onClick={() => action.mutate({ path: `${base}/plan/${current.id}/accept` })}>
            确认分集与素材
          </Button>
        </div>
      )}
      {error && <p role="alert" className="pb-4 text-xs text-error-soft">{error}</p>}
    </div>
  );

  return (
    <section aria-label="创作准备" className="overflow-hidden rounded-panel border border-stroke/30 bg-panel/65">
      {hasEpisodes ? (
        <details className="group">
          <summary className="flex cursor-pointer list-none items-center gap-3 px-5 py-3.5 sm:px-6 [&::-webkit-details-marker]:hidden">
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-medium">创作准备</span>
              <span className="mt-0.5 block text-xs leading-5 text-muted">导入剧本或故事想法，追加新的分集</span>
            </span>
            {working && <Badge tone="accent">草案生成中</Badge>}
            {canReview && <Badge tone="warning">草案待确认</Badge>}
            <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
              className="size-4 shrink-0 text-muted transition-transform group-open:rotate-180">
              <path d="m4 6 4 4 4-4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </summary>
          {content}
        </details>
      ) : (
        <>
          <header className="px-5 py-5 sm:px-6">
            <h2 className="text-lg font-semibold">准备你的故事</h2>
            <p className="mt-1.5 text-sm leading-6 text-muted">从已有剧本或一个想法开始，在这里定好分集和要出图的角色、场景。</p>
          </header>
          {content}
        </>
      )}
    </section>
  );
}
