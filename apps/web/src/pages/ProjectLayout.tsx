import { useMemo, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router-dom';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { SidebarToggle } from '@/components/SidebarToggle';
import { sidebarWidth, useSidebarCollapsed } from '@/lib/sidebar';
import { useProject, useProjectShots, useStore } from '@/lib/store';
import type { Episode } from '@/lib/types';

type EpisodePhase = 'empty' | 'draft' | 'making' | 'done' | 'planned';

function episodesPhase(episode: Episode, shotStates: { confirmed: number; total: number }): {
  phase: EpisodePhase;
  label: string;
} {
  if (episode.breakdown === 'running') return { phase: 'making', label: '拆解中' };
  // 有镜头就一定拆过：以镜头为准，避免「未拆解」和「4 镜」同时出现
  if (shotStates.total === 0) {
    return episode.story.trim() ? { phase: 'planned', label: '待拆解' } : { phase: 'empty', label: '空白' };
  }
  // 只有「待确认 / 已确认」一个状态：全部确认即完成，不再分「制作中 / 已定稿」
  if (shotStates.confirmed === shotStates.total) return { phase: 'done', label: '已确认' };
  if (shotStates.confirmed === 0) return { phase: 'draft', label: '待确认' };
  return { phase: 'making', label: `确认中 ${shotStates.confirmed}/${shotStates.total}` };
}

const dotTone: Record<EpisodePhase, string> = {
  empty: 'bg-white/20',
  planned: 'bg-white/30',
  draft: 'bg-warning/80',
  making: 'bg-accent',
  done: 'bg-success/90',
};

function NavItem({
  to,
  end,
  icon,
  label,
  count,
  collapsed,
}: {
  to: string;
  end?: boolean;
  icon: React.ReactNode;
  label: string;
  count?: number;
  collapsed: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      title={collapsed ? label : undefined}
      className={({ isActive }) =>
        `flex items-center rounded-card text-[13px] transition ${
          collapsed ? 'justify-center px-0 py-2.5' : 'gap-3 px-3 py-2.5'
        } ${isActive ? 'bg-hover/70 text-fg' : 'text-muted hover:bg-hover/40 hover:text-fg'}`
      }
    >
      <span className="flex w-4 shrink-0 justify-center text-current/80">{icon}</span>
      {!collapsed && (
        <>
          <span className="flex-1">{label}</span>
          {count !== undefined && <span className="text-xs text-muted/70">{count}</span>}
        </>
      )}
    </NavLink>
  );
}

export function ProjectLayout() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const shots = useProjectShots(id);
  const { characters, scenes, createEpisode } = useStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [episodeFilter, setEpisodeFilter] = useState('');
  const [collapsed, toggleSidebar] = useSidebarCollapsed();

  const episodeStats = useMemo(() => {
    const map = new Map<string, { confirmed: number; total: number }>();
    for (const s of shots) {
      const cur = map.get(s.episodeId) ?? { confirmed: 0, total: 0 };
      cur.total += 1;
      if (s.status === 'confirmed') cur.confirmed += 1;
      map.set(s.episodeId, cur);
    }
    return map;
  }, [shots]);

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

  const charCount = characters.filter((c) => c.projectId === project.id).length;
  const sceneCount = scenes.filter((c) => c.projectId === project.id).length;
  const episodes = [...project.episodes].sort((a, b) => a.index - b.index);
  const visibleEpisodes = episodes.filter(
    (e) =>
      !episodeFilter.trim() ||
      e.title.toLowerCase().includes(episodeFilter.trim().toLowerCase()) ||
      String(e.index).includes(episodeFilter.trim()),
  );

  const allConfirmed = episodes.length > 0 && shots.length > 0 && shots.every((s) => s.status === 'confirmed');

  const addEpisode = () => {
    const newId = createEpisode(project.id);
    navigate(`/projects/${project.id}/episodes/${newId}/story`);
  };

  return (
    <div className="flex h-full overflow-hidden">
      {/* 折叠把手挂在 aside 外侧的定位容器上，否则会被 overflow-hidden 裁掉 */}
      <div className="relative flex shrink-0">
        <aside
          className={`flex flex-col overflow-hidden border-r border-stroke/15 bg-panel/50 pt-5 transition-[width] duration-200 ease-out ${
            collapsed ? `${sidebarWidth.collapsed} px-2` : `${sidebarWidth.expanded} px-4`
          }`}
        >
          <div className={`flex shrink-0 items-center gap-2 ${collapsed ? 'justify-center' : 'pb-4'}`}>
            {collapsed ? (
              <Link
                to={`/projects/${project.id}`}
                title={project.name}
                className="flex h-9 w-9 items-center justify-center rounded-md bg-white/[0.06] text-sm font-medium text-white/80"
              >
                {project.name.slice(0, 1)}
              </Link>
            ) : (
              <>
                <Link to="/" className="text-xs text-muted transition hover:text-fg">
                  ‹ 项目
                </Link>
                <span className="h-4 w-px bg-stroke/25" />
                <NavLink
                  to={`/projects/${project.id}`}
                  end
                  className="min-w-0 flex-1 truncate text-sm font-medium"
                  title={project.name}
                >
                  {project.name}
                </NavLink>
              </>
            )}
          </div>
          {!collapsed && (
            <p className="shrink-0 px-1 pb-4 text-[11px] text-muted">
              {project.style || '未填写风格'}
              {episodes.length > 0 && ` · ${episodes.length} 集`}
            </p>
          )}

          <nav className="shrink-0 space-y-0.5 px-1">
            <NavItem
              to={`/projects/${project.id}`}
              end
              label="项目概览"
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <rect x="2" y="2" width="5" height="5" rx="1" />
                  <rect x="9" y="2" width="5" height="5" rx="1" />
                  <rect x="2" y="9" width="5" height="5" rx="1" />
                  <rect x="9" y="9" width="5" height="5" rx="1" />
                </svg>
              }
            />
            <NavItem
              to={`/projects/${project.id}/episodes`}
              label="分集"
              count={episodes.length}
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <path d="M2.5 3.5h5c.6 0 1 .4 1 1v9H3.5c-.6 0-1-.4-1-1v-9Z" />
                  <path d="M13.5 3.5h-5c-.6 0-1 .4-1 1v9h5c.6 0 1-.4 1-1v-9Z" />
                </svg>
              }
            />
            <NavItem
              to={`/projects/${project.id}/characters`}
              label="角色库"
              count={charCount}
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <circle cx="6" cy="5.5" r="2.2" />
                  <path d="M2 13c0-2.2 1.8-3.5 4-3.5s4 1.3 4 3.5" />
                  <path d="M11 4.2a1.9 1.9 0 0 1 0 3.6M12 9.8c1.2.4 2 1.5 2 3.2" />
                </svg>
              }
            />
            <NavItem
              to={`/projects/${project.id}/scenes`}
              label="场景库"
              count={sceneCount}
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <path d="M2 13V4.5l5-2v10.5" />
                  <path d="M7 13V2.5l7 2v8.5z" />
                  <path d="M1.5 13h13" />
                </svg>
              }
            />
          </nav>

          {collapsed ? (
            <div className="mt-auto flex flex-col items-center pb-4">
              <button
                type="button"
                onClick={addEpisode}
                title="新建一集"
                className="flex h-8 w-8 items-center justify-center rounded text-muted transition hover:bg-hover/60 hover:text-fg"
              >
                ＋
              </button>
            </div>
          ) : (
            <>
              <div className="mx-1 mt-3 flex shrink-0 items-center justify-between border-t border-stroke/15 pb-2 pt-4">
                <span className="text-xs text-muted">
                  分集 <span className="text-fg/70">{episodes.length}</span>
                </span>
                <button
                  type="button"
                  onClick={addEpisode}
                  title="新建一集"
                  className="flex h-6 w-6 items-center justify-center rounded text-muted transition hover:bg-hover/60 hover:text-fg"
                >
                  ＋
                </button>
              </div>

              <div className="shrink-0 px-1 pb-2">
                <input
                  className="h-8 w-full rounded-card border border-stroke/25 bg-black/20 px-3 text-xs outline-none placeholder:text-muted/60 focus:border-accent/50"
                  placeholder="搜索集名或序号"
                  value={episodeFilter}
                  onChange={(e) => setEpisodeFilter(e.target.value)}
                />
              </div>

              <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain scroll-thin pb-4">
                {episodes.length === 0 ? (
                  <p className="px-3 py-6 text-[11px] leading-5 text-muted/70">
                    还没有分集。新建一集，粘贴这一集的剧本，再拆解镜头。
                  </p>
                ) : visibleEpisodes.length === 0 ? (
                  <p className="px-3 py-6 text-[11px] text-muted/70">没有匹配的分集</p>
                ) : (
                  <ul className="space-y-0.5">
                    {visibleEpisodes.map((ep) => {
                      const stats = episodeStats.get(ep.id) ?? { confirmed: 0, total: 0 };
                      const { phase, label } = episodesPhase(ep, stats);
                      const active = location.pathname.includes(`/episodes/${ep.id}`);
                      return (
                        <li key={ep.id}>
                          <NavLink
                            to={`/projects/${project.id}/episodes/${ep.id}/story`}
                            title={`${ep.title} · ${label}`}
                            className={`flex items-center gap-3 rounded-card px-2.5 py-2 transition ${
                              active ? 'bg-hover/80' : 'hover:bg-hover/40'
                            }`}
                          >
                            <span
                              className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-md text-[15px] ${
                                active ? 'bg-accent-soft text-accent-ink' : 'bg-white/[0.06] text-white/70'
                              }`}
                            >
                              {ep.index}
                            </span>
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-[13px]">{ep.title}</span>
                              <span className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted">
                                <span className={`h-1.5 w-1.5 rounded-full ${dotTone[phase]}`} />
                                {label}
                              </span>
                            </span>
                          </NavLink>
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>

              {allConfirmed && (
                <div className="shrink-0 border-t border-stroke/15 px-1 py-4">
                  <Badge tone="success">全部镜头已确认</Badge>
                </div>
              )}
            </>
          )}
        </aside>

        <SidebarToggle collapsed={collapsed} onToggle={toggleSidebar} />
      </div>

      <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <Outlet />
      </main>
    </div>
  );
}
