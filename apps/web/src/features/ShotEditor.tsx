import { useEffect } from 'react';
import { Badge, ShotStatusBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { useStore } from '@/lib/store';
import type { CameraMove, Episode, Shot, ShotSize } from '@/lib/types';

const shotSizes: ShotSize[] = ['远景', '全景', '中景', '近景', '特写'];
const cameraMoves: CameraMove[] = ['固定', '推', '拉', '摇', '移', '跟'];

export function ShotEditor({
  shot,
  episode,
  onClose,
}: {
  shot: Shot | null;
  episode?: Episode;
  onClose: () => void;
}) {
  const { updateShot, confirmShot, unconfirmShot } = useStore();

  useEffect(() => {
    if (!shot) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [shot, onClose]);

  if (!shot) return null;

  const set = (patch: Partial<Shot>) => updateShot(shot.id, patch);
  const image = shot.images.find((i) => i.id === shot.selectedImageId);

  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/40" onClick={onClose} aria-hidden />
      <aside className="fixed right-0 top-0 z-40 flex h-full w-[400px] flex-col border-l border-stroke/25 bg-panel shadow-[-24px_0_60px_rgba(0,0,0,0.45)]">
        <header className="flex items-start justify-between border-b border-stroke/20 px-5 py-4">
          <div>
            <p className="font-mono text-[11px] text-muted">
              第 {episode?.index} 集 · {String(shot.seq).padStart(2, '0')}
            </p>
            <h2 className="mt-1 text-sm font-medium">{shot.sceneName}</h2>
            <div className="mt-2 flex flex-wrap gap-1.5">
              <ShotStatusBadge status={shot.status} />
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded px-2 py-1 text-xs text-muted transition hover:bg-hover/50 hover:text-fg"
          >
            关闭
          </button>
        </header>

        <div className="flex-1 space-y-5 overflow-y-auto scroll-thin p-5">
          {image && <PlaceholderImage seed={image.seed} ratio="16 / 9" label={`已选 ${image.label}`} />}

          <fieldset className="space-y-4">
            <label className="block text-xs text-muted">
              画面描述
              <textarea
                className="field mt-1.5 min-h-24 resize-y text-sm disabled:opacity-60"
                value={shot.description}
                onChange={(e) => set({ description: e.target.value })}
              />
            </label>

            <label className="block text-xs text-muted">
              台词
              <textarea
                className="field mt-1.5 min-h-16 resize-y text-sm disabled:opacity-60"
                value={shot.dialogue}
                onChange={(e) => set({ dialogue: e.target.value })}
              />
            </label>

            <div className="grid grid-cols-2 gap-3">
              <label className="block text-xs text-muted">
                景别
                <select
                  className="field mt-1.5 text-sm disabled:opacity-60"
                  value={shot.shotSize}
                  onChange={(e) => set({ shotSize: e.target.value as ShotSize })}
                >
                  {shotSizes.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-xs text-muted">
                运镜
                <select
                  className="field mt-1.5 text-sm disabled:opacity-60"
                  value={shot.cameraMove}
                  onChange={(e) => set({ cameraMove: e.target.value as CameraMove })}
                >
                  {cameraMoves.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <label className="block text-xs text-muted">
                场景
                <input
                  className="field mt-1.5 text-sm disabled:opacity-60"
                  value={shot.sceneName}
                  onChange={(e) => set({ sceneName: e.target.value })}
                />
              </label>
              <label className="block text-xs text-muted">
                时长（毫秒）
                <input
                  type="number"
                  min={1500}
                  max={12000}
                  step={500}
                  className="field mt-1.5 text-sm disabled:opacity-60"
                  value={shot.durationMs}
                  onChange={(e) => set({ durationMs: Math.max(1500, Number(e.target.value) || 3000) })}
                />
              </label>
            </div>

            <div className="text-xs text-muted">
              引用资产
              <div className="mt-2 flex flex-wrap gap-1.5">
                {shot.characterNames.length === 0 && (
                  <span className="text-muted/60">未识别角色</span>
                )}
                {shot.characterNames.map((n) => (
                  <Badge key={n} tone="accent">
                    {n}
                  </Badge>
                ))}
                <Badge tone="neutral">{shot.sceneName}</Badge>
              </div>
              <p className="mt-2 text-[11px] leading-5 text-muted/70">
                生成分镜图时会自动带上这些资产的当前基准版本。
              </p>
            </div>
          </fieldset>
        </div>

        <footer className="space-y-2 border-t border-stroke/20 p-5">
          {shot.status === 'draft' ? (
            <Button className="w-full" size="sm" onClick={() => confirmShot(shot.id)}>
              确认这一镜
            </Button>
          ) : (
            <Button variant="outline" size="sm" className="w-full" onClick={() => unconfirmShot(shot.id)}>
              取消确认
            </Button>
          )}
        </footer>
      </aside>
    </>
  );
}
