import { NavLink, Navigate, useNavigate, useParams } from 'react-router-dom';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { StoryTab } from '@/features/StoryTab';
import { StoryboardTab } from '@/features/StoryboardTab';
import { VideoTab } from '@/features/VideoTab';
import { episodeBreakdownProgress } from '@/lib/episodeProgress';
import { useEpisodeShots, useProject } from '@/lib/store';

const tabs = [
  { key: 'story', label: '故事' },
  { key: 'storyboard', label: '分镜' },
  { key: 'video', label: '视频' },
] as const;

export function EpisodeWorkspacePage() {
  const { id = '', episodeId = '', tab = 'story' } = useParams();
  const project = useProject(id);
  const shots = useEpisodeShots(episodeId);
  const navigate = useNavigate();

  const episode = project?.episodes.find((e) => e.id === episodeId);

  if (!project || !episode) {
    return (
      <div className="flex min-h-full items-center justify-center p-10">
        <EmptyState
          title="这一集不存在"
          description="它可能已被删除，或者链接不正确。"
          action={
            <Button onClick={() => navigate(`/projects/${project ? project.id : ''}/episodes`)}>
              返回分集列表
            </Button>
          }
        />
      </div>
    );
  }

  const activeTab = tabs.some((t) => t.key === tab) ? tab : 'story';
  const breakdown = episodeBreakdownProgress(episode.breakdown, shots.length);
  const hasShots = shots.length > 0;
  const breakdownLabel = breakdown === 'running' ? '拆解中' : breakdown === 'done' ? '已拆解' : '未拆解';
  const withFrame = shots.filter((s) => s.selectedImageId);
  const confirmed = withFrame.filter((s) => s.status === 'confirmed').length;
  const withVideo = shots.filter((s) => s.video.ready).length;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="border-b border-stroke/15 bg-panel/40">
        <div className="flex flex-wrap items-center gap-4 px-8 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-white/[0.06] text-base">
              {episode.index}
            </span>
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <h1 className="truncate text-sm font-medium">{episode.title}</h1>
                <Badge tone={breakdown === 'running' ? 'accent' : breakdown === 'done' ? 'success' : 'muted'}>{breakdownLabel}</Badge>
              </div>
              <p className="mt-0.5 truncate text-[11px] text-muted">
                第 {episode.index} 集 · {shots.length} 镜 · {withVideo} 镜已出片
              </p>
            </div>
          </div>

          {withFrame.length > 0 && <div className="ml-auto flex items-center gap-2 text-[11px] text-muted">
            <span>
              已确认 {confirmed}/{withFrame.length}
            </span>
          </div>}
        </div>

        <nav className="flex items-center gap-1 px-6">
          {tabs.map((t) => {
            const disabled = t.key !== 'story' && !hasShots;
            const to = `/projects/${project.id}/episodes/${episode.id}/${t.key}`;
            if (disabled) {
              return (
                <span
                  key={t.key}
                  title="先在这一集拆解镜头"
                  className="cursor-not-allowed px-3 py-2.5 text-xs text-muted/60"
                >
                  {t.label}
                </span>
              );
            }
            return (
              <NavLink
                key={t.key}
                to={to}
                className={({ isActive }) =>
                  `relative px-3 py-2.5 text-xs transition ${
                    isActive
                      ? 'text-fg before:absolute before:inset-x-2 before:bottom-0 before:h-[2px] before:rounded-full before:bg-accent'
                      : 'text-muted hover:text-fg'
                  }`
                }
              >
                {t.label}
              </NavLink>
            );
          })}

          {hasShots && activeTab !== 'story' && (
            <div className="ml-auto flex items-center gap-2 pb-2 pr-2">
              <button
                type="button"
                onClick={() => navigate(`/projects/${project.id}/episodes`)}
                className="text-[11px] text-muted transition hover:text-fg"
              >
                其他分集
              </button>
            </div>
          )}
        </nav>
      </header>

      <main className="flex min-h-0 flex-1 flex-col">
        {activeTab === 'story' && <StoryTab episodeId={episode.id} />}
        {activeTab === 'storyboard' &&
          (hasShots ? (
            <StoryboardTab key={episode.id} episodeId={episode.id} projectId={project.id} />
          ) : (
            <Navigate to={`/projects/${project.id}/episodes/${episode.id}/story`} replace />
          ))}
        {activeTab === 'video' &&
          (hasShots ? (
            <VideoTab key={episode.id} episodeId={episode.id} projectId={project.id} />
          ) : (
            <Navigate to={`/projects/${project.id}/episodes/${episode.id}/story`} replace />
          ))}
      </main>
    </div>
  );
}
