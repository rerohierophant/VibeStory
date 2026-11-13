import { useId, useRef, useState, type FormEvent, type MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { Badge, TaskBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { GenerationMark } from '@/components/GenerationLoading';
import { ImageGenerationSource } from '@/components/ImageGenerationSource';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { formatDuration } from '@/lib/id';
import { useEpisodeShots, useProject, useProjectShots, useStore } from '@/lib/store';
import type { VideoRegeneration } from '@/lib/storeContext';
import { cameraMoves, shotSizes, type CameraMove, type Shot, type ShotImage, type ShotSize, type VideoCandidate } from '@/lib/types';
import { BatchGeneration } from './BatchGeneration';
import { VideoLoading } from './VideoLoading';

type Filter = 'all' | 'ready' | 'pending' | 'failed';

const shotNumber = (shot: Shot) => String(shot.seq).padStart(2, '0');
const hasVideo = (shot: Shot) => shot.video.ready && !!shot.video.url;
const firstPreview = (shots: Shot[]) => shots.find(hasVideo) ?? shots[0];
const isImageUrl = (frame: string | undefined): frame is string =>
  !!frame && (frame.startsWith('/api/') || frame.startsWith('data:image/'));

const defaultFrameId = (shot: Shot) => shot.video.ready
  ? shot.video.basedOn : shot.video.pendingBasedOn ?? shot.selectedImageId;

function getFrame(shot: Shot, mode: string | undefined, sourceId?: string) {
  const image = shot.images.find((candidate) => candidate.id === sourceId)
    ?? shot.deletedImages?.find((candidate) => candidate.id === sourceId);
  const historicalFrame = !image && sourceId && mode === 'api'
    ? `/api/shot-images/${encodeURIComponent(sourceId)}/image`
    : undefined;
  return { image, historicalFrame, frame: image?.seed ?? historicalFrame };
}

const frameLabel = (sourceId?: string, image?: ShotImage) =>
  image?.label ?? (sourceId ? `历史首帧 #${sourceId}` : '未记录');

function PlayIcon({ className }: { className: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 16 16" fill="currentColor" className={className}>
      <path d="M5 3.5v9l7-4.5-7-4.5Z" />
    </svg>
  );
}

function FrameIcon({ className }: { className: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={className}>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3 16 5-4 4 3 3-2 6 4" />
      <circle cx="15.5" cy="9.5" r="1" fill="currentColor" stroke="none" />
    </svg>
  );
}

function videoSettings(shot: Shot, prompt?: string) {
  const previous = prompt?.trim();
  // Both the default MiniMax prompt and the editor use this trailing pair of settings.
  const [, description, size, move] = previous?.match(/^(.*)；景别：([^；]+)；运镜：([^；]+)$/s) ?? [];
  const shotSize = shotSizes.find(value => value === size);
  const cameraMove = cameraMoves.find(value => value === move);
  if (shotSize && cameraMove) return { description: description.trim(), shotSize, cameraMove };
  return { description: previous || shot.description, shotSize: shot.shotSize, cameraMove: shot.cameraMove };
}

function VideoRegenerationForm({ id, shot, firstFrame, previousPrompt, outputDurationMs, disabled, onClose, onSubmitted }: {
  id: string;
  shot: Shot;
  firstFrame: ShotImage;
  previousPrompt?: string;
  outputDurationMs?: number;
  disabled: boolean;
  onClose: () => void;
  onSubmitted: () => void;
}) {
  const { generateVideo } = useStore();
  const title = useId();
  const [draft, setDraft] = useState(() => videoSettings(shot, previousPrompt));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<VideoRegeneration | null>(null);
  const submittingRef = useRef(false);
  const description = draft.description.trim();
  const prompt = `${description}；景别：${draft.shotSize}；运镜：${draft.cameraMove}`;
  const tooLong = Array.from(prompt).length > 2000;
  const frameChanged = shot.selectedImageId !== firstFrame.id;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || submittingRef.current || frameChanged || !description || tooLong) return;
    if (request.current?.prompt !== prompt) {
      request.current = { prompt, expectedFirstFrameId: firstFrame.id, regenerateNonce: crypto.randomUUID() };
    }
    submittingRef.current = true;
    setSubmitting(true);
    setError('');
    try {
      await generateVideo(shot.id, request.current);
      onSubmitted();
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '提交失败，请重试');
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  return (
    <form id={id} aria-labelledby={title} onSubmit={submit}
      className="mt-4 space-y-4 rounded-panel border border-stroke/30 bg-panel p-4">
      <header className="flex items-center gap-3">
        <PlaceholderImage seed={firstFrame.seed} alt="本次生成首帧" ratio="16 / 9" fit="contain" className="w-24 shrink-0" />
        <div>
          <h4 id={title} className="text-sm font-medium">重新生成视频</h4>
          <p className="mt-1 text-xs text-muted">本次首帧 · {firstFrame.label}
            {outputDurationMs && ` · 生成规格 ${formatDuration(outputDurationMs)}`}
          </p>
        </div>
      </header>
      <fieldset disabled={disabled || submitting} className="min-w-0 space-y-3">
        <label className="block text-xs text-muted">
          视频提示词
          <textarea rows={3} autoFocus value={draft.description}
            onChange={event => setDraft({ ...draft, description: event.target.value })}
            className="field mt-1.5 resize-y text-sm disabled:opacity-60" />
        </label>
        <div className="grid grid-cols-2 gap-3">
          <label className="block text-xs text-muted">
            景别
            <select className="field mt-1.5 text-sm disabled:opacity-60" value={draft.shotSize}
              onChange={event => setDraft({ ...draft, shotSize: event.target.value as ShotSize })}>
              {shotSizes.map(size => <option key={size}>{size}</option>)}
            </select>
          </label>
          <label className="block text-xs text-muted">
            运镜
            <select className="field mt-1.5 text-sm disabled:opacity-60" value={draft.cameraMove}
              onChange={event => setDraft({ ...draft, cameraMove: event.target.value as CameraMove })}>
              {cameraMoves.map(move => <option key={move}>{move}</option>)}
            </select>
          </label>
        </div>
      </fieldset>
      {tooLong && <p role="alert" className="text-xs text-error-soft">视频提示词加上景别和运镜不能超过 2000 字符。</p>}
      {frameChanged && <p role="alert" className="text-xs text-error-soft">选中的首帧已变更，请取消后重新确认。</p>}
      {error && <p role="alert" className="text-xs text-error-soft">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="ghost" disabled={submitting} onClick={onClose}>取消</Button>
        <Button type="submit" size="sm" disabled={disabled || submitting || frameChanged || !description || tooLong}>
          {submitting ? '提交中…' : '确认生成'}
        </Button>
      </div>
    </form>
  );
}

function VideoCandidates({ shot, candidates, activeId, onSelect }: {
  shot: Shot;
  candidates: VideoCandidate[];
  activeId?: string;
  onSelect: (id?: string) => void;
}) {
  const { mode } = useStore();
  const titleId = useId();

  return (
    <section aria-labelledby={titleId} className="mt-3 rounded-panel border border-stroke/30 bg-panel px-3 py-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <h4 id={titleId} className="text-sm font-medium">视频候选</h4>
        <span className="text-xs text-muted">共 {candidates.length} 版</span>
      </div>
      <ul className="mt-2 grid max-h-48 grid-cols-[repeat(auto-fill,minmax(7.5rem,1fr))] gap-1.5 overflow-y-auto scroll-thin">
        {candidates.map((candidate, index) => {
          const active = candidate.id === activeId;
          const { image, frame } = getFrame(shot, mode, candidate.basedOn);
          const label = `第 ${candidates.length - index} 版`;
          return (
            <li key={candidate.id} className="min-w-0">
              <button type="button" aria-pressed={active} onClick={() => onSelect(index === 0 ? undefined : candidate.id)}
                className={`flex w-full items-center gap-1.5 rounded-card border p-1.5 text-left transition focus-visible:outline-offset-[-2px] ${
                  active ? 'border-accent/45 bg-accent/15' : 'border-stroke/25 hover:bg-hover/45'
                }`}>
                {frame
                  ? <PlaceholderImage seed={frame} alt="" fit="contain" className="w-10 shrink-0" />
                  : <span className="grid aspect-video w-10 shrink-0 place-items-center rounded-card bg-black/40">
                      <PlayIcon className="size-4 text-muted" />
                    </span>}
                <span className="min-w-0 flex-1">
                  <span className={`flex items-center justify-between gap-1 text-xs font-medium ${active ? 'text-accent-soft' : 'text-fg'}`}>
                    <span>{label}</span>
                    {candidate.durationMs != null && <span className="tabular-nums">{formatDuration(candidate.durationMs)}</span>}
                  </span>
                  <span className="mt-1 block truncate text-[11px] text-muted" title={frameLabel(candidate.basedOn, image)}>
                    首帧 · {frameLabel(candidate.basedOn, image)}
                  </span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function ShotPreview({ shot, storyboardUrl }: { shot: Shot; storyboardUrl: string }) {
  const { generateVideo, mode, runtime, workspaceReadOnly } = useStore();
  const regenerationId = useId();
  const [reviewedFrame, setReviewedFrame] = useState<ShotImage>();
  const [previewId, setPreviewId] = useState<string>();
  const submitButton = useRef<HTMLButtonElement>(null);
  const { state, ready, attempts, error, resumable } = shot.video;
  const candidates = shot.video.candidates ?? [];
  const candidate = candidates.find(item => item.id === previewId) ?? candidates[0];
  const { url, durationMs, prompt } = candidate ?? shot.video;
  const sourceId = candidate ? candidate.basedOn : defaultFrameId(shot);
  const busy = state === 'queued' || state === 'running';
  const playable = ready && !!url;
  const outputDurationMs = runtime?.video?.durationMs;
  const { image, historicalFrame, frame } = getFrame(shot, mode, sourceId);
  const poster = isImageUrl(frame) ? frame : undefined;
  const pendingFrame = ready
    ? getFrame(shot, mode, shot.video.pendingBasedOn).frame
    : frame;
  const blockedReason = !shot.selectedImageId
    ? '先去分镜页生成首帧'
    : shot.status !== 'confirmed' ? '先在分镜页确认这一镜' : '';
  const submitLabel = state === 'queued' ? '排队中'
    : state === 'running' ? '生成中'
    : state === 'unknown' ? resumable ? '继续查询' : '确认后重试'
    : state === 'failed' ? '重试'
    : ready ? '重新生成' : '生成视频';
  const selectedFrame = shot.images.find(candidate => candidate.id === shot.selectedImageId);
  const regenerating = ready && state === 'idle';

  function submit(event: MouseEvent<HTMLButtonElement>) {
    if (regenerating) {
      submitButton.current = event.currentTarget;
      if (selectedFrame && !reviewedFrame) setReviewedFrame(selectedFrame);
      return;
    }
    void Promise.resolve(generateVideo(shot.id)).catch(() => {});
  }

  return (
    <article aria-labelledby="video-shot-title" className="min-w-0">
      <div className="relative aspect-video overflow-hidden rounded-panel border border-white/10 bg-black">
        {playable ? (
          <video
            key={url}
            controls
            playsInline
            preload="none"
            src={url}
            poster={poster}
            className="block h-full w-full object-contain"
            aria-label="镜头视频"
          />
        ) : busy ? null : frame ? (
          <>
            <PlaceholderImage seed={frame} alt="分镜首帧" ratio={null} rounded="rounded-none" fit="contain" className="h-full" />
            <span className="absolute bottom-3 left-3 rounded-card bg-black/75 px-2.5 py-1.5 text-xs text-white">
              {ready ? '暂无可播放视频' : '首帧预览'}
            </span>
          </>
        ) : (
          <div className="grid h-full place-content-center justify-items-center gap-2 px-3 text-center text-sm text-muted">
            <FrameIcon className="mb-1 h-8 w-8 text-muted/60" />
            <span>{ready ? '暂无可播放视频' : '缺少分镜图'}</span>
            {!ready && <Link to={storyboardUrl} className="text-xs text-accent-soft underline underline-offset-4">先去分镜页生成</Link>}
          </div>
        )}
        <div role="status">
          {busy && <VideoLoading state={state} hasPreviousVideo={playable} firstFrame={pendingFrame} />}
        </div>
      </div>

      {candidates.length > 1 && <VideoCandidates shot={shot} candidates={candidates} activeId={candidate?.id} onSelect={setPreviewId} />}

      <div className="px-0.5 pt-4">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5">
              <h3 id="video-shot-title" className="text-lg font-semibold">第 {shotNumber(shot)} 镜</h3>
              {ready && <Badge tone="success">已生成</Badge>}
              {state !== 'idle' ? <TaskBadge state={state} /> : !ready && <Badge tone="muted">未生成</Badge>}
            </div>
            <p className="mt-1 flex flex-wrap gap-x-2 gap-y-1 text-xs text-muted">
              <span>{shot.sceneName} · {shot.shotSize} · {shot.cameraMove}</span>
              <span>计划 {formatDuration(shot.durationMs)}</span>
              {ready && durationMs != null && <span>成片 {formatDuration(durationMs)}</span>}
              {!ready && state === 'idle' && outputDurationMs && <span>生成规格 {formatDuration(outputDurationMs)}</span>}
              {attempts > 0 && <span>尝试 {attempts} 次</span>}
            </p>
          </div>
          <Button
            size="sm"
            variant={!blockedReason && !ready ? 'primary' : 'outline'}
            onClick={submit}
            aria-expanded={regenerating ? !!reviewedFrame : undefined}
            aria-controls={reviewedFrame ? regenerationId : undefined}
            disabled={workspaceReadOnly || !!blockedReason || busy || (regenerating && !selectedFrame)}
            title={blockedReason || undefined}
          >
            {blockedReason && !busy ? blockedReason : submitLabel}
          </Button>
        </div>
        <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-6 text-fg/90">{shot.description}</p>
        {reviewedFrame && <VideoRegenerationForm id={regenerationId} shot={shot} firstFrame={reviewedFrame} previousPrompt={prompt} outputDurationMs={outputDurationMs}
          disabled={!!workspaceReadOnly || !!blockedReason || busy}
          onSubmitted={() => setPreviewId(undefined)}
          onClose={() => {
            setReviewedFrame(undefined);
            submitButton.current?.focus();
          }} />}

        {error && <p className="mt-3 rounded-card bg-error/10 px-3 py-2 text-xs leading-5 text-error-soft">{error}</p>}

        {(ready || shot.dialogue) && (
          <details className="mt-4 border-t border-stroke/20 pt-3 text-xs leading-5 text-muted">
            <summary className="w-fit cursor-pointer hover:text-fg">{ready ? '镜头详情与首帧来源' : '镜头详情'}</summary>
            <div className="mt-2 space-y-2 rounded-card bg-panel p-3">
              {shot.dialogue && <p className="whitespace-pre-wrap break-words">台词 · {shot.dialogue}</p>}
              {ready && (
                <p>
                  首帧来源 · {frameLabel(sourceId, image)}{image && ` · ${new Date(image.createdAt).toLocaleString('zh-CN')}`}
                </p>
              )}
              {historicalFrame && <PlaceholderImage seed={historicalFrame} label="历史首帧" fit="contain" className="max-w-md" />}
              {ready && image && <ImageGenerationSource image={image} />}
              {candidate?.createdAt != null && <p>视频生成时间 · {new Date(candidate.createdAt).toLocaleString('zh-CN')}</p>}
              {ready && prompt && <p className="whitespace-pre-wrap break-words">视频提示词 · {prompt}</p>}
            </div>
          </details>
        )}
      </div>
    </article>
  );
}

function ShotListItem({ shot, active, onSelect }: { shot: Shot; active: boolean; onSelect: () => void }) {
  const { mode } = useStore();
  const { frame } = getFrame(shot, mode, defaultFrameId(shot));
  const { ready, state, durationMs } = shot.video;
  const playable = hasVideo(shot);
  const candidateCount = shot.video.candidates?.length ?? 0;
  const status = state === 'running' ? '生成中' : state === 'queued' ? '排队中'
    : state === 'failed' ? '失败' : state === 'unknown' ? '结果未知'
    : ready ? '已生成' : '未生成';

  return (
    <li>
      <button
        type="button"
        aria-current={active ? 'true' : undefined}
        onClick={onSelect}
        className={`flex w-full min-w-0 items-center gap-3 rounded-card border p-2 text-left transition focus-visible:outline-offset-[-2px] ${
          active ? 'border-accent/45 bg-accent/15' : 'border-transparent hover:bg-hover/45'
        }`}
      >
        <span className="relative grid aspect-video w-[5.5rem] shrink-0 place-items-center overflow-hidden rounded-card bg-black/40">
          {isImageUrl(frame) ? (
            <img src={frame} alt="" loading="lazy" className="absolute inset-0 h-full w-full object-cover" />
          ) : playable ? (
            <PlayIcon className="h-5 w-5 text-muted" />
          ) : (
            <FrameIcon className="h-5 w-5 text-muted/55" />
          )}
          {playable && (
            <span className="absolute bottom-1 right-1 rounded bg-black/80 px-1 text-[10px] tabular-nums text-white">
              {formatDuration(durationMs ?? shot.durationMs)}
            </span>
          )}
        </span>
        <span className="min-w-0 flex-1">
          <span className={`block text-sm font-medium ${active ? 'text-accent-soft' : 'text-fg'}`}>第 {shotNumber(shot)} 镜</span>
          <span className="mt-0.5 block truncate text-xs text-muted" title={shot.description}>{shot.description}</span>
          <span className={`mt-0.5 flex items-center gap-1.5 text-[11px] ${state === 'failed' ? 'text-error-soft'
            : state === 'queued' || state === 'running' ? 'font-medium text-accent-soft' : 'text-muted'}`}>
            {(state === 'queued' || state === 'running') && <GenerationMark state={state} />}
            {status}
            {candidateCount > 1 && <span>· {candidateCount} 版视频</span>}
          </span>
        </span>
        {active && <PlayIcon className="h-4 w-4 shrink-0 text-accent-soft" />}
      </button>
    </li>
  );
}

function EpisodePicker({ projectId, episodeId }: { projectId: string; episodeId: string }) {
  const project = useProject(projectId);
  const shots = useProjectShots(projectId);
  const episodes = [...(project?.episodes ?? [])].sort((a, b) => a.index - b.index);
  if (!episodes.length) return null;

  const stats = new Map<string, { confirmed: number; ready: number }>();
  for (const shot of shots) {
    const count = stats.get(shot.episodeId) ?? { confirmed: 0, ready: 0 };
    if (shot.status === 'confirmed') count.confirmed += 1;
    if (shot.video.ready) count.ready += 1;
    stats.set(shot.episodeId, count);
  }

  return (
    <nav aria-labelledby="video-episodes-title" className="overflow-hidden rounded-panel border border-stroke/30 bg-panel">
      <div className="flex items-baseline justify-between gap-2 border-b border-stroke/20 px-4 py-3">
        <h3 id="video-episodes-title" className="text-sm font-semibold">选集</h3>
        <span className="text-xs text-muted">共 {episodes.length} 集</span>
      </div>
      <ul className="max-h-60 space-y-1 overflow-y-auto overscroll-y-contain p-2 scroll-thin">
        {episodes.map((episode) => {
          const count = stats.get(episode.id);
          const active = episode.id === episodeId;
          const status = episode.breakdown === 'running' ? '拆解中'
            : count?.ready ? `${count.ready} 镜已出片`
            : count?.confirmed ? '未生成' : count ? '待确认' : '待拆解';
          return (
            <li key={episode.id}>
              <Link
                to={`/projects/${projectId}/episodes/${episode.id}/${count ? 'video' : 'story'}`}
                aria-current={active ? 'page' : undefined}
                className={`flex min-w-0 items-center gap-2.5 rounded-card px-2 py-1.5 transition focus-visible:outline-offset-[-2px] ${
                  active ? 'bg-accent/15 text-accent-soft' : 'text-fg hover:bg-hover/45'
                }`}
              >
                <span className={`grid h-7 min-w-7 shrink-0 place-items-center rounded-md px-1 text-xs tabular-nums ${active ? 'bg-accent-soft text-accent-ink' : 'bg-white/[0.06] text-muted'}`}>
                  {String(episode.index).padStart(2, '0')}
                </span>
                <span className="min-w-0 flex-1 truncate text-sm" title={episode.title}>{episode.title}</span>
                <span className="shrink-0 text-[11px] text-muted">
                  {status}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

export function VideoTab({ episodeId, projectId }: { episodeId: string; projectId: string }) {
  const shots = useEpisodeShots(episodeId);
  const eligible = shots.filter((shot) => shot.status === 'confirmed' || shot.video.ready || shot.video.state !== 'idle');
  const [filter, setFilter] = useState<Filter>('all');
  const [selectedId, setSelectedId] = useState(() => firstPreview(eligible)?.id);
  const ready = eligible.filter((shot) => shot.video.ready);
  const pending = eligible.filter((shot) => !shot.video.ready);
  const failed = eligible.filter((shot) => shot.video.state === 'failed');
  const byFilter = { all: eligible, ready, pending, failed };
  const filtered = byFilter[filter];
  const selected = eligible.find((shot) => shot.id === selectedId) ?? firstPreview(filtered);
  const totalMs = ready.reduce((sum, shot) => sum + (shot.video.durationMs ?? shot.durationMs), 0);
  const storyboardUrl = `/projects/${projectId}/episodes/${episodeId}/storyboard`;

  function changeFilter(next: Filter) {
    if (next === filter) return;
    const nextShots = byFilter[next];
    const nextShot = nextShots.find((shot) => shot.id === selected?.id) ?? firstPreview(nextShots);
    setSelectedId(nextShot?.id);
    setFilter(next);
  }

  if (!shots.length) {
    return (
      <div className="p-8">
        <EmptyState
          title="还没有可以生成视频的镜头"
          description="先在「故事」里拆解，再到「分镜」里出图。"
          action={<Link className="text-sm text-accent-soft underline underline-offset-4" to={`/projects/${projectId}/episodes/${episodeId}/story`}>去拆解这一集</Link>}
        />
      </div>
    );
  }

  return (
    <div className="@container/video min-h-0 flex-1 overflow-y-auto scroll-thin">
      <div className="mx-auto max-w-[96rem] px-4 pt-4 pb-8 @min-[40rem]/video:px-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <h2 className="text-base font-semibold">视频预览</h2>
          <Link to={storyboardUrl} className="rounded-card px-1 py-1.5 text-xs text-muted transition hover:text-fg">← 回分镜改图</Link>
        </div>
        <BatchGeneration episodeId={episodeId} type="VIDEO" className="mb-4 text-xs" />

        <div className="grid min-w-0 items-start gap-4 @min-[40rem]/video:grid-cols-[minmax(0,1fr)_clamp(16rem,29%,21rem)] @min-[40rem]/video:gap-5">
          {selected ? (
            <ShotPreview key={selected.id} shot={selected} storyboardUrl={storyboardUrl} />
          ) : (
            <EmptyState
              title={eligible.length ? '没有符合筛选的镜头' : '这里没有视频'}
              description={eligible.length ? '试试查看其他状态的镜头。' : '还没有确认的镜头。先到分镜页确认镜头。'}
              action={eligible.length && filter !== 'all' ? (
                <Button size="sm" variant="outline" onClick={() => changeFilter('all')}>查看全部镜头</Button>
              ) : undefined}
            />
          )}

          <div className="min-w-0 space-y-4">
            <section aria-labelledby="video-shots-title" className="overflow-hidden rounded-panel border border-stroke/30 bg-panel">
              <div className="border-b border-stroke/20 px-3.5 pt-3.5 pb-3">
                <h3 id="video-shots-title" className="text-sm font-semibold">本集镜头</h3>
                <p className="mt-1 text-xs tabular-nums text-muted">
                  已出片 {ready.length}/{eligible.length} · 总时长 {(totalMs / 1000).toFixed(1)}s
                </p>
                <div role="group" aria-label="筛选镜头" className="mt-3 flex flex-wrap gap-1">
                  {([
                    ['all', `全部 ${eligible.length}`],
                    ['ready', `已生成 ${ready.length}`],
                    ['pending', `未生成 ${pending.length}`],
                    ['failed', `失败 ${failed.length}`],
                  ] as const).map(([key, label]) => (
                    <button
                      key={key}
                      type="button"
                      aria-pressed={filter === key}
                      onClick={() => changeFilter(key)}
                      className={`rounded-full px-2 py-1 text-[11px] whitespace-nowrap transition ${
                        filter === key ? 'bg-accent/20 text-accent-soft' : 'text-muted hover:bg-hover/50 hover:text-fg'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>
              {filtered.length ? (
                <ol className="max-h-[22rem] space-y-1 overflow-y-auto overscroll-y-contain p-2 scroll-thin">
                  {filtered.map((shot) => (
                    <ShotListItem key={shot.id} shot={shot} active={selected?.id === shot.id} onSelect={() => setSelectedId(shot.id)} />
                  ))}
                </ol>
              ) : (
                <p className="p-4 text-xs text-muted">{eligible.length ? '该状态下没有镜头' : '暂无已确认镜头'}</p>
              )}
            </section>

            <EpisodePicker projectId={projectId} episodeId={episodeId} />
          </div>
        </div>
      </div>
    </div>
  );
}
