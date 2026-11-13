import { useRef, useState } from 'react';
import { Button } from '@/components/Button';
import { ImageGenerationLoading } from '@/components/GenerationLoading';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { useStore } from '@/lib/store';
import type { AssetVersion, Character, SceneAsset } from '@/lib/types';
import { ImageGenerationButton } from '@/features/ImageRegeneration';

export type AssetKind = 'character' | 'scene';

export function VersionStrip({
  kind,
  versions,
  selectedId,
  onSelect,
  readOnly,
}: {
  kind: AssetKind;
  versions: AssetVersion[];
  selectedId?: string;
  onSelect: (id: string) => void;
  readOnly?: boolean;
}) {
  const portrait = kind === 'character';
  return (
    <div className="flex flex-wrap gap-2">
      {versions.map((v, index) => {
        const label = v.source === 'generated' ? '生成图' : v.label;
        return (
          <button
            key={v.id}
            type="button"
            disabled={readOnly}
            onClick={() => onSelect(v.id)}
            aria-label={`设为基准图：${label}（第 ${index + 1} 版）`}
            aria-pressed={v.id === selectedId}
            className={`${portrait ? 'w-12' : 'w-[76px]'} overflow-hidden rounded border-2 text-left transition ${
              v.id === selectedId ? 'border-accent' : 'border-transparent opacity-60 hover:opacity-100'
            }`}
            title={label}
          >
            <PlaceholderImage seed={v.seed} ratio={portrait ? '2 / 3' : '4 / 3'}
              fit={portrait ? 'contain' : 'cover'} rounded="rounded-none" />
            <span className={`block truncate bg-black/40 py-1 text-[10px] text-white/80 ${portrait ? 'px-0.5 text-center' : 'px-1.5'}`}>
              {label}
            </span>
          </button>
        );
      })}
    </div>
  );
}

/** 项目内角色或场景的基准图与版本操作。 */
export function AssetRow({
  kind,
  asset,
  usedIn,
  episodes,
}: {
  kind: AssetKind;
  asset: Character | SceneAsset;
  usedIn: number;
  episodes: number[];
}) {
  const { addAssetVersion, selectAssetVersion, workspaceReadOnly } = useStore();
  const upload = useRef<HTMLInputElement>(null);
  const [submitting, setSubmitting] = useState(false);
  const selected = asset.versions.find((v) => v.id === asset.selectedVersionId);
  const isCharacter = kind === 'character';
  const imageLabel = isCharacter ? '角色图' : '场景图';
  const uploadLabel = `上传${imageLabel}`;
  const generation = asset.generationState === 'running' || asset.generationState === 'queued'
    ? asset.generationState : submitting ? 'submitting' : undefined;
  const generationLabel = generation === 'running' ? `正在生成${imageLabel}`
    : generation === 'queued' ? `${imageLabel}排队中` : '正在提交…';
  const selectVersion = (id: string) => selectAssetVersion(kind, asset.id, id);
  const description = <p className="text-xs leading-6 text-muted [overflow-wrap:anywhere]">{asset.description}</p>;

  async function generate() {
    setSubmitting(true);
    try {
      await addAssetVersion(kind, asset.id, 'generated');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <article className={`min-w-0 gap-3 rounded-panel border border-stroke/20 bg-panel/50 p-3 ${
      isCharacter
        ? 'row-span-4 grid grid-cols-[clamp(3.75rem,39%,10rem)_minmax(0,1fr)] grid-rows-subgrid items-start @min-[60rem]/asset-library:p-4'
        : 'flex flex-col @min-[48rem]/asset-library:p-5'
    }`}>
      <figure className={`relative ${isCharacter ? 'row-span-4 aspect-[2/3] w-full min-w-0' : 'aspect-video'}`}>
        {(selected || !generation) && (
          <PlaceholderImage seed={selected?.seed ?? asset.name} ratio={null} className="h-full"
            fit="contain" alt={selected ? `${asset.name}基准图` : undefined}
            label={selected ? undefined : '暂无基准图'} />
        )}
        <div role="status">
          {!selected && generation && <ImageGenerationLoading state={generation} label={generationLabel} />}
        </div>
      </figure>
      <header className="min-w-0">
        <h3 className="text-sm font-medium [overflow-wrap:anywhere]">{asset.name}</h3>
        {!isCharacter && <p className="mt-1 text-[11px] text-muted">
          出现于 {usedIn} 个镜头
          {episodes.length > 0 && ` · 第 ${episodes.join('、')} 集`}
        </p>}
      </header>

      {description}

      {asset.versions.length === 0 ? <p className="text-xs text-muted">0 个版本</p> : (
        <div className="space-y-2 text-xs text-muted">
          <p>版本历史 · 共 {asset.versions.length} 个版本</p>
          <VersionStrip kind={kind} versions={asset.versions} selectedId={asset.selectedVersionId}
            readOnly={workspaceReadOnly} onSelect={selectVersion} />
        </div>
      )}

      <div className="mt-auto flex flex-wrap gap-2 pt-1">
        <ImageGenerationButton projectId={asset.projectId} targetType={isCharacter ? 'CHARACTER' : 'SCENE'}
          targetId={asset.id} name={asset.name} hasImage={asset.versions.length > 0} generation={generation} initialLabel="生成一版"
          retryLabel={asset.generationState === 'unknown' ? '确认重试' : asset.generationState === 'failed' ? '重试' : undefined}
          onGenerate={generate} />
        <input ref={upload} type="file" accept="image/jpeg,image/png" className="hidden" aria-label={uploadLabel}
          onChange={e => { const file = e.target.files?.[0]; if (file) addAssetVersion(kind, asset.id, 'uploaded', file); e.target.value = ''; }} />
        <Button size="sm" variant="ghost" disabled={workspaceReadOnly} onClick={() => upload.current?.click()}>
          {uploadLabel}
        </Button>
      </div>
    </article>
  );
}
