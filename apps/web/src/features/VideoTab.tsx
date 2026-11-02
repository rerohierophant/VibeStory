import { BatchGeneration } from './BatchGeneration';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, ShotStatusBadge, TaskBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { ImageGenerationSource } from '@/components/ImageGenerationSource';
import { formatDuration } from '@/lib/id';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';
import type { Episode, Shot } from '@/lib/types';

type Filter = 'all' | 'ready' | 'pending' | 'failed';

function VideoCard({ shot, onPreview }: { shot: Shot; onPreview: () => void }) {
  const { generateVideo } = useStore();
  const navigate = useNavigate();
  const image = shot.images.find((i) => i.id === (shot.video.ready ? shot.video.basedOn : shot.video.pendingBasedOn ?? shot.selectedImageId));
  const busy = shot.video.state === 'queued' || shot.video.state === 'running';
  const canSubmit = !!shot.selectedImageId && shot.status === 'confirmed';
  const blockedReason = !shot.selectedImageId
    ? '先去分镜页生成首帧'
    : shot.status !== 'confirmed'
      ? '先在分镜页确认这一镜'
      : '';
  const submitLabel = busy
    ? shot.video.state === 'queued'
      ? '排队中'
      : '生成中'
    : shot.video.state === 'unknown' ? shot.video.resumable ? '继续查询' : '确认后重试'
    : shot.video.state === 'failed'
      ? '重试'
      : shot.video.ready
        ? '重新生成'
        : '生成视频';

  return (
    <article className="flex flex-col overflow-hidden rounded-card border border-stroke/20 bg-panel">
      <div className="relative">
        {shot.video.ready ? (
          <button type="button" onClick={onPreview} className="group block w-full">
            <PlaceholderImage seed={image?.seed ?? shot.id} ratio="16 / 9" rounded="rounded-none" />
            <span className="absolute inset-0 flex items-center justify-center">
              <span className="flex h-11 w-11 items-center justify-center rounded-full bg-black/55 text-white/90 backdrop-blur transition group-hover:scale-110 group-hover:bg-black/70">
                ▶
              </span>
            </span>
            <span className="absolute bottom-2 right-2 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white/85">
              {formatDuration(shot.video.durationMs ?? shot.durationMs)}
            </span>
          </button>
        ) : image ? (
          <div className="relative">
            <PlaceholderImage seed={image.seed} ratio="16 / 9" rounded="rounded-none" />
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-1 bg-black/60 text-[11px] text-muted">
              {busy ? (
                <>
                  <span className="h-5 w-5 animate-spin rounded-full border-2 border-accent/40 border-t-accent" />
                  {shot.video.state === 'queued' ? '排队中' : '生成中'}
                </>
              ) : (
                <span>首帧已就绪，未生成视频</span>
              )}
            </div>
          </div>
        ) : (
          <div
            className="flex flex-col items-center justify-center gap-2 bg-black/25 text-[11px] text-muted"
            style={{ aspectRatio: '16 / 9' }}
          >
            <span>缺少分镜图</span>
            <button
              type="button"
              onClick={() => navigate(`/projects/${shot.projectId}/episodes/${shot.episodeId}/storyboard`)}
              className="underline hover:text-fg"
            >
              先去分镜页生成
            </button>
          </div>
        )}

        <div className="absolute left-2 top-2 flex items-center gap-1.5">
          <span className="rounded bg-black/60 px-1.5 py-0.5 font-mono text-[10px] text-white/85">
            {String(shot.seq).padStart(2, '0')}
          </span>
          {shot.video.state !== 'idle' && <TaskBadge state={shot.video.state} />}
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-2.5 p-3.5">
        <p className="line-clamp-2 text-xs leading-5 text-fg/85">{shot.description}</p>

        <div className="flex flex-wrap items-center gap-1.5 text-[11px] text-muted">
          <ShotStatusBadge status={shot.status} />
          {shot.video.ready ? (
            <Badge tone="success">已生成</Badge>
          ) : (
            <Badge tone="muted">未生成</Badge>
          )}
          {shot.video.attempts > 0 && <span>尝试 {shot.video.attempts} 次</span>}
        </div>

        {shot.video.error && (
          <p className="rounded bg-error/10 px-2 py-1.5 text-[10px] leading-4 text-error-soft">
            {shot.video.error}
          </p>
        )}

        <div className="mt-auto flex gap-2 border-t border-stroke/15 pt-2.5">
          <Button
            size="sm"
            className="flex-1"
            variant={canSubmit ? 'primary' : 'outline'}
            onClick={() => generateVideo(shot.id)}
            disabled={!canSubmit || busy}
            title={blockedReason}
          >
            {blockedReason && !busy ? blockedReason : submitLabel}
          </Button>
        </div>
      </div>
    </article>
  );
}

function VideoPreview({
  shot,
  episode,
  onClose,
}: {
  shot: Shot | null;
  episode?: Episode;
  onClose: () => void;
}) {
  if (!shot) return null;
  const image = shot.images.find((i) => i.id === shot.video.basedOn);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-8 backdrop-blur-sm"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="w-full max-w-5xl overflow-hidden rounded-panel border border-stroke/25 bg-panel">
        <div className="flex items-center justify-between border-b border-stroke/20 px-6 py-4">
          <div>
            <h2 className="text-sm font-medium">
              第 {episode?.index} 集 · {String(shot.seq).padStart(2, '0')} · {shot.sceneName}
            </h2>
            <p className="mt-0.5 text-[11px] text-muted">
              {episode?.title} · {shot.shotSize} · {shot.cameraMove} · {formatDuration(shot.video.durationMs ?? shot.durationMs)} · 尝试{' '}
              {shot.video.attempts} 次
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={onClose}>
              关闭
            </Button>
          </div>
        </div>

        <div className="grid gap-6 p-6 lg:grid-cols-[minmax(0,1fr)_300px]">
          <div className="space-y-3">
            <div className="relative">
              {shot.video.url ? <video controls autoPlay preload="metadata" src={shot.video.url} poster={image?.seed.startsWith('/api/') ? image.seed : undefined} className="aspect-video w-full rounded-card bg-black" aria-label="镜头视频" /> : <>
              <PlaceholderImage seed={image?.seed ?? shot.id} ratio="16 / 9" />
              <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/25">
                <span className="flex h-14 w-14 items-center justify-center rounded-full bg-black/55 text-xl text-white/90 backdrop-blur">
                  ▶
                </span>
                <span className="rounded bg-black/55 px-2 py-0.5 text-[10px] text-white/80">
                  占位播放器 · 接入供应商后替换为真实视频
                </span>
              </div>
              </>}
            </div>
            <div className="flex items-center gap-3 rounded-card border border-stroke/20 bg-black/25 px-4 py-3 text-[11px] text-muted">
              <span>首帧来源</span>
              <span className="flex-1 truncate">
                {image ? `${image.label} · ${new Date(image.createdAt).toLocaleString('zh-CN')}` : '未记录'}
              </span>
            </div>
          </div>

          <aside className="space-y-4 text-sm">
            <div>
              <p className="text-xs text-muted">画面描述</p>
              <p className="mt-1 leading-6">{shot.description}</p>
            </div>
            <div>
              <p className="text-xs text-muted">台词</p>
              <p className="mt-1 leading-6">{shot.dialogue || '—'}</p>
            </div>
            {image && <ImageGenerationSource image={image} />}
            <div className="rounded-card border border-stroke/20 bg-black/20 p-3 text-[11px] leading-5 text-muted">
              首帧来源记录生成这段视频时使用的分镜图，切换当前分镜图后仍可查看这段视频。
            </div>
          </aside>
        </div>
      </div>
    </div>
  );
}

export function VideoTab({ episodeId, projectId }: { episodeId: string; projectId: string }) {
  const shots = useEpisodeShots(episodeId);
  const project = useProject(projectId);
  const episode = project?.episodes.find((e) => e.id === episodeId);
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [preview, setPreview] = useState<Shot | null>(null);

  const eligible = shots.filter((s) => s.status === 'confirmed');

  const filtered = useMemo(() => {
    if (filter === 'ready') return eligible.filter((s) => s.video.ready);
    if (filter === 'pending') return eligible.filter((s) => !s.video.ready);
    if (filter === 'failed') return eligible.filter((s) => s.video.state === 'failed');
    return eligible;
  }, [eligible, filter]);

  const counts = {
    ready: eligible.filter((s) => s.video.ready).length,
    pending: eligible.filter((s) => !s.video.ready).length,
    failed: eligible.filter((s) => s.video.state === 'failed').length,
  };

  const totalMs = eligible.reduce((sum, s) => sum + (s.video.ready ? s.video.durationMs ?? s.durationMs : 0), 0);

  if (shots.length === 0) {
    return (
      <div className="p-8">
        <EmptyState
          title="还没有可以生成视频的镜头"
          description="先在「故事」里拆解，再到「分镜」里出图。"
          action={
            <Button onClick={() => navigate(`/projects/${projectId}/episodes/${episodeId}/story`)}>
              去拆解这一集
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stroke/15 px-6 py-3">
        <div className="flex items-center gap-1.5">
          {(
            [
              ['all', `全部 ${eligible.length}`],
              ['ready', `已生成 ${counts.ready}`],
              ['pending', `未生成 ${counts.pending}`],
              ['failed', `失败 ${counts.failed}`],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setFilter(key)}
              className={`rounded-full px-3 py-1 text-xs transition ${
                filter === key ? 'bg-accent/20 text-fg' : 'text-muted hover:bg-hover/40'
              }`}
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex items-center gap-3 text-[11px] text-muted">
          <span>
            已出片 {counts.ready}/{eligible.length} · 总时长{' '}
            {(totalMs / 1000).toFixed(1)}s
          </span>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => navigate(`/projects/${projectId}/episodes/${episodeId}/storyboard`)}
          >
            ← 回分镜改图
          </Button>
        </div>
      </div>

      <BatchGeneration episodeId={episodeId} type="VIDEO" />

      <div className="flex-1 overflow-y-auto scroll-thin p-6">
        {filtered.length === 0 ? (
          <EmptyState
            title="这里没有视频"
            description={eligible.length === 0 ? '还没有确认的镜头。先到分镜页确认镜头。' : '切换筛选条件试试。'}
          />
        ) : (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
            {filtered.map((s) => (
              <VideoCard key={s.id} shot={s} onPreview={() => setPreview(s)} />
            ))}
          </div>
        )}
      </div>

      <VideoPreview shot={preview ? shots.find(s => s.id === preview.id) ?? null : null} episode={episode} onClose={() => setPreview(null)} />
    </div>
  );
}
