import type { AssetVersionSnapshot, ShotImage } from '@/lib/types';

function VersionSource({ source }: { source: AssetVersionSnapshot }) {
  return (
    <span title={source.versionId}>
      {source.name} · {source.versionLabel ?? source.versionId ?? '未关联资产版本'}
    </span>
  );
}

/** 只展示这张图的历史来源，不与资产当前版本比较。 */
export function ImageGenerationSource({ image }: { image: ShotImage }) {
  const snapshot = image.inputSnapshot;

  return (
    <section aria-label="分镜生成来源" className="space-y-2 text-xs text-muted">
      <h3 className="font-medium text-fg/90">生成来源 · {image.label}</h3>
      {image.guidance?.notice && <p className="text-[11px] leading-5">{image.guidance.notice}</p>}
      {snapshot ? (
        <dl className="space-y-2 text-[11px] leading-5">
          <div className="flex items-start gap-3">
            <dt className="shrink-0 text-muted/70">人物</dt>
            <dd className="min-w-0 space-y-1 break-words">
              {snapshot.characters.length > 0
                ? snapshot.characters.map((source, index) => (
                    <div key={source.assetId ?? `${source.name}-${index}`}>
                      <VersionSource source={source} />
                    </div>
                  ))
                : '未引用人物资产'}
            </dd>
          </div>
          <div className="flex items-start gap-3">
            <dt className="shrink-0 text-muted/70">场景</dt>
            <dd className="min-w-0 break-words">
              <VersionSource source={snapshot.scene} />
            </dd>
          </div>
        </dl>
      ) : (
        <p className="text-[11px] leading-5 text-muted/70">这张分镜图未记录生成来源。</p>
      )}
    </section>
  );
}
