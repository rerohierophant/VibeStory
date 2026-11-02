import { Link, useNavigate, useParams } from 'react-router-dom';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { timeAgo } from '@/lib/id';
import { useProject, useProjectShots, useStore } from '@/lib/store';
import { visualLabel } from '@/lib/visualPresets';

export function ProjectOverviewPage() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const shots = useProjectShots(id);
  const { characters, scenes, createEpisode, customStyles } = useStore();
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
  const charList = characters.filter((c) => c.projectId === id);
  const sceneList = scenes.filter((c) => c.projectId === id);
  const confirmed = shots.filter((s) => s.status === 'confirmed').length;
  const withVideo = shots.filter((s) => s.video.ready).length;

  const addEpisode = async () => {
    try {
    const newId = await createEpisode(id);
    navigate(`/projects/${id}/episodes/${newId}/story`);
    } catch { /* Store displays the scoped API error. */ }
  };

  const stats = [
    { label: '分集', value: episodes.length, hint: '集' },
    { label: '镜头', value: shots.length, hint: `${confirmed} 已确认` },
    { label: '分镜图', value: shots.filter((s) => s.selectedImageId).length, hint: '已选定' },
    { label: '视频', value: withVideo, hint: `${confirmed} 镜已确认` },
  ];

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto scroll-thin">
      <header className="border-b border-stroke/15 px-8 py-7">
        <div className="flex flex-wrap items-start justify-between gap-5">
          <div className="flex min-w-0 items-center gap-4">
            <span className="w-[74px] shrink-0 overflow-hidden rounded-card border border-stroke/25">
              <PlaceholderImage seed={project.id} label="暂无封面" ratio="4 / 3" rounded="rounded-none" />
            </span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="truncate text-xl font-semibold">{project.name}</h1>
                <Badge tone="muted">{(project.visualPreset?.label ?? visualLabel(project.visualType, customStyles))}</Badge>
              </div>
              <p className="mt-1.5 truncate text-xs text-muted">{project.style || '未填写风格'}</p>
              <p className="mt-1 text-[11px] text-muted/70">
                更新于 {timeAgo(project.updatedAt)} · 创建于 {new Date(project.createdAt).toLocaleDateString('zh-CN')}
              </p>
            </div>
          </div>
          <Button onClick={addEpisode}>新建一集</Button>
        </div>

        <div className="mt-6 grid max-w-3xl grid-cols-2 gap-3 sm:grid-cols-4">
          {stats.map((s) => (
            <div key={s.label} className="rounded-card border border-stroke/20 bg-panel/50 px-4 py-3">
              <p className="text-[11px] text-muted">{s.label}</p>
              <p className="mt-1 flex items-baseline gap-1.5">
                <span className="text-xl font-medium">{s.value}</span>
                <span className="text-[11px] text-muted/80">{s.hint}</span>
              </p>
            </div>
          ))}
        </div>
      </header>

      <div className="grid gap-8 px-8 py-7 xl:grid-cols-[minmax(0,1fr)_300px]">
        <section className="min-w-0">
          <div className="flex items-end justify-between gap-3">
            <div>
              <h2 className="text-sm font-medium">分集</h2>
              <p className="mt-1 text-xs text-muted">
                每一集独立上传剧本、独立拆解。镜头、分镜图和视频都挂在集下面。
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
                description="新建第一集，把这段故事贴进去，再让 AI 拆成镜头。"
                action={<Button onClick={addEpisode}>新建一集</Button>}
              />
            </div>
          ) : (
            <ul className="mt-4 overflow-hidden rounded-panel border border-stroke/20">
              {episodes.map((ep, i) => {
                const epShots = shots.filter((s) => s.episodeId === ep.id);
                const epConfirmed = epShots.filter((s) => s.status === 'confirmed').length;
                const epVideo = epShots.filter((s) => s.video.ready).length;
                const state =
                  ep.breakdown === 'running'
                    ? { text: '拆解中', tone: 'accent' as const }
                    : ep.breakdown !== 'done'
                      ? { text: ep.story.trim() ? '待拆解' : '空白', tone: 'muted' as const }
                      : epShots.length === 0
                        ? { text: '无镜头', tone: 'warning' as const }
                        : { text: `${epConfirmed}/${epShots.length} 已确认`, tone: 'neutral' as const };

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
                      <div className="hidden shrink-0 items-center gap-4 text-[11px] text-muted sm:flex">
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

        <aside className="space-y-4">
          <section className="rounded-panel border border-stroke/20 bg-panel/50 p-5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium">角色库</h3>
              <Link to={`/projects/${id}/characters`} className="text-[11px] text-muted hover:text-fg">
                {charList.length} 个 →
              </Link>
            </div>
            {charList.length === 0 ? (
              <p className="mt-3 text-[11px] leading-5 text-muted">拆解后从台本自动抽取角色，在这里定妆。</p>
            ) : (
              <ul className="mt-3 space-y-2">
                {charList.slice(0, 4).map((c) => {
                  const v = c.versions.find((x) => x.id === c.selectedVersionId);
                  return (
                    <li key={c.id} className="flex items-center gap-2.5">
                      <span className="w-7 shrink-0 overflow-hidden rounded">
                        <PlaceholderImage seed={v?.seed ?? c.name} ratio="1 / 1" rounded="rounded-none" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-xs">{c.name}</span>
                        <span className="block truncate text-[10px] text-muted">{v?.label ?? '未定妆'}</span>
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>

          <section className="rounded-panel border border-stroke/20 bg-panel/50 p-5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-medium">场景库</h3>
              <Link to={`/projects/${id}/scenes`} className="text-[11px] text-muted hover:text-fg">
                {sceneList.length} 个 →
              </Link>
            </div>
            {sceneList.length === 0 ? (
              <p className="mt-3 text-[11px] leading-5 text-muted">场景会在拆解时抽取，同样需要一张基准图。</p>
            ) : (
              <div className="mt-3 flex flex-wrap gap-1.5">
                {sceneList.map((s) => (
                  <Badge key={s.id} tone="neutral">
                    {s.name}
                  </Badge>
                ))}
              </div>
            )}
          </section>

          <section className="rounded-card border border-stroke/20 bg-black/20 p-4 text-[11px] leading-5 text-muted">
            人物与场景是可跨集复用的资产。分镜图保留生成人物、场景的版本来源，切换基准版本只用于后续生成。
          </section>
        </aside>
      </div>
    </div>
  );
}
