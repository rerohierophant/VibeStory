import { useId, useMemo, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useMatch, useNavigate, useParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { EpisodeNameInput } from '@/components/EpisodeNameInput';
import { Modal } from '@/components/Modal';
import { SidebarToggle } from '@/components/SidebarToggle';
import { useCreativeRecipes } from '@/features/CreativeRecipes';
import { episodeBreakdownProgress } from '@/lib/episodeProgress';
import { sidebarWidth, useSidebarCollapsed } from '@/lib/sidebar';
import { useProject, useProjectShots, useStore } from '@/lib/store';
import type { Episode } from '@/lib/types';

type EpisodePhase = 'empty' | 'draft' | 'making' | 'done' | 'planned';
type EpisodeStats = { confirmed: number; withImage: number; withVideo: number; total: number };
const emptyStats: EpisodeStats = { confirmed: 0, withImage: 0, withVideo: 0, total: 0 };

function episodesPhase(episode: Episode, shotStates: EpisodeStats): {
  phase: EpisodePhase;
  label: string;
} {
  const breakdown = episodeBreakdownProgress(episode.breakdown, shotStates.total);
  if (breakdown === 'running') return { phase: 'making', label: '拆解中' };
  if (breakdown === 'idle') {
    return episode.story.trim() ? { phase: 'planned', label: '待拆解' } : { phase: 'empty', label: '空白' };
  }
  if (shotStates.withVideo === shotStates.total) return { phase: 'done', label: '视频已生成' };
  if (shotStates.withVideo > 0) return { phase: 'making', label: `视频 ${shotStates.withVideo}/${shotStates.total}` };
  if (shotStates.withImage < shotStates.total) return { phase: 'planned', label: '待出图' };
  if (shotStates.confirmed < shotStates.total) return { phase: 'draft', label: '待确认分镜' };
  return { phase: 'making', label: '待生成视频' };
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
      aria-label={collapsed ? label : undefined}
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

function EpisodeActions({ episode, onRename, onDelete }: {
  episode: Episode;
  onRename: () => void;
  onDelete: () => void;
}) {
  const id = useId();
  const menu = useRef<HTMLDivElement>(null);

  return <>
    <button
      type="button"
      popoverTarget={id}
      aria-label={`第 ${episode.index} 集操作`}
      title="分集操作"
      className="mr-1.5 grid size-8 shrink-0 place-items-center rounded-card text-muted/75 transition hover:bg-hover/70 hover:text-fg"
    >
      <svg aria-hidden="true" viewBox="0 0 16 16" fill="currentColor" className="size-4">
        <circle cx="3" cy="8" r="1.25" />
        <circle cx="8" cy="8" r="1.25" />
        <circle cx="13" cy="8" r="1.25" />
      </svg>
    </button>
    <div
      id={id}
      ref={menu}
      popover="auto"
      className="inset-auto m-0 mt-1 w-32 rounded-card border border-stroke/50 bg-panel p-1 text-xs text-fg shadow-xl [position-area:bottom_span-left] [position-try-fallbacks:flip-block] [justify-self:end]"
    >
      <button type="button" className="block w-full rounded px-3 py-2 text-left hover:bg-hover/60"
        onClick={() => { menu.current?.hidePopover(); onRename(); }}>
        修改集名
      </button>
      <button type="button" className="block w-full rounded px-3 py-2 text-left text-error-soft hover:bg-error/10"
        onClick={() => { menu.current?.hidePopover(); onDelete(); }}>
        删除分集
      </button>
    </div>
  </>;
}

export function ProjectLayout() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const shots = useProjectShots(id);
  const { characters, scenes, createEpisode, updateEpisode, deleteEpisode, workspaceLoading, workspaceTeam, workspaceReadOnly, mode, user } = useStore();
  const recipeUserId = mode === 'api' && project && !project.teamId ? user?.id : undefined;
  const { data: recipes } = useCreativeRecipes(recipeUserId);
  const navigate = useNavigate();
  const activeEpisodeId = useMatch('/projects/:id/episodes/:episodeId/:tab?')?.params.episodeId;
  const [episodeFilter, setEpisodeFilter] = useState('');
  const [collapsed, toggleSidebar] = useSidebarCollapsed();
  const [renamingId, setRenamingId] = useState<string>();
  const [deleting, setDeleting] = useState<Episode>();
  const [deletePending, setDeletePending] = useState(false);

  const episodeStats = useMemo(() => {
    const map = new Map<string, EpisodeStats>();
    for (const s of shots) {
      const cur = map.get(s.episodeId) ?? { ...emptyStats };
      cur.total += 1;
      if (s.images.length > 0) cur.withImage += 1;
      if (s.selectedImageId && s.status === 'confirmed') cur.confirmed += 1;
      if (s.video.ready) cur.withVideo += 1;
      map.set(s.episodeId, cur);
    }
    return map;
  }, [shots]);

  if (!project) {
    if (workspaceLoading) return <div className="grid h-full place-items-center text-sm text-muted">正在读取项目…</div>;
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
  const canAddEpisode = mode === 'demo' || !!project.teamId;
  const episodes = [...project.episodes].sort((a, b) => a.index - b.index);
  const visibleEpisodes = episodes.filter(
    (e) =>
      !episodeFilter.trim() ||
      e.title.toLowerCase().includes(episodeFilter.trim().toLowerCase()) ||
      String(e.index).includes(episodeFilter.trim()),
  );

  const addEpisode = async () => {
    try {
      const newId = await createEpisode(project.id);
      navigate(`/projects/${project.id}/episodes/${newId}/story`);
    } catch { /* Store displays the scoped API error. */ }
  };

  const confirmDelete = async () => {
    if (!deleting || deletePending || workspaceReadOnly) return;
    setDeletePending(true);
    try {
      await deleteEpisode(deleting.id);
      setDeleting(undefined);
      if (activeEpisodeId === deleting.id) navigate(`/projects/${project.id}/episodes`, { replace: true });
    } catch { /* Store displays the scoped API error. */ }
    finally { setDeletePending(false); }
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
                <Link to={project.teamId ? `/teams/${project.teamId}` : '/'} className="text-xs text-muted transition hover:text-fg">
                  {project.teamId ? '‹ 团队' : '‹ 项目'}
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
              {workspaceTeam && <span className="mb-1 block">{workspaceTeam.name}{workspaceReadOnly && ' · 只读'}</span>}
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
            {recipeUserId && <NavItem
              to={`/projects/${project.id}/recipes`}
              label="创作配方"
              count={recipes?.length}
              collapsed={collapsed}
              icon={
                <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <rect x="3" y="2.5" width="10" height="11" rx="1.5" />
                  <path d="M5.5 6h5m-5 3.5h5" />
                </svg>
              }
            />}
          </nav>

          {collapsed && canAddEpisode && (
            <div className="mt-auto flex flex-col items-center pb-4">
              <button
                type="button"
                onClick={addEpisode}
                disabled={workspaceReadOnly}
                aria-label="新建一集"
                title="新建一集"
                className="flex h-8 w-8 items-center justify-center rounded text-muted transition hover:bg-hover/60 hover:text-fg"
              >
                ＋
              </button>
            </div>
          )}
          {!collapsed && (
            <>
              <div className="mx-1 mt-3 flex shrink-0 items-center justify-between border-t border-stroke/15 pb-2 pt-4">
                <span className="text-xs text-muted">
                  分集 <span className="text-fg/70">{episodes.length}</span>
                </span>
                {canAddEpisode && (
                  <button
                    type="button"
                    onClick={addEpisode}
                    disabled={workspaceReadOnly}
                    aria-label="新建一集"
                    title="新建一集"
                    className="flex h-6 w-6 items-center justify-center rounded text-muted transition hover:bg-hover/60 hover:text-fg"
                  >
                    ＋
                  </button>
                )}
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
                    {canAddEpisode
                      ? '还没有分集。新建一集，粘贴这一集的剧本，再拆解镜头。'
                      : '还没有分集。可在项目概览导入剧本或输入故事想法。'}
                  </p>
                ) : visibleEpisodes.length === 0 ? (
                  <p className="px-3 py-6 text-[11px] text-muted/70">没有匹配的分集</p>
                ) : (
                  <ul className="space-y-0.5">
                    {visibleEpisodes.map((ep) => {
                      const stats = episodeStats.get(ep.id) ?? emptyStats;
                      const { phase, label } = episodesPhase(ep, stats);
                      const active = activeEpisodeId === ep.id;
                      const renaming = !workspaceReadOnly && renamingId === ep.id;
                      const rowClass = 'flex min-w-0 flex-1 items-center gap-3 rounded-card py-2 pr-1 pl-2.5';
                      const content = <>
                        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-md text-[15px] ${
                          active ? 'bg-accent-soft text-accent-ink' : 'bg-white/[0.06] text-white/70'
                        }`}>
                          {ep.index}
                        </span>
                        <span className="min-w-0 flex-1">
                          {renaming ? <EpisodeNameInput
                            title={ep.title}
                            onSave={(title) => { updateEpisode(ep.id, { title }); setRenamingId(undefined); }}
                            onCancel={() => setRenamingId(undefined)}
                          /> : <span className="block truncate text-[13px]">{ep.title}</span>}
                          <span className="mt-0.5 flex items-center gap-1.5 text-[11px] text-muted">
                            <span className={`h-1.5 w-1.5 rounded-full ${dotTone[phase]}`} />
                            {label}
                          </span>
                        </span>
                      </>;
                      return (
                        <li key={ep.id} className={`flex items-center rounded-card transition ${active ? 'bg-hover/80' : 'hover:bg-hover/40'}`}>
                          {renaming ? <div className={rowClass}>{content}</div> : <NavLink
                            to={`/projects/${project.id}/episodes/${ep.id}/${stats.total > 0 ? 'storyboard' : 'story'}`}
                            title={`${ep.title} · ${label}`}
                            className={rowClass}
                          >
                            {content}
                          </NavLink>}
                          {!workspaceReadOnly && <EpisodeActions episode={ep}
                            onRename={() => setRenamingId(ep.id)} onDelete={() => setDeleting(ep)} />}
                        </li>
                      );
                    })}
                  </ul>
                )}
              </div>
            </>
          )}
        </aside>

        <SidebarToggle collapsed={collapsed} onToggle={toggleSidebar} />
      </div>

      <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <Outlet />
      </main>
      {deleting && <Modal open onClose={() => { if (!deletePending) setDeleting(undefined); }}
        width="max-w-md" labelledBy="episode-delete-title">
        <div className="p-6">
          <h2 id="episode-delete-title" className="text-lg font-medium">删除分集</h2>
          <p className="mt-3 text-sm leading-6 text-muted [overflow-wrap:anywhere]">
            删除第 {deleting.index} 集「{deleting.title}」会同时删除本集的剧本、镜头、分镜图和视频。删除后无法恢复。
          </p>
          <div className="mt-6 flex justify-end gap-3">
            <Button type="button" variant="ghost" disabled={deletePending} onClick={() => setDeleting(undefined)} autoFocus>取消</Button>
            <Button type="button" variant="danger" disabled={deletePending || workspaceReadOnly} onClick={confirmDelete}>
              {deletePending ? '正在删除…' : '确认删除'}
            </Button>
          </div>
        </div>
      </Modal>}
    </div>
  );
}
