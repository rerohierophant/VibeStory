import { Link, useNavigate, useParams } from 'react-router-dom';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { ProjectSetup } from '@/features/ProjectSetup';
import { ProjectStory } from '@/features/ProjectStory';
import { ProjectStyle } from '@/features/ProjectStyle';
import { episodeBreakdownProgress } from '@/lib/episodeProgress';
import { timeAgo } from '@/lib/id';
import { projectCoverSeed } from '@/lib/projectCover';
import { demoProjectStyleEditable } from '@/lib/projectStyle';
import { useProject, useProjectShots, useStore } from '@/lib/store';
import type { AssetVersion, Character, SceneAsset } from '@/lib/types';

function AssetLibraryPreview({ projectId, kind, assets }: {
  projectId: string;
  kind: 'character' | 'scene';
  assets: (Character | SceneAsset)[];
}) {
  const isCharacter = kind === 'character';
  const assetName = isCharacter ? '角色' : '场景';
  const title = `${assetName}库`;
  const headingId = `${kind}-library-preview`;

  return (
    <section aria-labelledby={headingId} className="min-w-0 rounded-panel border border-stroke/25 bg-panel/70">
      <header className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-4 pt-3 pb-2">
        <div className="flex items-center gap-2">
          <h2 id={headingId} className="text-sm font-semibold">{title}</h2>
          <span className="rounded bg-panel-2/70 px-1.5 py-0.5 text-[11px] leading-4 text-muted tabular-nums">
            {assets.length} 个
          </span>
        </div>
        <Link
          to={`/projects/${projectId}/${isCharacter ? 'characters' : 'scenes'}`}
          aria-label={`查看全部${assetName}`}
          className="inline-flex min-h-8 items-center gap-1.5 rounded px-1.5 text-xs text-muted transition hover:bg-hover/40 hover:text-fg focus-visible:outline-2 focus-visible:outline-accent-soft"
        >
          查看全部<span aria-hidden="true">→</span>
        </Link>
      </header>

      {assets.length === 0 ? (
        <p className="mx-4 mb-4 rounded-card border border-dashed border-stroke/30 bg-panel-2/20 px-3 py-4 text-xs leading-5 text-muted">
          {isCharacter ? '拆解剧本后，提取到的角色会显示在这里。' : '拆解剧本后，提取到的场景会显示在这里。'}
        </p>
      ) : (
        <ul className={isCharacter ? 'grid grid-cols-3 gap-2.5 px-4 pb-4' : 'divide-y divide-stroke/15 px-4 pb-2'}>
          {assets.slice(0, 3).map(asset => {
            const version = asset.versions.find(item => item.id === asset.selectedVersionId);
            const versionLabel = version?.label ?? '未选基准图';
            return (
              <li key={asset.id} className={isCharacter ? 'min-w-0' : 'flex min-w-0 items-center gap-3 py-2'}>
                <div className={isCharacter ? 'mb-2' : 'w-24 shrink-0'}>
                  <AssetPreviewThumbnail version={version} portrait={isCharacter} />
                </div>
                <div className="min-w-0">
                  <p title={asset.name} className="truncate text-xs leading-5 font-medium">{asset.name}</p>
                  <p title={versionLabel} className="truncate text-[11px] leading-4 text-muted">{versionLabel}</p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function AssetPreviewThumbnail({ version, portrait }: { version?: AssetVersion; portrait: boolean }) {
  return (
    <div
      aria-hidden="true"
      className={`overflow-hidden rounded-card border ${portrait ? 'aspect-[2/3]' : 'aspect-video'} ${
        version ? 'border-stroke/20 bg-bg' : 'grid place-items-center border-dashed border-stroke/35 bg-panel-2/35 text-muted/65'
      }`}
    >
      {version ? (
        <PlaceholderImage seed={version.seed} ratio={null} rounded="rounded-none" className="h-full w-full" fit={portrait ? 'contain' : 'cover'} />
      ) : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" className="size-5">
          {portrait ? <><circle cx="12" cy="8" r="3" /><path d="M5 20v-1a7 7 0 0 1 14 0v1" /></> : (
            <><rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8.5" cy="9" r="1.5" /><path d="m3 17 5-4 4 3 4-5 5 5" /></>
          )}
        </svg>
      )}
    </div>
  );
}

export function ProjectOverviewPage() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const shots = useProjectShots(id);
  const { characters, scenes, createEpisode, workspaceReadOnly, mode } = useStore();
  const navigate = useNavigate();

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
  const coverSeed = projectCoverSeed(project, shots, mode === 'demo');
  const charList = characters.filter((c) => c.projectId === id);
  const sceneList = scenes.filter((c) => c.projectId === id);
  const canAddEpisode = mode === 'demo' || !!project.teamId;
  const canPrepare = mode === 'api' && !project.teamId && !workspaceReadOnly;
  const needsSetup = canPrepare && episodes.length === 0;
  const styleEditable = !workspaceReadOnly && (mode === 'demo'
    ? demoProjectStyleEditable(id, { shots, characters: charList, scenes: sceneList })
    : project.styleEditable === true);
  const confirmed = shots.filter((s) => s.status === 'confirmed').length;

  const addEpisode = async () => {
    try {
      const newId = await createEpisode(id);
      navigate(`/projects/${id}/episodes/${newId}/story`);
    } catch { /* Store displays the scoped API error. */ }
  };

  return (
    <div className="@container flex min-h-0 flex-1 flex-col overflow-y-auto scroll-thin">
      <header className="border-b border-stroke/15 px-8 py-7">
        <div className="grid gap-x-6 gap-y-4 @min-[640px]:grid-cols-[minmax(0,1fr)_auto] @min-[640px]:items-center">
          <div className="flex min-w-0 items-center gap-4">
            <span className="w-[74px] shrink-0 overflow-hidden rounded-card border border-stroke/25">
              <PlaceholderImage seed={coverSeed ?? project.id} label={coverSeed ? undefined : '暂无封面'} alt="" ratio="4 / 3" rounded="rounded-none" />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="truncate text-xl font-semibold">{project.name}</h1>
                <ProjectStyle key={id} project={project} editable={styleEditable} />
              </div>
              <p className="mt-1.5 truncate text-xs text-muted">{project.style || '未填写风格'}</p>
              <p className="mt-1 text-[11px] text-muted/70">
                更新于 {timeAgo(project.updatedAt)} · 创建于 {new Date(project.createdAt).toLocaleDateString('zh-CN')}
              </p>
            </div>
          </div>
          {!needsSetup && (
            <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
              <ul aria-label="项目统计" className="flex items-center divide-x divide-stroke/30 text-xs text-muted tabular-nums">
                <li className="pr-4 whitespace-nowrap">
                  <span className="text-base font-semibold text-fg">{episodes.length}</span> 集
                </li>
                <li className="pl-4 whitespace-nowrap">
                  <span className="text-base font-semibold text-fg">{shots.length}</span> 镜头
                  {shots.length > 0 && <span className="ml-2 text-[11px]">已确认 {confirmed}</span>}
                </li>
              </ul>
              {canAddEpisode && <Button disabled={workspaceReadOnly} onClick={addEpisode}>新建一集</Button>}
            </div>
          )}
        </div>
      </header>

      {needsSetup && (
        <div className="space-y-5 px-8 py-7">
          <ProjectStory project={project} episodes={episodes} shots={shots} />
          <ProjectSetup key={id} projectId={id} hasEpisodes={false} />
        </div>
      )}

      {!needsSetup && <div className="grid gap-8 px-8 py-7 @min-[840px]:grid-cols-[minmax(0,1fr)_300px]">
        <div className="min-w-0 space-y-7">
          <ProjectStory project={project} episodes={episodes} shots={shots} />
          <section aria-labelledby="project-episodes-heading" className="min-w-0">
            <div className="flex items-end justify-between gap-3">
              <div>
                <h2 id="project-episodes-heading" className="text-sm font-medium">分集</h2>
                <p className="mt-1 text-xs text-muted">
                  每集有自己的剧本、镜头和视频。
                </p>
              </div>
              <Link to={`/projects/${id}/episodes`} className="text-xs text-muted hover:text-fg">
                查看全部 →
              </Link>
            </div>

            {episodes.length === 0 ? (
              <div className="mt-5">
                <EmptyState
                  title="还没有分集"
                  description={canAddEpisode ? '新建第一集，把这段故事贴进去，再让 AI 拆成镜头。' : undefined}
                  action={canAddEpisode && <Button disabled={workspaceReadOnly} onClick={addEpisode}>新建一集</Button>}
                />
              </div>
            ) : (
              <ul className="mt-4 overflow-hidden rounded-panel border border-stroke/20">
                {episodes.map((ep, i) => {
                  const epShots = shots.filter((s) => s.episodeId === ep.id);
                  const epConfirmed = epShots.filter((s) => s.status === 'confirmed').length;
                  const epVideo = epShots.filter((s) => s.video.ready).length;
                  const breakdown = episodeBreakdownProgress(ep.breakdown, epShots.length);
                  const state =
                    breakdown === 'running'
                      ? { text: '拆解中', tone: 'accent' as const }
                      : breakdown === 'done'
                        ? { text: `${epConfirmed}/${epShots.length} 已确认`, tone: 'neutral' as const }
                        : { text: ep.story.trim() ? '待拆解' : '空白', tone: 'muted' as const };

                  return (
                    <li key={ep.id} className={i > 0 ? 'border-t border-stroke/15' : ''}>
                      <Link
                        to={`/projects/${id}/episodes/${ep.id}/story`}
                        className="flex items-center gap-4 px-4 py-3.5 transition hover:bg-hover/30"
                      >
                        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-white/[0.06] text-sm">
                          {ep.index}
                        </span>
                        <div className="min-w-0 flex-1">
                          <div className="flex items-center gap-2">
                            <span className="truncate text-sm">{ep.title}</span>
                            <Badge tone={state.tone}>{state.text}</Badge>
                          </div>
                          <p className="mt-0.5 truncate text-[11px] text-muted">
                            {ep.summary || '还没有这一集的正文'}
                          </p>
                        </div>
                        <div className="hidden shrink-0 grid-cols-[2.5rem_3rem_4.5rem] gap-3 text-right text-[11px] whitespace-nowrap text-muted tabular-nums sm:grid">
                          <span>{epShots.length} 镜</span>
                          <span>{epVideo} 视频</span>
                          <span className="text-muted/60">{timeAgo(ep.updatedAt)}</span>
                        </div>
                      </Link>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>

        <aside className="grid min-w-0 items-start gap-4 self-start @min-[620px]:grid-cols-2 @min-[840px]:grid-cols-1">
          <AssetLibraryPreview projectId={id} kind="character" assets={charList} />
          <AssetLibraryPreview projectId={id} kind="scene" assets={sceneList} />
        </aside>
      </div>}
      {canPrepare && !needsSetup && (
        <div className="px-8 pb-7">
          <ProjectSetup key={id} projectId={id} hasEpisodes />
        </div>
      )}
    </div>
  );
}
