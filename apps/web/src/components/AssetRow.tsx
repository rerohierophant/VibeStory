import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { useStore } from '@/lib/store';
import type { AssetVersion, Character, SceneAsset } from '@/lib/types';

export type AssetKind = 'character' | 'scene';

export function VersionStrip({
  versions,
  selectedId,
  onSelect,
}: {
  versions: AssetVersion[];
  selectedId?: string;
  onSelect: (id: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {versions.map((v) => (
        <button
          key={v.id}
          type="button"
          onClick={() => onSelect(v.id)}
          className={`w-[76px] overflow-hidden rounded border-2 text-left transition ${
            v.id === selectedId ? 'border-accent' : 'border-transparent opacity-60 hover:opacity-100'
          }`}
          title={`${v.label} · ${v.source === 'uploaded' ? '上传' : '生成'}`}
        >
          <PlaceholderImage seed={v.seed} ratio="4 / 3" rounded="rounded-none" />
          <span className="block truncate bg-black/40 px-1.5 py-1 text-[10px] text-white/80">{v.label}</span>
        </button>
      ))}
    </div>
  );
}

/** 角色 / 场景的资产行：基准形象 + 版本历史，项目内资产库与全局资产库共用。 */
export function AssetRow({
  kind,
  asset,
  usedIn,
  episodes,
  projectName,
  onOpenProject,
}: {
  kind: AssetKind;
  asset: Character | SceneAsset;
  usedIn: number;
  episodes: number[];
  /** 全局资产库传入，用来标明资产归属；项目内不传。 */
  projectName?: string;
  onOpenProject?: () => void;
}) {
  const { addAssetVersion, selectAssetVersion } = useStore();
  const selected = asset.versions.find((v) => v.id === asset.selectedVersionId);

  return (
    <article className="grid grid-cols-[128px_minmax(0,1fr)] gap-5 rounded-panel border border-stroke/20 bg-panel/50 p-5 2xl:grid-cols-[150px_minmax(0,1fr)]">
      <div className="space-y-2">
        <PlaceholderImage
          seed={selected?.seed ?? asset.name}
          ratio="3 / 4"
          label={selected ? '基准形象' : '未定妆'}
        />
        <p className="text-center text-[10px] text-muted/70">{asset.versions.length} 个版本</p>
      </div>

      <div className="flex min-w-0 flex-col gap-3">
        <header className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h3 className="truncate text-sm font-medium">{asset.name}</h3>
              {projectName && <Badge tone="muted">{projectName}</Badge>}
            </div>
            <p className="mt-1 text-[11px] text-muted">
              出现于 {usedIn} 个镜头
              {episodes.length > 0 && ` · 第 ${episodes.join('、')} 集`}
            </p>
          </div>
          {selected && <Badge tone="accent">当前选定 {selected.label}</Badge>}
        </header>

        <p className="text-xs leading-6 text-muted">{asset.description}</p>

        <div className="space-y-2">
          <p className="text-[11px] text-muted">版本历史</p>
          <VersionStrip
            versions={asset.versions}
            selectedId={asset.selectedVersionId}
            onSelect={(id) => selectAssetVersion(kind, asset.id, id)}
          />
        </div>

        <div className="flex flex-wrap gap-2 pt-1">
          <Button size="sm" variant="outline" onClick={() => addAssetVersion(kind, asset.id, 'generated')}>
            生成一版
          </Button>
          <Button size="sm" variant="ghost" onClick={() => addAssetVersion(kind, asset.id, 'uploaded')}>
            上传参考图
          </Button>
          {onOpenProject && (
            <Button size="sm" variant="ghost" onClick={onOpenProject}>
              打开项目
            </Button>
          )}
        </div>
      </div>
    </article>
  );
}
