import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { api, type Schema } from '@/lib/api';
import { useStore } from '@/lib/store';
import { ChildExecutionRecord } from './AgentExecution';

const stateLabels = { QUEUED: '等待执行', RUNNING: '执行中', READY: '待审阅', APPLIED: '已应用', FAILED: '失败', UNKNOWN: '结果未知' };
const active = (turn?: Schema['CreativeTurn']) => turn?.state === 'QUEUED' || turn?.state === 'RUNNING';
const ready = (turn?: Schema['CreativeTurn']) => turn?.state === 'READY' || turn?.state === 'APPLIED';

export function creativeEpisodeKey(userId?: string, episodeId?: string) {
  return ['creative-episode', userId, episodeId];
}

function useAction(key: readonly unknown[]) {
  const cache = useQueryClient();
  const { performCreative } = useStore();
  const request = useRef<{ fingerprint: string; id: string } | undefined>(undefined);
  const action = useMutation({
    mutationFn: async ({ path, data }: { path: string; data?: object }) => {
      if (!performCreative) return;
      const fingerprint = JSON.stringify([path, data]);
      if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, id: crypto.randomUUID() };
      const id = request.current.id;
      await performCreative(path + id, () => api(path, 'POST', data === undefined ? undefined : { ...data, requestKey: id }));
      request.current = undefined;
    },
    onSettled: () => cache.invalidateQueries({ queryKey: key }),
  });
  return action;
}

function Status({ turn }: { turn?: Schema['CreativeTurn'] }) {
  if (!turn?.state) return null;
  return <p className="mt-2 text-xs text-muted">
    {stateLabels[turn.state]}{turn.summary ? ` · ${turn.summary}` : ''}{turn.error ? ` · ${turn.error}` : ''}
    {!turn.current && turn.state !== 'APPLIED' ? ' · 输入已更新' : ''}
  </p>;
}

export function ProjectScriptImport({ projectId }: { projectId: string }) {
  const { user } = useStore();
  const base = `/api/projects/${projectId}/creative`;
  const key = ['creative-project', user?.id, projectId];
  const project = useQuery({ queryKey: key, queryFn: ({ signal }) => api<Schema['CreativeProject']>(base, 'GET', undefined, signal),
    refetchInterval: 2500 });
  const [notes, setNotes] = useState('');
  const [uploadError, setUploadError] = useState('');
  const action = useAction(key);
  const source = project.data?.source;
  const plan = project.data?.plan;
  const preview = useQuery({
    queryKey: [...key, 'plan', plan?.id], enabled: !!plan?.id && ready(plan),
    queryFn: ({ signal }) => api<Schema['CreativePlanPreview']>(`${base}/plan/${plan?.id}`, 'GET', undefined, signal),
  });

  async function upload(file?: File) {
    if (!file) return;
    setUploadError('');
    try {
      if (!/\.(txt|md)$/i.test(file.name) || file.size > 1024 * 1024)
        throw new Error('请选择不超过 1 MiB 的 TXT 或 Markdown 文件');
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      action.mutate({ path: `${base}/source`, data: { name: file.name, text } });
    } catch (error) {
      setUploadError(error instanceof Error ? error.message : '无法读取文件');
    }
  }

  return <section aria-label="整稿分集" className="mt-5 space-y-3 rounded-panel border border-stroke/20 bg-panel/50 p-4">
    <h3 className="text-sm font-medium">整稿分集</h3>
    <p className="text-xs text-muted">上传整部原文，审阅每集范围后确认建集。</p>
    <label className="block text-xs text-muted">
      整稿文件
      <input type="file" accept=".txt,.md" disabled={action.isPending} className="mt-1 block max-w-full text-xs"
        onChange={event => { const file = event.currentTarget.files?.[0]; event.currentTarget.value = ''; void upload(file); }} />
    </label>
    {source && <>
      <p className="break-words text-xs text-muted">{source.name} · {source.characters} 字</p>
      <label className="block text-xs text-muted">
        分集要求
        <input className="field mt-1 w-full text-sm" value={notes} maxLength={500} onChange={event => setNotes(event.target.value)} />
      </label>
      <Button size="sm" variant="outline" disabled={action.isPending || active(plan) || (plan?.current && plan.state === 'APPLIED')}
        onClick={() => action.mutate({ path: `${base}/plan`, data: { instruction: notes } })}>生成分集草案</Button>
    </>}
    <Status turn={plan} />
    {plan?.current && preview.data?.episodes && <ol className="max-h-80 space-y-2 overflow-y-auto text-xs">
      {preview.data.episodes.map((episode, index) => <li key={episode.start} className="rounded-card border border-stroke/20 p-3">
        <p className="font-medium">{index + 1}. {episode.title}</p>
        <p className="mt-1 text-muted">{episode.summary}</p>
        <details className="mt-1 text-muted">
          <summary className="cursor-pointer">核对原文边界</summary>
          <p className="mt-1 whitespace-pre-wrap break-words">开头　{episode.opening}</p>
          <p className="mt-1 whitespace-pre-wrap break-words">结尾　{episode.closing}</p>
        </details>
      </li>)}
    </ol>}
    {plan?.current && plan.state === 'READY' && plan.id && preview.isSuccess && <Button size="sm"
      disabled={action.isPending} onClick={() => action.mutate({ path: `${base}/plan/${plan.id}/accept` })}>确认草案并创建分集</Button>}
    {(uploadError || action.error || project.error || preview.error) && <p role="alert" className="text-xs text-error-soft">
      {uploadError || (action.error ?? project.error ?? preview.error)?.message}
    </p>}
  </section>;
}

export function EpisodeCreativePanel({ episodeId, hasShots }: { episodeId: string; hasShots: boolean }) {
  const { user } = useStore();
  const base = `/api/episodes/${episodeId}/creative`;
  const key = creativeEpisodeKey(user?.id, episodeId);
  const episode = useQuery({ queryKey: key, queryFn: ({ signal }) => api<Schema['CreativeEpisode']>(base, 'GET', undefined, signal),
    refetchInterval: 2500 });
  const [notes, setNotes] = useState('');
  const [showScript, setShowScript] = useState(false);
  const [showBoard, setShowBoard] = useState(false);
  const action = useAction(key);
  const script = episode.data?.script;
  const board = episode.data?.storyboard;
  const scriptPreview = useQuery({
    queryKey: [...key, 'script', script?.id], enabled: showScript && !!script?.id && ready(script),
    queryFn: ({ signal }) => api<Schema['ScriptDraft']>(`${base}/script/${script?.id}`, 'GET', undefined, signal),
  });
  const boardPreview = useQuery({
    queryKey: [...key, 'storyboard', board?.id], enabled: showBoard && !!board?.id && ready(board),
    queryFn: ({ signal }) => api<Schema['CreativeStoryboard']>(`${base}/storyboard/${board?.id}`, 'GET', undefined, signal),
  });
  return <section aria-label="本集创作会话" className="space-y-3 rounded-panel border border-stroke/20 bg-panel/60 p-5">
    <h3 className="text-sm font-medium">本集创作会话</h3>
    <label className="block text-xs text-muted">
      本轮创作要求
      <input className="field mt-1 w-full text-sm" value={notes} maxLength={500} onChange={event => setNotes(event.target.value)} />
    </label>
    <div>
      <p className="text-xs font-medium">剧本子 Agent</p>
      <Status turn={script} />
      <ChildExecutionRecord id={script?.id} userId={user?.id} />
      <div className="mt-2 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={action.isPending || active(script)}
          onClick={() => action.mutate({ path: `${base}/script`, data: { instruction: notes } })}>整理本集剧本</Button>
        {ready(script) && <Button size="sm" variant="ghost" onClick={() => setShowScript(!showScript)}>
          {showScript ? '收起脚本' : '审阅脚本'}
        </Button>}
      </div>
      {showScript && scriptPreview.data && <p className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words text-xs text-muted">
        {scriptPreview.data.script}
      </p>}
    </div>
    <div>
      <p className="text-xs font-medium">分镜子 Agent</p>
      <Status turn={board} />
      <ChildExecutionRecord id={board?.id} userId={user?.id} />
      <div className="mt-2 flex flex-wrap gap-2">
        <Button size="sm" variant="outline" disabled={action.isPending || active(board) || !script?.current || script.state !== 'READY'}
          onClick={() => action.mutate({ path: `${base}/storyboard`, data: { scriptId: script?.id, instruction: notes } })}>规划本集分镜</Button>
        {ready(board) && <Button size="sm" variant="ghost" onClick={() => setShowBoard(!showBoard)}>
          {showBoard ? '收起分镜草案' : '审阅分镜草案'}
        </Button>}
      </div>
      {showBoard && boardPreview.data?.shots && <ol className="mt-2 max-h-52 space-y-1 overflow-auto text-xs text-muted">
        {boardPreview.data.shots.map((shot, index) => <li key={index}>{index + 1}. {shot.shotSize} · {shot.description} {shot.dialogue}</li>)}
      </ol>}
      {showBoard && boardPreview.isSuccess && board?.current && board.state === 'READY' && !hasShots &&
        <Button className="mt-2" size="sm" disabled={action.isPending}
          onClick={() => action.mutate({ path: `${base}/storyboard/${board.id}/apply` })}>应用本集分镜草案</Button>}
    </div>
    {(action.error || episode.error || scriptPreview.error || boardPreview.error) && <p role="alert" className="text-xs text-error-soft">
      {(action.error ?? episode.error ?? scriptPreview.error ?? boardPreview.error)?.message}
    </p>}
  </section>;
}
