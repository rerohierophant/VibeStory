import { BatchGeneration } from './BatchGeneration';
import { useId, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, ShotStatusBadge, TaskBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { ImageGenerationLoading } from '@/components/GenerationLoading';
import { Modal } from '@/components/Modal';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { PortraitThumbnail } from '@/components/PortraitThumbnail';
import { ImageGenerationSource } from '@/components/ImageGenerationSource';
import { formatDuration } from '@/lib/id';
import { missingReferenceMessage, resolveShotReferences, type ShotReference } from '@/lib/imageSource';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';
import type { AssetVersion, Episode, Shot, ShotImage } from '@/lib/types';
import { ShotEditor } from './ShotEditor';
import { BreakdownError } from './BreakdownError';
import { blockedGenerationStyle, ShotReferenceHint } from './ShotReferenceHint';

const imageLoadingLabels = { queued: '分镜图排队中', running: '正在生成分镜图' } as const;

type Filter = 'all' | 'todo' | 'draft';
type Frame = { shotId: string; imageId: string };

function shotCode(shot: Shot) {
  return String(shot.seq).padStart(2, '0');
}

function selectedFrame(shot: Shot) {
  return shot.images.find((image) => image.id === shot.selectedImageId);
}

function ReferencePreview({ portrait = false, name, version }: { portrait?: boolean; name: string; version?: AssetVersion }) {
  const id = useId();
  const tileClass = 'relative block h-16 w-[5.25rem] overflow-hidden rounded-card border text-left';
  const captionClass = 'absolute inset-x-0 bottom-0 truncate px-1.5 pb-1 pt-3 text-center text-[11px] leading-4';

  if (!version) return (
    <li title={`${name} · 暂无基准图`}>
      <div className={`${tileClass} border-dashed border-stroke/25 bg-panel-2/30 text-muted`}>
        <span aria-hidden="true" className="absolute inset-x-0 top-2.5 text-center text-xs">—</span>
        <span className={captionClass}>{name}</span>
        <span className="sr-only">暂无基准图</span>
      </div>
    </li>
  );

  return (
    <li>
      <button type="button" popoverTarget={id} aria-label={`查看${name}基准图`} title={name}
        className={`${tileClass} group/ref border-stroke/25 bg-panel-2 transition hover:border-stroke/70 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-soft`}>
        {portrait ? <PortraitThumbnail key={version.seed} seed={version.seed} /> : (
          <PlaceholderImage seed={version.seed} alt="" ratio={null} rounded="rounded-none"
            className="h-full w-full transition group-hover/ref:brightness-110" />
        )}
        <span className={`${captionClass} bg-gradient-to-t from-black/90 to-transparent text-white`}>{name}</span>
      </button>
      <div id={id} popover="auto"
        className={`inset-auto m-0 mt-2 max-w-[calc(100vw-2rem)] rounded-panel border border-stroke/35 bg-panel p-2 text-fg shadow-xl [position-area:bottom_span-right] [position-try-fallbacks:flip-inline,flip-block,flip-block_flip-inline] [justify-self:start] ${portrait ? 'w-44' : 'w-72'}`}>
        <figure>
          <PlaceholderImage seed={version.seed} alt={`${name}基准图`} ratio={portrait ? '2 / 3' : '16 / 9'} fit="contain" />
          <figcaption className="px-1 pt-2 text-xs leading-5 text-muted [overflow-wrap:anywhere]">{name}</figcaption>
        </figure>
      </div>
    </li>
  );
}

function ShotReferences({ references }: { references: ShotReference[] }) {
  if (!references.length) return null;

  return (
    <ul aria-label="当前参考素材" className="mt-3.5 flex flex-wrap gap-1.5">
      {references.map(({ kind, name, version }) => (
        <ReferencePreview key={`${kind}:${name}`} portrait={kind === 'character'} name={name} version={version} />
      ))}
    </ul>
  );
}

function ShotPreview({
  shot,
  image,
  episode,
  onClose,
}: {
  shot: Shot;
  image?: ShotImage;
  episode?: Episode;
  onClose: () => void;
}) {
  const { imageState } = shot;
  return (
    <Modal open onClose={onClose} width="max-w-5xl" labelledBy="preview-title">
      <div className="flex items-center justify-between border-b border-stroke/20 px-6 py-4">
        <div>
          <h2 id="preview-title" className="text-sm font-medium">
            分镜预览 · 第 {shotCode(shot)} 镜
          </h2>
          <p className="mt-0.5 text-[11px] text-muted">
            {episode?.title} · {shot.sceneName}
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}>
          关闭
        </Button>
      </div>
      <div className="grid gap-6 p-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div className="space-y-3">
          {image ? <PlaceholderImage seed={image.seed} ratio="16 / 9" fit="contain" label={image.label}
            alt={`第 ${shotCode(shot)} 镜 · ${image.label}`} /> : imageState === 'queued' || imageState === 'running' ? (
            <div className="relative aspect-video" role="status">
              <ImageGenerationLoading state={imageState} label={imageLoadingLabels[imageState]} />
            </div>
          ) : (
            <div className="grid aspect-video place-items-center rounded-card border border-dashed border-stroke/30 text-sm text-muted">尚无分镜图</div>
          )}
          {shot.video.ready ? (
            <div className="flex items-center justify-between rounded-card border border-stroke/20 bg-black/25 px-4 py-3">
              <div className="flex items-center gap-2 text-xs text-muted">
                <span className="inline-flex h-7 w-7 items-center justify-center rounded-full bg-white/10">
                  ▶
                </span>
                视频片段 · {formatDuration(shot.video.durationMs ?? shot.durationMs)}
              </div>
              <span className="text-[11px] text-muted">尝试 {shot.video.attempts} 次</span>
            </div>
          ) : (
            <p className="rounded-card border border-dashed border-stroke/30 px-4 py-3 text-xs text-muted">
              还没有视频片段。到「视频」页提交生成。
            </p>
          )}
        </div>
        <aside className="space-y-4 text-sm">
          <p className="text-xs text-muted">{shot.shotSize} · {shot.cameraMove} · 计划 {formatDuration(shot.durationMs)}</p>
          <div>
            <p className="text-xs text-muted">画面描述</p>
            <p className="mt-1 leading-6">{shot.description || '—'}</p>
          </div>
          <div>
            <p className="text-xs text-muted">台词</p>
            <p className="mt-1 leading-6">{shot.dialogue || '—'}</p>
          </div>
          {image && <ImageGenerationSource image={image} />}
          {!image && (
            <div>
              <p className="text-xs text-muted">下次生成引用</p>
              <div className="mt-2 flex flex-wrap gap-1.5">
                {shot.characterNames.map((n) => (
                  <Badge key={n} tone="accent">
                    {n}
                  </Badge>
                ))}
                <Badge tone="neutral">{shot.sceneName}</Badge>
              </div>
            </div>
          )}
        </aside>
      </div>
    </Modal>
  );
}

function ShotRow({
  shot,
  code,
  active,
  reviewBusy,
  onOpenEditor,
  onRegenerate,
  onOpenPreview,
  onConfirm,
  onUnconfirm,
  onDelete,
}: {
  shot: Shot;
  code: string;
  active: boolean;
  reviewBusy: boolean;
  onOpenEditor: () => void;
  onRegenerate: () => void;
  onOpenPreview: (image?: ShotImage) => void;
  onConfirm: (image: ShotImage) => void;
  onUnconfirm: () => void;
  onDelete: (image: ShotImage) => void;
}) {
  const { generateImage, workspaceReadOnly, characters, scenes } = useStore();
  const references = resolveShotReferences(shot, characters, scenes);
  const missing = references.filter(ref => !ref.version);
  const hintId = useId();
  const [showHint, setShowHint] = useState(false);
  const selected = selectedFrame(shot);
  const [browsing, setBrowsing] = useState<{ imageId: string; selectedId?: string }>();
  const displayed = (browsing && browsing.selectedId === shot.selectedImageId
    ? shot.images.find(image => image.id === browsing.imageId) : undefined) ?? selected;
  const displayingConfirmed = shot.status === 'confirmed' && displayed?.id === selected?.id;
  const { imageState } = shot;
  const busy = imageState === 'queued' || imageState === 'running';
  const retryLabel = shot.imageState === 'unknown' ? '确认后重试' : shot.imageState === 'failed' ? '重试' : undefined;
  const canRegenerate = shot.images.length > 0 && !retryLabel;
  // A retry uses the original task's frozen input rather than today's asset selection.
  const blocked = !retryLabel && missing.length > 0;

  return (
    <tr
      className={`group border-b border-stroke/20 transition-colors ${
        active ? 'bg-accent/5' : 'hover:bg-panel-2/20'
      }`}
    >
      <th scope="row" className="px-4 py-5 align-top font-normal">
        <span className="font-mono text-xs text-muted">{code}</span>
      </th>

      <td className="px-4 py-4 align-top">
        <button
          type="button"
          onClick={() => onOpenPreview(displayed)}
          aria-haspopup="dialog"
          aria-label={`预览第 ${code} 镜分镜图`}
          className="group/preview relative block w-full overflow-hidden rounded-card ring-1 ring-inset ring-stroke/25 transition hover:ring-accent/60"
        >
          {displayed ? (
            <>
              <PlaceholderImage seed={displayed.seed} ratio="16 / 9" label={displayed.label} />
              <span className="absolute right-2 top-2 rounded bg-black/50 px-2 py-1 text-[11px] text-white/85 transition group-hover/preview:bg-black/70">
                放大 ↗
              </span>
            </>
          ) : busy ? (
            <div className="aspect-video" role="status">
              <ImageGenerationLoading state={imageState} label={imageLoadingLabels[imageState]} />
            </div>
          ) : (
            <div className="grid aspect-video place-items-center rounded-card border border-dashed border-stroke/35 bg-black/25 text-xs text-muted">
              {shot.images.length > 0 ? '尚未选定分镜图' : '待出图'}
            </div>
          )}
        </button>

        {shot.images.length > 0 && (
          <div className="mt-2.5 flex flex-wrap gap-2" aria-label={`第 ${code} 镜候选图`}>
            {shot.images.map((img) => (
              <button
                key={img.id}
                type="button"
                onClick={() => setBrowsing({ imageId: img.id, selectedId: shot.selectedImageId })}
                disabled={reviewBusy}
                aria-label={`第 ${code} 镜查看 ${img.label}`}
                aria-current={displayed?.id === img.id ? true : undefined}
                title={`查看 ${img.label}`}
                className={`w-14 overflow-hidden rounded border-2 transition ${
                  displayed?.id === img.id
                    ? 'border-accent'
                    : 'border-transparent opacity-60 hover:opacity-100'
                }`}
              >
                <PlaceholderImage seed={img.seed} ratio="16 / 9" rounded="rounded-none" />
                <span className="block truncate bg-panel-2/70 px-1 py-0.5 text-[10px] text-fg/85">
                  {img.label}
                </span>
              </button>
            ))}
          </div>
        )}
        {displayed && <div className="mt-2.5 flex flex-wrap items-center gap-2">
          {!displayingConfirmed && <Button size="sm" variant="outline" disabled={workspaceReadOnly || reviewBusy}
            onClick={() => onConfirm(displayed)}>确认画面</Button>}
          {!workspaceReadOnly && <Button type="button" size="sm" variant="ghost"
            aria-haspopup="dialog" disabled={reviewBusy} onClick={() => onDelete(displayed)}>删除候选</Button>}
        </div>}
      </td>

      <td className="px-4 py-4 align-top">
        <button
          type="button"
          onClick={onOpenEditor}
          aria-label={`编辑第 ${code} 镜：${shot.description || '无画面描述'}`}
          className="block w-full text-left"
        >
          <p className="line-clamp-4 text-sm leading-6 text-fg/90 transition hover:text-fg">
            {shot.description || '（无画面描述）'}
          </p>
          {shot.dialogue && (
            <div className="mt-3 border-l-2 border-stroke/35 pl-3">
              <span className="text-[11px] text-muted/70">台词</span>
              <p className="mt-1 line-clamp-3 text-[13px] leading-6 text-muted">{shot.dialogue}</p>
            </div>
          )}
        </button>
        <ShotReferences references={references} />
      </td>

      <td className="px-4 py-4 align-top">
        <dl className="space-y-3 text-xs">
          <div>
            <dt className="text-[11px] text-muted/70">景别 / 运镜</dt>
            <dd className="mt-1 text-fg/85">{shot.shotSize} · {shot.cameraMove}</dd>
          </div>
          <div>
            <dt className="text-[11px] text-muted/70">计划时长</dt>
            <dd className="mt-1 font-mono text-fg/85">{formatDuration(shot.durationMs)}</dd>
          </div>
        </dl>
      </td>

      <td className="space-y-3 px-4 py-4 align-top">
        {(displayed || shot.imageState !== 'idle' || shot.video.state !== 'idle') && <div className="flex flex-col items-start gap-2 text-[11px] text-muted">
          {displayed && <div className="self-center"><ShotStatusBadge status={shot.status} /></div>}
          {shot.imageState !== 'idle' && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span>出图</span>
              <TaskBadge state={shot.imageState} />
            </div>
          )}
          {shot.video.state !== 'idle' && (
            <div className="flex flex-wrap items-center gap-1.5">
              <span>视频</span>
              <TaskBadge state={shot.video.state} />
            </div>
          )}
        </div>}

        <div className="flex flex-col items-stretch gap-2">
          {shot.imageError && <p className="text-[11px] leading-5 text-error-soft">{shot.imageError}</p>}
          <Button type="button" size="sm" variant={shot.images.length ? 'outline' : 'primary'}
            disabled={workspaceReadOnly || busy} aria-haspopup={canRegenerate && !blocked ? 'dialog' : undefined}
            aria-disabled={blocked || undefined} aria-describedby={blocked && showHint ? hintId : undefined}
            className={blocked ? blockedGenerationStyle : undefined} title={blocked ? missingReferenceMessage(missing) : undefined}
            onClick={() => {
              if (blocked) setShowHint(true);
              else if (canRegenerate) onRegenerate();
              else void Promise.resolve(generateImage(shot.id)).catch(() => {});
            }}>
            {retryLabel ?? (canRegenerate ? '再生成一版' : '生成分镜图')}
          </Button>
          {blocked && showHint && <ShotReferenceHint id={hintId} projectId={shot.projectId} missing={missing} />}
          {shot.status === 'confirmed' && selected && (
            <Button size="sm" variant="ghost" disabled={workspaceReadOnly || reviewBusy} onClick={onUnconfirm}>取消确认</Button>
          )}
        </div>
      </td>
    </tr>
  );
}

export function StoryboardTab({ episodeId, projectId }: { episodeId: string; projectId: string }) {
  const shots = useEpisodeShots(episodeId);
  const project = useProject(projectId);
  const { confirmAll, confirmShot, unconfirmShot, deleteShotImage, runBreakdown, workspaceReadOnly } = useStore();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [editor, setEditor] = useState<{ shotId: string; regenerate: boolean } | null>(null);
  const [preview, setPreview] = useState<{ shotId: string; imageId?: string } | null>(null);
  const [batchReview, setBatchReview] = useState<Frame[] | null>(null);
  const [deleting, setDeleting] = useState<Frame | null>(null);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [reviewError, setReviewError] = useState('');
  const [confirmingBreakdown, setConfirmingBreakdown] = useState(false);

  const episode = project?.episodes.find((e) => e.id === episodeId);
  const running = episode?.breakdown === 'running';
  const canReplace = episode && !workspaceReadOnly && !running && episode.story.trim().length >= 20;

  const filtered = useMemo(() => {
    if (filter === 'todo') return shots.filter((s) => s.images.length === 0);
    if (filter === 'draft') return shots.filter((s) => s.selectedImageId && s.status === 'draft');
    return shots;
  }, [shots, filter]);

  const active = shots.find((s) => s.id === editor?.shotId) ?? null;
  const previewShot = preview && shots.find((s) => s.id === preview.shotId);
  const previewImage = previewShot && (preview.imageId
    ? previewShot.images.find((image) => image.id === preview.imageId)
    : selectedFrame(previewShot));
  const deleteShot = deleting && shots.find(shot => shot.id === deleting.shotId);
  const deleteImage = deleteShot && deleteShot.images.find(image => image.id === deleting.imageId);
  const pendingFrames = shots.flatMap((shot) => {
    const image = selectedFrame(shot);
    return shot.status === 'draft' && image ? [{ shotId: shot.id, imageId: image.id }] : [];
  });
  const draftCount = pendingFrames.length;
  const noImage = shots.filter((s) => s.images.length === 0).length;

  async function review(action: () => unknown, onSuccess?: () => void) {
    if (reviewBusy || workspaceReadOnly) return;
    setReviewBusy(true);
    setReviewError('');
    try {
      await action();
      onSuccess?.();
    } catch (error) {
      setReviewError(error instanceof Error ? error.message : '操作失败，请重试。');
    } finally {
      setReviewBusy(false);
    }
  }

  function closePreview() {
    if (reviewBusy) return;
    setPreview(null);
    setReviewError('');
  }

  function closeBatchReview() {
    if (reviewBusy) return;
    setBatchReview(null);
    setReviewError('');
  }

  function closeDelete() {
    if (reviewBusy) return;
    setDeleting(null);
    setReviewError('');
  }

  if (shots.length === 0) {
    return (
      <div className="p-8">
        <EmptyState
          title="这一集还没有分镜"
          description="先在「故事」里粘贴这一集的剧本并执行拆解，镜头会出现在这里。"
          action={
            <Button onClick={() => navigate(`/projects/${projectId}/episodes/${episodeId}/story`)}>
              去拆解这一集
            </Button>
          }
        />
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stroke/15 px-6 py-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex flex-wrap items-center gap-1.5">
            {(
              [
                ['all', '全部'],
                ['todo', '待出图'],
                ...(draftCount > 0 || filter === 'draft' ? [['draft', '待确认']] as const : []),
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                type="button"
                onClick={() => setFilter(key)}
                className={`rounded-full px-3 py-1 text-xs transition ${
                  filter === key ? 'bg-accent/20 text-fg' : 'text-muted hover:bg-hover/40'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <span className="h-4 w-px bg-stroke/25" />
          <span className="text-[11px] text-muted">第 {episode?.index} 集 · {episode?.title}</span>
        </div>

        <div className="flex flex-wrap items-center gap-3 text-[11px] text-muted">
          <span>
            {filtered.length} 镜 · {noImage} 镜待出图
            {draftCount > 0 && <span className="text-warning"> · {draftCount} 镜待确认</span>}
          </span>
          {draftCount > 0 && (
            <Button size="sm" variant="outline" aria-haspopup="dialog" disabled={workspaceReadOnly || reviewBusy}
              onClick={() => { setReviewError(''); setBatchReview(pendingFrames); }}>
              批量确认画面
            </Button>
          )}
          <Button size="sm" variant="ghost" disabled={!canReplace} onClick={() => setConfirmingBreakdown(true)}>
            {running ? '拆解中…' : '重新拆解'}
          </Button>
        </div>
      </div>

      <BreakdownError task={episode?.breakdownTask} className="border-b border-stroke/15 px-6 py-2" />
      {reviewError && !previewShot && !batchReview && !deleting && <p role="alert" className="px-6 py-2 text-xs text-error-soft">{reviewError}</p>}
      <BatchGeneration episodeId={episodeId} type="IMAGE" />

      <div className="min-h-0 flex-1 overflow-auto scroll-thin px-6 pb-6">
        {filtered.length === 0 ? (
          <EmptyState title="没有符合条件的镜头" description="切换筛选条件试试。" />
        ) : (
          <table className="w-full min-w-[944px] table-fixed border-collapse text-left [--storyboard-image-width:clamp(15rem,22vw,22rem)]">
            <caption className="sr-only">
              第 {episode?.index} 集 · {episode?.title} 分镜表。点击缩略图切换画面，点击主图放大，点击描述编辑镜头。
            </caption>
            <colgroup>
              <col className="w-16" />
              <col style={{ width: 'calc(var(--storyboard-image-width) + 2rem)' }} />
              <col />
              <col className="w-32" />
              <col className="w-36" />
            </colgroup>
            <thead className="sticky top-0 z-10 bg-panel text-[11px] text-muted shadow-[0_1px_0_var(--vibe-panel-2)]">
              <tr>
                {['镜号', '分镜画面', '画面描述 / 台词', '镜头参数', '状态 / 操作'].map((label) => (
                  <th key={label} scope="col" className="px-4 py-3 font-medium">{label}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.map((s) => (
                <ShotRow
                  key={s.id}
                  shot={s}
                  code={shotCode(s)}
                  active={editor?.shotId === s.id}
                  reviewBusy={reviewBusy}
                  onOpenEditor={() => setEditor({ shotId: s.id, regenerate: false })}
                  onRegenerate={() => setEditor({ shotId: s.id, regenerate: true })}
                  onOpenPreview={(image) => { setReviewError(''); setPreview({ shotId: s.id, imageId: image?.id }); }}
                  onConfirm={(image) => void review(() => confirmShot(s.id, image.id))}
                  onUnconfirm={() => void review(() => unconfirmShot(s.id))}
                  onDelete={(image) => { setReviewError(''); setDeleting({ shotId: s.id, imageId: image.id }); }}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      <ShotEditor key={active?.id} shot={active} episode={episode} regenerate={editor?.regenerate} onClose={() => setEditor(null)} />
      {previewShot && <ShotPreview shot={previewShot} image={previewImage || undefined} episode={episode} onClose={closePreview} />}
      {deleting && <Modal open onClose={closeDelete} width="max-w-md" labelledBy="delete-candidate-title">
        <div className="p-6">
          <h2 id="delete-candidate-title" className="text-lg font-medium">删除候选图？</h2>
          {deleteShot && deleteImage ? (
            <>
              <PlaceholderImage seed={deleteImage.seed} alt={deleteImage.label} ratio="16 / 9" className="mt-4" />
              <p className="mt-3 text-sm leading-6 text-muted">
                第 {shotCode(deleteShot)} 镜 · {deleteImage.label}。删除后无法在分镜中恢复。
                {deleteShot.selectedImageId === deleteImage.id && (deleteShot.images.length > 1
                  ? '请重新确认画面，再生成新视频。' : '该镜头将回到待出图。')}
              </p>
            </>
          ) : <p className="mt-3 text-sm text-muted">这张候选图已移除。</p>}
          {reviewError && <p role="alert" className="mt-3 text-xs text-error-soft">{reviewError}</p>}
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="ghost" autoFocus disabled={reviewBusy} onClick={closeDelete}>取消</Button>
            <Button variant="danger" disabled={!deleteImage || workspaceReadOnly || reviewBusy} onClick={() => void review(
              () => deleteShotImage(deleting.shotId, deleting.imageId),
              () => setDeleting(null),
            )}>{reviewBusy ? '删除中…' : '确认删除'}</Button>
          </div>
        </div>
      </Modal>}
      {batchReview && <Modal open onClose={closeBatchReview} width="max-w-5xl" labelledBy="batch-review-title">
        <div className="px-6 py-5">
          <h2 id="batch-review-title" className="font-medium">批量确认分镜画面</h2>
          <p className="mt-1 text-xs text-muted">确认后，下列画面将用作各镜头后续视频生成的首帧。</p>
          <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {batchReview.map(({ shotId, imageId }) => {
              const shot = shots.find((item) => item.id === shotId);
              const image = shot?.images.find((item) => item.id === imageId);
              return <figure key={shotId}>
                {image ? <PlaceholderImage seed={image.seed} fit="contain" alt={`第 ${shotCode(shot!)} 镜 · ${image.label}`} /> :
                  <div className="grid aspect-video place-items-center rounded-card border border-dashed border-stroke/30 text-xs text-muted">画面已变化</div>}
                <figcaption className="mt-2 text-xs text-muted">{shot && `第 ${shotCode(shot)} 镜`}{image && ` · ${image.label}`}</figcaption>
              </figure>;
            })}
          </div>
        </div>
        <footer className="sticky bottom-0 flex flex-wrap items-center justify-end gap-2 border-t border-stroke/20 bg-panel px-6 py-4">
          {reviewError && <p role="alert" className="mr-auto text-xs text-error-soft">{reviewError}</p>}
          <Button size="sm" variant="ghost" disabled={reviewBusy} autoFocus onClick={closeBatchReview}>取消</Button>
          <Button size="sm" disabled={workspaceReadOnly || reviewBusy} onClick={() => void review(
            () => confirmAll(episodeId, Object.fromEntries(batchReview.map(({ shotId, imageId }) => [shotId, imageId]))),
            () => setBatchReview(null),
          )}>{reviewBusy ? '确认中…' : `确认 ${batchReview.length} 张画面`}</Button>
        </footer>
      </Modal>}
      <Modal
        open={confirmingBreakdown}
        onClose={() => setConfirmingBreakdown(false)}
        width="max-w-md"
        labelledBy="confirm-breakdown-title"
      >
        <div className="p-6">
          <h2 id="confirm-breakdown-title" className="text-lg font-medium">
            重新拆解这一集？
          </h2>
          <p className="mt-3 text-sm leading-6 text-muted">
            将按当前剧本覆盖本集现有的 {shots.length} 个镜头，包括手动修改、确认状态、分镜图和视频。新镜头需要重新出图。
          </p>
          <p className="mt-2 text-xs leading-5 text-muted">此操作无法撤销。</p>
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="ghost" autoFocus onClick={() => setConfirmingBreakdown(false)}>
              取消
            </Button>
            <Button variant="danger" disabled={!canReplace} onClick={() => {
              setConfirmingBreakdown(false);
              runBreakdown(episodeId, true);
            }}>
              确认重新拆解
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
