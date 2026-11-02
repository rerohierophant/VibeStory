import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AssetRow, type AssetKind } from '@/components/AssetRow';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { useProjectShots, useProject, useStore } from '@/lib/store';

const copy: Record<
  AssetKind,
  { title: string; unit: string; emptyTitle: string; emptyDesc: string; note: string }
> = {
  character: {
    title: '角色库',
    unit: '个角色',
    emptyTitle: '还没有角色',
    emptyDesc: '拆解这一集的剧本后，角色会从台词和描述里自动抽取，在这里定妆。',
    note: '人物资产可跨集复用；切换基准版本用于后续生成，已有分镜图保留原生成来源。',
  },
  scene: {
    title: '场景库',
    unit: '个场景',
    emptyTitle: '还没有场景',
    emptyDesc: '拆解后，镜头里出现的场景会被归到这里，同样需要一张基准图。',
    note: '场景资产可跨集复用；切换基准版本用于后续生成，已有分镜图保留原生成来源。',
  },
};

export function AssetLibraryPage({ kind }: { kind: AssetKind }) {
  const { id = '' } = useParams();
  const project = useProject(id);
  const { characters, scenes } = useStore();
  const shots = useProjectShots(id);
  const navigate = useNavigate();
  const [filter, setFilter] = useState('');

  const text = copy[kind];
  const charCount = characters.filter((c) => c.projectId === id).length;
  const sceneCount = scenes.filter((c) => c.projectId === id).length;
  const list = kind === 'character' ? characters.filter((c) => c.projectId === id) : scenes.filter((c) => c.projectId === id);

  const usage = useMemo(() => {
    const map = new Map<string, { shots: number; episodes: Set<number> }>();
    for (const s of shots) {
      const names = kind === 'character' ? s.characterNames : [s.sceneName];
      const ep = project?.episodes.find((e) => e.id === s.episodeId);
      for (const n of names) {
        const cur = map.get(n) ?? { shots: 0, episodes: new Set<number>() };
        cur.shots += 1;
        if (ep) cur.episodes.add(ep.index);
        map.set(n, cur);
      }
    }
    return map;
  }, [shots, kind, project]);

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

  const visible = list.filter((a) => !filter.trim() || a.name.includes(filter.trim()));

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="border-b border-stroke/15 px-8 py-6">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-medium">{text.title}</h1>
              <Badge tone="muted">{list.length}</Badge>
            </div>
            <p className="mt-1 max-w-2xl text-xs leading-5 text-muted">{text.note}</p>
          </div>
          <div className="flex items-center gap-2">
            <input
              className="field h-9 w-48 py-0 text-xs"
              placeholder={`搜索${kind === 'character' ? '角色' : '场景'}名`}
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <Link
              to={`/projects/${id}/${kind === 'character' ? 'scenes' : 'characters'}`}
              className="text-[11px] text-muted hover:text-fg"
            >
              {kind === 'character' ? `场景库 ${sceneCount} →` : `角色库 ${charCount} →`}
            </Link>
          </div>
        </div>
      </header>

      <div className="flex-1 overflow-y-auto scroll-thin p-8">
        {list.length === 0 ? (
          <EmptyState
            title={text.emptyTitle}
            description={text.emptyDesc}
            action={
              <Button onClick={() => navigate(`/projects/${id}/episodes`)}>
                去分集里拆解
              </Button>
            }
          />
        ) : visible.length === 0 ? (
          <EmptyState title="没有匹配的资产" description="换个关键词试试。" />
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(min(100%,28rem),1fr))] gap-4">
            {visible.map((a) => {
              const use = usage.get(a.name);
              return (
                <AssetRow
                  key={a.id}
                  kind={kind}
                  asset={a}
                  usedIn={use?.shots ?? 0}
                  episodes={[...(use?.episodes ?? [])].sort((x, y) => x - y)}
                />
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
