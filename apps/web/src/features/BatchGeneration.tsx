import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { Modal } from '@/components/Modal';
import { api, type Schema } from '@/lib/api';
import { useEpisodeShots, useStore } from '@/lib/store';

type Props = { episodeId: string; type: 'IMAGE' | 'VIDEO' };
const outcomes: Record<string, string> = { accepted: '已接收', reused: '复用任务', blocked: '已阻塞' };
const states: Record<string, string> = { queued: '排队中', running: '生成中', succeeded: '已生成', failed: '失败', unknown: '结果未知', canceled: '已取消', blocked: '未提交' };

export function BatchGeneration(props: Props) {
  const { mode } = useStore();
  return mode === 'api' ? <ApiBatchGeneration key={`${props.episodeId}-${props.type}`} {...props} /> : null;
}

function ApiBatchGeneration({ episodeId, type }: Props) {
  const { user, submitBatch, workspaceReadOnly } = useStore();
  const shots = useEpisodeShots(episodeId);
  const cache = useQueryClient();
  const key = ['batches', user?.id, episodeId, type];
  const batches = useQuery({ queryKey: key, queryFn: ({ signal }) => api<Schema['GenerationBatch'][]>(`/api/episodes/${episodeId}/generation-batches?type=${type}`, 'GET', undefined, signal), refetchInterval: 3000 });
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [regenerate, setRegenerate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<{ fingerprint: string; key: string } | null>(null);
  const [result, setResult] = useState<Schema['GenerationBatch']>();
  const latest = batches.data?.[0];
  const shown = batches.data?.find(b => b.id === result?.id) ?? result ?? latest;
  const label = type === 'IMAGE' ? '分镜图' : '视频';
  const problem = (id: string) => {
    const shot = shots.find(s => s.id === id)!;
    if (shot.status !== 'confirmed') return '尚未确认';
    if (type === 'VIDEO' && !shot.selectedImageId) return '缺少已选首帧';
    const state = type === 'IMAGE' ? shot.imageState : shot.video.state;
    if (state === 'unknown') return '结果未知，请先处理原任务';
    if (state === 'queued' || state === 'running') return '已有任务，提交时校验能否复用';
    return '可提交';
  };
  function show() {
    setSelected(shots.filter(s => s.status === 'confirmed' && (type === 'IMAGE' ? s.images.length === 0 : !s.video.ready)).slice(0, 200).map(s => s.id));
    setError(''); setOpen(true);
  }
  async function submit() {
    if (workspaceReadOnly || busy || !submitBatch) return;
    setBusy(true); setError('');
    const fingerprint = JSON.stringify([selected.slice().sort(), regenerate]);
    if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, key: crypto.randomUUID() };
    try {
      const batch = await submitBatch(episodeId, type, request.current.key, selected, regenerate);
      setResult(batch); request.current = null;
      await cache.invalidateQueries({ queryKey: key });
    } catch (cause) { setError(cause instanceof Error ? cause.message : '提交失败，请重试'); }
    finally { setBusy(false); }
  }
  async function resume(taskId: string) {
    try { await api(`/api/tasks/${taskId}/dispatch/resume`, 'POST', {}); await cache.invalidateQueries({ queryKey: key }); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '恢复失败'); }
  }
  return <div className="border-b border-stroke/15 px-6 py-3 text-xs">
    <div className="flex flex-wrap items-center gap-3">
      <Button size="sm" variant="outline" disabled={workspaceReadOnly} onClick={show}>本集批量生成{label}</Button>
      {latest?.progress && <button type="button" className="text-muted underline" onClick={() => { setResult(latest); setOpen(true); }}>
        最近批次 #{latest.id} · 已生成 {latest.progress.succeeded ?? 0}/{latest.progress.total} · 阻塞 {latest.progress.blocked ?? 0}{latest.progress.paused ? ` · 派发暂停 ${latest.progress.paused}` : ''}
      </button>}
      {batches.error && <span role="alert" className="text-error-soft">批次记录读取失败 <button className="underline" onClick={() => void batches.refetch()}>重试</button></span>}
    </div>
    <Modal open={open} onClose={() => { if (!busy) setOpen(false); }} labelledBy={`batch-title-${type}`}>
      <div className="space-y-4 p-6">
        <div className="flex items-center justify-between gap-3">
          <h2 id={`batch-title-${type}`} className="text-sm font-medium">本集批量生成{label}</h2>
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => setOpen(false)}>关闭</Button>
        </div>
        <p className="text-xs leading-5 text-muted">逐镜检查确认状态{type === 'VIDEO' ? '与当前选定首帧' : ''}。不满足条件的镜头会显示阻塞原因；已接收的任务在离开页面后继续执行。</p>
        <div className="flex flex-wrap gap-4 text-xs">
          <button type="button" disabled={busy} onClick={() => setSelected(shots.slice(0, 200).map(s => s.id))} className="underline">选择本集镜头（最多 200）</button>
          <button type="button" disabled={busy} onClick={() => setSelected([])} className="underline">清空选择</button>
          <label className="flex items-center gap-2"><input type="checkbox" disabled={busy} checked={regenerate} onChange={e => setRegenerate(e.target.checked)} />重新生成已选镜头</label>
        </div>
        <div className="max-h-60 overflow-y-auto rounded-card border border-stroke/20">
          {shots.map(shot => <label key={shot.id} className="flex items-center gap-3 border-b border-stroke/15 p-3 text-xs last:border-0">
            <input type="checkbox" aria-label={`选择镜头 ${shot.seq}`} disabled={busy || (!selected.includes(shot.id) && selected.length >= 200)} checked={selected.includes(shot.id)} onChange={e => setSelected(ids => e.target.checked ? [...ids, shot.id] : ids.filter(id => id !== shot.id))} />
            <span className="font-mono">{String(shot.seq).padStart(2, '0')}</span><span className="min-w-0 flex-1 truncate">{shot.description}</span><span className="text-muted">{problem(shot.id)}</span>
          </label>)}
        </div>
        <Button size="sm" disabled={workspaceReadOnly || busy || selected.length === 0} onClick={() => void submit()}>{busy ? '正在提交…' : `提交 ${selected.length} 镜`}</Button>
        {error && <p role="alert" className="text-xs text-error-soft">{error}</p>}
        {shown && <div aria-live="polite" className="space-y-2 text-xs">
          <h3 className="font-medium">批次 #{shown.id} · {shown.state === 'complete' ? '已结束' : shown.state === 'paused' ? '派发暂停' : '执行中'}</h3>
          <p className="text-muted">接收 {shown.progress?.accepted ?? 0} · 复用 {shown.progress?.reused ?? 0} · 阻塞 {shown.progress?.blocked ?? 0} · 已生成 {shown.progress?.succeeded ?? 0} · 失败 {shown.progress?.failed ?? 0} · 未知 {shown.progress?.unknown ?? 0}</p>
          <ul className="max-h-52 space-y-2 overflow-y-auto">{shown.members?.map(member => <li key={member.shotId} className="flex flex-wrap gap-2 rounded bg-black/20 p-2">
            <span>镜头 {member.seq} · {outcomes[member.outcome ?? '']} · {states[member.state ?? '']}</span>
            {member.waitReason && <span className="text-muted">{member.waitReason === 'RATE_LIMITED' ? '等待生成名额' : '生成服务暂缓接收，等待恢复'} · 将自动继续</span>}
            {member.reason && <span className="text-error-soft">{member.reason}</span>}
            {member.taskId && <span className="text-muted">任务 #{member.taskId}</span>}
            {member.dispatchBlocked && <><span className="text-error-soft">派发已暂停 · {member.dispatchBlocked}</span><button className="underline" disabled={workspaceReadOnly} onClick={() => void resume(member.taskId!)}>修复后恢复派发</button></>}
          </li>)}</ul>
        </div>}
      </div>
    </Modal>
  </div>;
}
