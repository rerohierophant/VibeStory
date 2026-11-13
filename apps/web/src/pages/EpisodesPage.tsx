import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EpisodeNameInput } from '@/components/EpisodeNameInput';
import { EmptyState } from '@/components/EmptyState';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { episodeBreakdownProgress } from '@/lib/episodeProgress';
import { timeAgo } from '@/lib/id';
import { useProject, useProjectShots, useStore } from '@/lib/store';

/**
 * 集封面：1 张铺满，2 张左右分栏，3 张左大右二。
 * 只用这一集已选首帧的 seed，不引入额外状态。
 */
function EpisodePreview({ previews }: { previews: { key: string; seed: string }[] }) {
  if (previews.length === 0) {
    return (
      <div
        className="flex flex-col items-center justify-center gap-1.5 bg-black/25 text-[11px] text-muted"
        style={{ aspectRatio: '16 / 9' }}
      >
        <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.2" className="h-5 w-5 opacity-60">
          <rect x="2.5" y="4" width="15" height="12" rx="1.5" />
          <path d="m2.5 12.5 4-3.5 3.5 3 3-2.5 4.5 4" />
        </svg>
        还没有分镜图
      </div>
    );
  }

  const cell = (p: { key: string; seed: string }, className = '') => (
    <PlaceholderImage
      key={p.key}
      seed={p.seed}
      ratio={null}
      rounded="rounded-none"
      className={`h-full w-full ${className}`}
    />
  );

  return (
    <div className="relative" style={{ aspectRatio: '16 / 9' }}>
      {previews.length === 1 ? (
        <div className="absolute inset-0">{cell(previews[0])}</div>
      ) : previews.length === 2 ? (
        <div className="absolute inset-0 grid grid-cols-2 gap-px bg-stroke/25">
          {previews.map((p) => cell(p))}
        </div>
      ) : (
        <div className="absolute inset-0 grid grid-cols-3 grid-rows-2 gap-px bg-stroke/25">
          {cell(previews[0], 'col-span-2 row-span-2')}
          {cell(previews[1])}
          {cell(previews[2])}
        </div>
      )}
    </div>
  );
}

export function EpisodesPage() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const shots = useProjectShots(id);
  const { createEpisode, updateEpisode, workspaceReadOnly, mode } = useStore();
  const navigate = useNavigate();
  const [editingId, setEditingId] = useState<string>();

  if (!project) {
    return (
      <div className="flex min-h-full items-center justify-center p-10">
        <EmptyState
          title="项目不存在"
          description="它可能已被删除，或者链接不正确。"
          action={<Button onClick={() => navigate('/')}>返回项目列表</Button>}
        />
      </div>
    );
  }

  const episodes = [...project.episodes].sort((a, b) => a.index - b.index);
  const canAddEpisode = mode === 'demo' || !!project.teamId;

  const addEpisode = async () => {
    try {
    const newId = await createEpisode(id);
    navigate(`/projects/${id}/episodes/${newId}/story`);
    } catch { /* Store displays the scoped API error. */ }
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="flex flex-wrap items-end justify-between gap-3 border-b border-stroke/15 px-8 py-6">
        <div>
          <h1 className="text-lg font-medium">分集</h1>
          <p className="mt-1 text-xs text-muted">
            {episodes.length} 集 · {shots.length} 个镜头。每集各自拆解、各自出图。
          </p>
        </div>
        {canAddEpisode && <Button disabled={workspaceReadOnly} onClick={addEpisode}>新建一集</Button>}
      </header>

      <div className="flex-1 overflow-y-auto scroll-thin p-8">
        {episodes.length === 0 ? (
          <EmptyState
            title="还没有分集"
            description={canAddEpisode
              ? '先建第一集，粘贴这一集的剧本。'
              : '导入整稿或输入故事想法，审阅并确认分集草案。'}
            action={canAddEpisode
              ? <Button disabled={workspaceReadOnly} onClick={addEpisode}>新建一集</Button>
              : <Link to={`/projects/${id}`} className="text-sm text-accent-soft underline underline-offset-4">去准备故事</Link>}
          />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 2xl:grid-cols-3">
            {episodes.map((ep) => {
              const epShots = shots.filter((s) => s.episodeId === ep.id);
              const breakdown = episodeBreakdownProgress(ep.breakdown, epShots.length);
              const confirmed = epShots.filter((s) => s.status === 'confirmed').length;
              const withVideo = epShots.filter((s) => s.video.ready).length;
              const pct = epShots.length ? Math.round((confirmed / epShots.length) * 100) : 0;
              // 每集拿前三个已选首帧拼一张预览，按镜头顺序，没有图就留空
              const previews = epShots
                .slice()
                .sort((a, b) => a.seq - b.seq)
                .flatMap((s) => {
                  const img = s.images.find((i) => i.id === s.selectedImageId) ?? s.images[0];
                  return img ? [{ key: s.id, seed: img.seed }] : [];
                })
                .slice(0, 3);

              return (
                <article
                  key={ep.id}
                  className="group flex flex-col overflow-hidden rounded-panel border border-stroke/20 bg-panel/50 transition hover:border-stroke/40"
                >
                  <div className="relative">
                    <EpisodePreview previews={previews} />
                    <button
                      type="button"
                      disabled={workspaceReadOnly}
                      onClick={() => setEditingId(ep.id)}
                      title="修改集名"
                      aria-label={`修改第 ${ep.index} 集集名`}
                      className="absolute right-2.5 top-2.5 flex h-7 w-7 items-center justify-center rounded-md bg-black/55 text-white/80 backdrop-blur transition hover:bg-black/75 hover:text-white"
                    >
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" className="h-3.5 w-3.5">
                        <path d="M11.3 2.2a1.4 1.4 0 0 1 2 2L5 12.5l-2.7.7.7-2.7 8.3-8.3Z" />
                      </svg>
                    </button>
                  </div>

                  <div className="flex flex-1 flex-col p-5">
                    <div className="flex min-w-0 items-center gap-3">
                      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-white/[0.06] text-sm">
                        {ep.index}
                      </span>
                      <div className="min-w-0 flex-1">
                        {!workspaceReadOnly && editingId === ep.id ? <EpisodeNameInput
                          title={ep.title}
                          onSave={(title) => { updateEpisode(ep.id, { title }); setEditingId(undefined); }}
                          onCancel={() => setEditingId(undefined)}
                        /> : <h2 className="truncate text-sm font-medium">{ep.title}</h2>}
                        <p className="mt-0.5 text-[11px] text-muted">更新于 {timeAgo(ep.updatedAt)}</p>
                      </div>
                    </div>

                    <p className="mt-3 line-clamp-2 min-h-10 text-xs leading-5 text-muted">
                      {ep.summary || ep.story.slice(0, 80) || '还没有正文'}
                    </p>

                    <div className="mt-3 flex flex-wrap items-center gap-1.5">
                      <Badge tone={breakdown === 'running' ? 'accent' : breakdown === 'done' ? 'success' : 'muted'}>
                        {breakdown === 'running' ? '拆解中' : breakdown === 'done' ? '已拆解' : '未拆解'}
                      </Badge>
                      <Badge tone="neutral">{epShots.length} 镜</Badge>
                      <Badge tone="neutral">{confirmed} 已确认</Badge>
                      <Badge tone="accent">{withVideo} 视频</Badge>
                    </div>

                    <div className="mt-4">
                      <div className="flex items-center justify-between text-[10px] text-muted">
                        <span>已确认</span>
                        <span>{pct}%</span>
                      </div>
                      <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-white/[0.07]">
                        <div className="h-full rounded-full bg-accent" style={{ width: `${pct}%` }} />
                      </div>
                    </div>

                    <div className="mt-auto pt-5">
                      <Button
                        size="sm"
                        variant="outline"
                        className="w-full"
                        onClick={() => navigate(`/projects/${id}/episodes/${ep.id}/story`)}
                      >
                        进入工作区
                      </Button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </div>

    </div>
  );
}
