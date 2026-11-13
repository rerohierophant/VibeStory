import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { GenerationMark } from '@/components/GenerationLoading';
import { Modal } from '@/components/Modal';
import { api, type Schema } from '@/lib/api';
import { missingReferenceMessage, resolveShotReferences } from '@/lib/imageSource';
import { useEpisodeShots, useStore } from '@/lib/store';
import { blockedGenerationStyle, ShotReferenceHint } from './ShotReferenceHint';

type Props = { episodeId: string; type: 'IMAGE' | 'VIDEO'; className?: string };
const outcomes: Record<string, string> = { accepted: '已接收', reused: '复用任务', blocked: '已阻塞' };
const states: Record<string, string> = { queued: '排队中', running: '生成中', succeeded: '已生成', failed: '失败', unknown: '结果未知', canceled: '已取消', blocked: '未提交' };

export function BatchGeneration(props: Props) {
  const { mode } = useStore();
  return mode === 'api' ? <ApiBatchGeneration key={`${props.episodeId}-${props.type}`} {...props} /> : null;
}

function ApiBatchGeneration({ episodeId, type, className = 'border-b border-stroke/15 px-6 py-3 text-xs' }: Props) {
  const { user, submitBatch, workspaceReadOnly, characters, scenes } = useStore();
  const shots = useEpisodeShots(episodeId);
  const cache = useQueryClient();
  const key = ['batches', user?.id, episodeId, type];
  const batches = useQuery({ queryKey: key, queryFn: ({ signal }) => api<Schema['GenerationBatch'][]>(`/api/episodes/${episodeId}/generation-batches?type=${type}`, 'GET', undefined, signal), refetchInterval: 3000 });
  const [open, setOpen] = useState(false);
  const [showReferenceHint, setShowReferenceHint] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [regenerate, setRegenerate] = useState(false);
  const [pendingCount, setPendingCount] = useState(0);
  const [submitError, setSubmitError] = useState<{ message: string; shotIds: string[]; regenerate: boolean }>();
  const [resumeError, setResumeError] = useState('');
  const [detailsOpen, setDetailsOpen] = useState(false);
  const request = useRef<{ fingerprint: string; key: string } | null>(null);
  const [result, setResult] = useState<Schema['GenerationBatch']>();
  const shown = batches.data?.find(batch => batch.id === result?.id) ?? result ?? batches.data?.[0];
  const progress = shown?.progress;
  const busy = pendingCount > 0;
  const detailsId = `batch-details-${type}`;
  const label = type === 'IMAGE' ? '分镜图' : '视频';
  const missingByShot = new Map(type === 'IMAGE' ? shots.map(shot => [shot.id,
    resolveShotReferences(shot, characters, scenes).filter(ref => !ref.version)] as const) : []);
  const eligible = shots.filter(shot => !missingByShot.get(shot.id)?.length);
  const missing = [...missingByShot.values()].flat();
  const allMissing = eligible.length === 0 && missing.length > 0;
  const selectedIds = selected.filter(id => eligible.some(shot => shot.id === id));
  const problem = (shot: (typeof shots)[number]) => {
    const missing = missingByShot.get(shot.id);
    if (missing?.length) return missingReferenceMessage(missing);
    if (type === 'VIDEO' && !shot.selectedImageId) return '缺少已选首帧';
    if (type === 'VIDEO' && shot.status !== 'confirmed') return '尚未确认';
    const state = type === 'IMAGE' ? shot.imageState : shot.video.state;
    if (state === 'unknown') return '结果未知，请先处理原任务';
    if (state === 'queued' || state === 'running') return '已有任务，提交时校验能否复用';
    return '可提交';
  };
  function show() {
    if (allMissing) {
      setShowReferenceHint(true);
      return;
    }
    setSelected(eligible.filter(s => type === 'IMAGE' ? s.images.length === 0 : s.status === 'confirmed' && s.selectedImageId && !s.video.ready)
      .slice(0, 200).map(s => s.id));
    setOpen(true);
  }
  async function submit(shotIds: string[], shouldRegenerate: boolean) {
    if (workspaceReadOnly || busy || !submitBatch || shotIds.length === 0) return;
    setOpen(false);
    setPendingCount(shotIds.length);
    setSubmitError(undefined);
    setResumeError('');
    const fingerprint = JSON.stringify([shotIds.slice().sort(), shouldRegenerate]);
    if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, key: crypto.randomUUID() };
    try {
      const batch = await submitBatch(episodeId, type, request.current.key, shotIds, shouldRegenerate);
      request.current = null;
      setResult(batch);
      setDetailsOpen(true);
      void cache.invalidateQueries({ queryKey: key });
    } catch (cause) {
      setSubmitError({ message: cause instanceof Error ? cause.message : '请稍后重试', shotIds, regenerate: shouldRegenerate });
    } finally { setPendingCount(0); }
  }
  async function resume(taskId: string) {
    setResumeError('');
    try { await api(`/api/tasks/${taskId}/dispatch/resume`, 'POST', {}); await cache.invalidateQueries({ queryKey: key }); }
    catch (cause) { setResumeError(cause instanceof Error ? cause.message : '恢复失败'); }
  }
  return <div className={className}>
    <div className="flex flex-wrap items-center gap-3">
      <Button size="sm" variant="outline" aria-haspopup={allMissing ? undefined : 'dialog'} disabled={workspaceReadOnly || busy}
        aria-disabled={allMissing || undefined} className={allMissing ? blockedGenerationStyle : undefined}
        aria-describedby={allMissing && showReferenceHint ? 'batch-reference-hint' : undefined} onClick={show}>本集批量生成{label}</Button>
      {batches.error && <span role="alert" className="text-error-soft">批次记录读取失败 <button className="underline" onClick={() => void batches.refetch()}>重试</button></span>}
    </div>
    {allMissing && showReferenceHint && <div className="mt-2">
      <ShotReferenceHint id="batch-reference-hint" projectId={shots[0].projectId} missing={missing} />
    </div>}
    <section aria-label={`${label}批次进度`}>
      {busy && <p role="status" className="mt-3 flex items-center gap-2 text-muted">
        <GenerationMark state="submitting" />
        正在提交 {pendingCount} 镜…
      </p>}
      {submitError && <div className="mt-3 flex flex-wrap items-center gap-3">
        <p role="alert" className="text-error-soft">提交失败 · {submitError.message}</p>
        <Button size="sm" variant="outline" disabled={workspaceReadOnly} onClick={() => void submit(submitError.shotIds, submitError.regenerate)}>重试提交</Button>
      </div>}
      {!busy && shown && <div className="mt-3 space-y-2 rounded-card border border-stroke/20 px-3 py-2.5">
        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
          <div role="status" className="space-y-1">
            <p className="font-medium">批次 #{shown.id} · {shown.state === 'complete' ? '已结束' : shown.state === 'paused' ? '派发暂停' : '执行中'}</p>
            <p className="text-muted">已生成 {progress?.succeeded ?? 0}/{progress?.total ?? 0} · 接收 {progress?.accepted ?? 0} · 复用 {progress?.reused ?? 0} · 阻塞 {progress?.blocked ?? 0} · 失败 {progress?.failed ?? 0} · 未知 {progress?.unknown ?? 0}{progress?.paused ? ` · 派发暂停 ${progress.paused}` : ''}</p>
          </div>
          <button type="button" aria-expanded={detailsOpen} aria-controls={detailsId} className="text-muted underline underline-offset-4 hover:text-fg" onClick={() => setDetailsOpen(value => !value)}>
            {detailsOpen ? '收起明细' : '查看明细'}
          </button>
        </div>
        <div id={detailsId} hidden={!detailsOpen}>
          <ul className="max-h-32 space-y-1 overflow-y-auto scroll-thin">{shown.members?.map(member => <li key={member.shotId} className="flex flex-wrap gap-2 rounded bg-black/20 p-2">
            <span className="inline-flex items-center gap-1">
              {(member.state === 'queued' || member.state === 'running') && <GenerationMark state={member.state} />}
              镜头 {member.seq} · {outcomes[member.outcome ?? '']} · {states[member.state ?? '']}
            </span>
            {member.waitReason && <span className="text-muted">{member.waitReason === 'RATE_LIMITED' ? '等待生成名额' : '生成服务暂缓接收，等待恢复'} · 将自动继续</span>}
            {member.reason && <span className="text-error-soft">{member.reason}</span>}
            {member.taskId && <span className="text-muted">任务 #{member.taskId}</span>}
            {member.dispatchBlocked && <><span className="text-error-soft">派发已暂停 · {member.dispatchBlocked}</span><button className="underline" disabled={workspaceReadOnly} onClick={() => void resume(member.taskId!)}>修复后恢复派发</button></>}
          </li>)}</ul>
          {resumeError && <p role="alert" className="mt-2 text-error-soft">{resumeError}</p>}
        </div>
      </div>}
    </section>
    <Modal open={open} onClose={() => setOpen(false)} labelledBy={`batch-title-${type}`}>
      <div className="space-y-4 p-6">
        <div className="flex items-center justify-between gap-3">
          <h2 id={`batch-title-${type}`} className="text-sm font-medium">本集批量生成{label}</h2>
          <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>关闭</Button>
        </div>
        {type === 'VIDEO' && <p className="text-xs leading-5 text-muted">镜头须已确认并选定首帧才能提交视频生成。</p>}
        {missing.length > 0 && <p className="text-xs leading-5 text-muted">缺少参考图的镜头暂不可选。</p>}
        <div className="flex flex-wrap gap-4 text-xs">
          <button type="button" onClick={() => setSelected(eligible.slice(0, 200).map(s => s.id))} className="underline">选择本集镜头（最多 200）</button>
          <button type="button" onClick={() => setSelected([])} className="underline">清空选择</button>
          <label className="flex items-center gap-2"><input type="checkbox" checked={regenerate} onChange={e => setRegenerate(e.target.checked)} />重新生成已选镜头</label>
        </div>
        <div className="max-h-60 overflow-y-auto rounded-card border border-stroke/20">
          {shots.map(shot => <label key={shot.id} className="flex items-center gap-3 border-b border-stroke/15 p-3 text-xs last:border-0">
            <input type="checkbox" aria-label={`选择镜头 ${shot.seq}`}
              disabled={!!missingByShot.get(shot.id)?.length || (!selectedIds.includes(shot.id) && selectedIds.length >= 200)}
              checked={selectedIds.includes(shot.id)} onChange={e => setSelected(e.target.checked ? [...selectedIds, shot.id] : selectedIds.filter(id => id !== shot.id))} />
            <span className="font-mono">{String(shot.seq).padStart(2, '0')}</span><span className="min-w-0 flex-1 truncate">{shot.description}</span><span className="min-w-0 max-w-[55%] break-words text-muted">{problem(shot)}</span>
          </label>)}
        </div>
        <Button size="sm" disabled={workspaceReadOnly || selectedIds.length === 0} onClick={() => void submit(selectedIds, regenerate)}>提交 {selectedIds.length} 镜</Button>
      </div>
    </Modal>
  </div>;
}
