import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AssetRow, type AssetKind } from '@/components/AssetRow';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { useStore } from '@/lib/store';

interface Row {
  kind: AssetKind;
  asset: (ReturnType<typeof useStore>['characters'])[number];
}

/** 全局资产库：跨项目汇总，默认展示角色，可切到场景。 */
export function AssetsPage() {
  const { projects, characters, scenes, shots } = useStore();
  const navigate = useNavigate();
  const [scope, setScope] = useState<AssetKind>('character');
  const [filter, setFilter] = useState('');

  const projectName = useMemo(() => {
    const map = new Map(projects.map((p) => [p.id, p.name]));
    return (id: string) => map.get(id) ?? '已删除项目';
  }, [projects]);

  const usage = useMemo(() => {
    const map = new Map<string, { shots: number; episodes: number[] }>();
    for (const s of shots) {
      const project = projects.find((p) => p.id === s.projectId);
      const ep = project?.episodes.find((e) => e.id === s.episodeId);
      for (const name of [s.sceneName, ...s.characterNames]) {
        const key = `${s.projectId}:${name}`;
        const cur = map.get(key) ?? { shots: 0, episodes: [] };
        cur.shots += 1;
        if (ep && !cur.episodes.includes(ep.index)) cur.episodes.push(ep.index);
        map.set(key, cur);
      }
    }
    return map;
  }, [shots, projects]);

  const rows = useMemo<Row[]>(() => {
    const list: Row[] =
      scope === 'character'
        ? characters.map((asset) => ({ kind: 'character' as const, asset }))
        : scenes.map((asset) => ({ kind: 'scene' as const, asset }));
    const q = filter.trim();
    return q ? list.filter((r) => r.asset.name.includes(q) || projectName(r.asset.projectId).includes(q)) : list;
  }, [scope, characters, scenes, filter, projectName]);

  const tabs: { value: AssetKind; label: string; count: number }[] = [
    { value: 'character', label: '角色', count: characters.length },
    { value: 'scene', label: '场景', count: scenes.length },
  ];

  const active = tabs.find((t) => t.value === scope)!;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="border-b border-stroke/15 px-10 py-8">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-2xl font-semibold">资产库</h1>
              <Badge tone="muted">{active.count}</Badge>
            </div>
            <p className="mt-1 text-sm text-muted">
              跨项目的{scope === 'character' ? '角色' : '场景'}基准图。换掉基准版本，依赖它的分镜会被标记为陈旧。
            </p>
          </div>
          <input
            className="field h-10 w-56 py-0 text-sm"
            placeholder="搜索资产或项目"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>

        <div className="mt-6 flex gap-1">
          {tabs.map((t) => (
            <button
              key={t.value}
              type="button"
              onClick={() => setScope(t.value)}
              className={`rounded-card px-3 py-1.5 text-xs transition ${
                scope === t.value ? 'bg-hover/70 text-fg' : 'text-muted hover:bg-hover/40 hover:text-fg'
              }`}
            >
              {t.label}
              <span className="ml-1.5 text-muted/70">{t.count}</span>
            </button>
          ))}
        </div>
      </header>

      <div className="flex-1 overflow-y-auto scroll-thin px-10 py-8">
        {rows.length === 0 ? (
          <EmptyState
            title={filter.trim() ? '没有匹配的资产' : `还没有${scope === 'character' ? '角色' : '场景'}`}
            description={
              filter.trim()
                ? `换个关键词，或切到${scope === 'character' ? '场景' : '角色'}看看。`
                : '在某个项目里拆解剧本后，角色和场景会自动进入资产库。'
            }
            action={
              filter.trim() ? undefined : <Button onClick={() => navigate('/')}>去我的项目</Button>
            }
          />
        ) : (
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 2xl:grid-cols-3">
            {rows.map(({ kind, asset }) => {
              const use = usage.get(`${asset.projectId}:${asset.name}`);
              return (
                <AssetRow
                  key={`${kind}:${asset.id}`}
                  kind={kind}
                  asset={asset}
                  usedIn={use?.shots ?? 0}
                  episodes={[...(use?.episodes ?? [])].sort((x, y) => x - y)}
                  projectName={projectName(asset.projectId)}
                  onOpenProject={() => navigate(`/projects/${asset.projectId}`)}
                />
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
