import { useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { CreativeRecipes, useCreativeRecipes } from '@/features/CreativeRecipes';
import { useStore } from '@/lib/store';
import type { AssetKind } from '@/components/AssetRow';
import type { Character, SceneAsset } from '@/lib/types';

type AssetTab = AssetKind | 'recipes';

function AssetCard({ asset, kind, projectName, shots, episodes }: {
  asset: Character | SceneAsset;
  kind: AssetKind;
  projectName: string;
  shots: number;
  episodes: number[];
}) {
  const selected = asset.versions.find((version) => version.id === asset.selectedVersionId);
  const frame = kind === 'character' ? 'aspect-[4/5]' : 'aspect-video';

  return (
    <article className="flex min-w-0 flex-col rounded-panel border border-stroke/30 bg-panel transition-colors hover:border-stroke/60">
      <div className={`relative overflow-hidden rounded-t-panel border-b border-stroke/20 bg-canvas ${frame}`}>
        {selected ? (
          <PlaceholderImage seed={selected.seed} alt={`${asset.name}的基准图`} ratio={null} rounded="rounded-none"
            className="h-full w-full" fit={kind === 'character' ? 'contain' : 'cover'} />
        ) : (
          <div className="grid h-full place-content-center justify-items-center gap-2 bg-panel-2/30 p-3 text-center text-xs text-muted">
            <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"
              strokeLinecap="round" strokeLinejoin="round" className="size-7 text-muted/60">
              {kind === 'character' ? <><circle cx="12" cy="8" r="3" /><path d="M5 20v-1a7 7 0 0 1 14 0v1" /></> : <>
                <rect x="3" y="4" width="18" height="16" rx="2" /><circle cx="8.5" cy="9" r="1.5" /><path d="m3 17 5-4 4 3 4-5 5 5" />
              </>}
            </svg>
            暂无基准图
          </div>
        )}
      </div>
      <div className="flex-1 px-4 pt-3.5 pb-4">
        <h2 className="text-base font-semibold [overflow-wrap:anywhere]">{asset.name}</h2>
        {asset.description && (
          <p className="mt-1.5 line-clamp-2 text-xs leading-5 text-muted [overflow-wrap:anywhere]" title={asset.description}>
            {asset.description}
          </p>
        )}
        <p className="mt-3 flex flex-wrap gap-x-2 gap-y-1 text-[11px] leading-4 text-muted/85 tabular-nums">
          <span>{asset.versions.length} 个版本</span>
          <span aria-hidden="true">·</span>
          <span>{shots} 个镜头</span>
        </p>
        {episodes.length > 0 && (
          <p className="mt-1 truncate text-[11px] leading-4 text-muted/85 tabular-nums" title={`第 ${episodes.join('、')} 集`}>
            第 {episodes.join('、')} 集
          </p>
        )}
      </div>
      <Link to={`/projects/${asset.projectId}`}
        className="group flex min-h-11 flex-wrap items-center justify-between gap-x-3 gap-y-1 rounded-b-panel border-t border-stroke/25 bg-panel-2/20 px-4 py-2 text-xs transition hover:bg-hover/45 focus-visible:outline-offset-[-2px]">
        <span className="flex min-w-0 flex-1 basis-20 items-center gap-2">
          <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="size-4 shrink-0 text-muted/85">
            <path d="M2 5.2c0-.7.6-1.2 1.2-1.2h2.4l1.2 1.5h5c.7 0 1.2.6 1.2 1.2v5.1c0 .7-.6 1.2-1.2 1.2H3.2c-.7 0-1.2-.6-1.2-1.2V5.2Z" />
          </svg>
          <span className="truncate font-medium" title={projectName}>{projectName}</span>
        </span>
        <span className="shrink-0 whitespace-nowrap text-muted transition group-hover:text-accent-soft group-focus-visible:text-accent-soft">
          打开项目 <span className="ml-0.5" aria-hidden="true">→</span>
        </span>
      </Link>
    </article>
  );
}

export function AssetsPage() {
  const { projects, characters, scenes, shots, mode, user } = useStore();
  const recipeUserId = mode === 'api' ? user?.id : undefined;
  const { data: recipes } = useCreativeRecipes(recipeUserId);
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  const tab = params.get('tab');
  const kind = tab === 'scene' ? 'scene' : 'character';
  const showRecipes = tab === 'recipes' && !!recipeUserId;
  const activeTab = showRecipes ? 'recipes' : kind;

  function selectTab(next: AssetTab) {
    if (activeTab === next) return;
    const updated = new URLSearchParams(params);
    if (next === 'character') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated);
  }

  const projectName = useMemo(() => {
    const map = new Map(projects.map((p) => [p.id, p.name]));
    return (id: string) => map.get(id) ?? '已删除项目';
  }, [projects]);

  const usage = useMemo(() => {
    const map = new Map<string, { shots: number; episodes: Set<number> }>();
    for (const s of shots) {
      const project = projects.find((p) => p.id === s.projectId);
      const ep = project?.episodes.find((e) => e.id === s.episodeId);
      const names = kind === 'character' ? s.characterNames : [s.sceneName];
      for (const name of new Set(names)) {
        const key = `${s.projectId}:${name}`;
        const cur = map.get(key) ?? { shots: 0, episodes: new Set<number>() };
        cur.shots += 1;
        if (ep) cur.episodes.add(ep.index);
        map.set(key, cur);
      }
    }
    return map;
  }, [shots, projects, kind]);

  const assets = kind === 'character' ? characters : scenes;
  const columns = kind === 'character'
    ? 'grid-cols-[repeat(auto-fill,minmax(min(100%,13.5rem),1fr))]'
    : 'grid-cols-[repeat(auto-fill,minmax(min(100%,18rem),1fr))]';
  const q = filter.trim();
  const visible = q ? assets.filter((asset) => asset.name.includes(q) || projectName(asset.projectId).includes(q)) : assets;
  const tabs: { value: AssetTab; label: string; count?: number }[] = [
    { value: 'character', label: '角色', count: characters.length },
    { value: 'scene', label: '场景', count: scenes.length },
  ];
  if (recipeUserId) tabs.push({ value: 'recipes', label: '创作配方', count: recipes?.length });

  return (
    <div className="@container min-h-0 min-w-0 flex-1 overflow-y-auto scroll-thin">
      <header className="border-b border-stroke/20">
        <div className="mx-auto max-w-[84rem] px-3 pt-6 pb-4 @sm:px-5 @lg:px-6 @lg:pt-8">
          <h1 className="text-2xl font-semibold">资产库</h1>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <div role="group" aria-label="资产类别" className="flex max-w-full flex-wrap gap-1 rounded-card border border-stroke/25 bg-panel p-1">
              {tabs.map((t) => (
                <button
                  key={t.value}
                  type="button"
                  onClick={() => selectTab(t.value)}
                  aria-pressed={activeTab === t.value}
                  className={`rounded px-3 py-1.5 text-xs font-medium whitespace-nowrap transition ${
                    activeTab === t.value ? 'bg-accent/15 text-accent-soft' : 'text-muted hover:bg-hover/40 hover:text-fg'
                  }`}
                >
                  {t.label}
                  {t.count !== undefined && <span className="ml-1.5 text-muted/80 tabular-nums">{t.count}</span>}
                </button>
              ))}
            </div>
            {!showRecipes && <div className="relative w-full @md:w-60">
              <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5"
                strokeLinecap="round" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted/80">
                <circle cx="8.5" cy="8.5" r="4.5" /><path d="m12 12 4 4" />
              </svg>
              <input type="search" aria-label="搜索资产或项目" className="field h-9 py-0 pr-3 pl-9 text-sm"
                placeholder="搜索资产或项目" value={filter} onChange={(e) => setFilter(e.target.value)} />
            </div>}
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-[84rem] px-3 py-5 @sm:px-5 @lg:px-6 @lg:py-6">
        {showRecipes && recipeUserId ? (
          <section aria-label="创作配方"><CreativeRecipes userId={recipeUserId} /></section>
        ) : visible.length === 0 ? (
          <EmptyState
            title={q ? '没有匹配的资产' : `还没有${kind === 'character' ? '角色' : '场景'}`}
            description={
              q
                ? `换个关键词，或切到${kind === 'character' ? '场景' : '角色'}看看。`
                : '在某个项目里拆解剧本后，角色和场景会自动进入资产库。'
            }
            action={
              q ? undefined : <Button onClick={() => navigate('/')}>去我的项目</Button>
            }
          />
        ) : (
          <div className={`grid gap-4 ${columns}`}>
            {visible.map((asset) => {
              const use = usage.get(`${asset.projectId}:${asset.name}`);
              const episodes = [...(use?.episodes ?? [])].sort((a, b) => a - b);
              return <AssetCard key={`${kind}:${asset.id}`} asset={asset} kind={kind}
                projectName={projectName(asset.projectId)} shots={use?.shots ?? 0} episodes={episodes} />;
            })}
          </div>
        )}
      </div>
    </div>
  );
}
