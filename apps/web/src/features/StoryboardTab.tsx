import { BatchGeneration } from './BatchGeneration';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, ShotStatusBadge, TaskBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { Modal } from '@/components/Modal';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { ImageGenerationSource } from '@/components/ImageGenerationSource';
import { formatDuration } from '@/lib/id';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';
import type { Episode, Shot } from '@/lib/types';
import { ShotEditor } from './ShotEditor';
import { StoryboardVariants } from './StoryboardVariants';
import { ImageLearning } from './ImageLearning';

type Filter = 'all' | 'todo' | 'draft';

function shotCode(shot: Shot) {
  return String(shot.seq).padStart(2, '0');
}

function ShotPreview({
  shot,
  episode,
  onClose,
}: {
  shot: Shot | null;
  episode?: Episode;
  onClose: () => void;
}) {
  if (!shot) return null;
  const image = shot.images.find((i) => i.id === shot.selectedImageId);
  return (
    <Modal open onClose={onClose} width="max-w-5xl" labelledBy="preview-title">
      <div className="flex items-center justify-between border-b border-stroke/20 px-6 py-4">
        <div>
          <h2 id="preview-title" className="text-sm font-medium">
            第 {episode?.index} 集 · {shotCode(shot)} · {shot.sceneName}
          </h2>
          <p className="mt-0.5 text-[11px] text-muted">
            {episode?.title} · {shot.shotSize} · {shot.cameraMove} · {formatDuration(shot.durationMs)}
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}>
          关闭
        </Button>
      </div>
      <div className="grid gap-6 p-6 lg:grid-cols-[minmax(0,1fr)_280px]">
        <div className="space-y-3">
          <PlaceholderImage
            seed={image?.seed ?? `${shot.sceneName}-${shot.seq}`}
            ratio="16 / 9"
            label={image ? `已选 ${image.label}` : '尚无分镜图'}
          />
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
  onOpenEditor,
  onOpenPreview,
}: {
  shot: Shot;
  code: string;
  active: boolean;
  onOpenEditor: () => void;
  onOpenPreview: () => void;
}) {
  const { generateImage, selectImage, confirmShot, workspaceReadOnly } = useStore();
  const selected = shot.images.find((i) => i.id === shot.selectedImageId);
  const busy = shot.imageState === 'queued' || shot.imageState === 'running';

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
          onClick={onOpenPreview}
          aria-label={`预览第 ${code} 镜分镜图`}
          className="group/preview relative block w-full overflow-hidden rounded-card ring-1 ring-inset ring-stroke/25 transition hover:ring-accent/60"
        >
          {selected ? (
            <>
              <PlaceholderImage seed={selected.seed} ratio="16 / 9" label={`已选 ${selected.label}`} />
              <span className="absolute right-2 top-2 rounded bg-black/50 px-2 py-1 text-[11px] text-white/85 transition group-hover/preview:bg-black/70">
                放大 ↗
              </span>
            </>
          ) : (
            <div
              className="flex flex-col items-center justify-center gap-2 rounded-card border border-dashed border-stroke/35 bg-black/25 text-xs text-muted"
              style={{ aspectRatio: '16 / 9' }}
            >
              {busy ? (
                <>
                  <span className="h-5 w-5 animate-spin rounded-full border-2 border-accent/40 border-t-accent" />
                  {shot.imageState === 'queued' ? '排队中' : '生成中'}
                </>
              ) : (
                <>
                  <span>{shot.images.length > 0 ? '尚未选定分镜图' : '尚无分镜图'}</span>
                  <span className="text-[11px] text-muted/65">
                    {shot.status === 'draft' ? '确认镜头后即可生成' : '点击画面可查看镜头详情'}
                  </span>
                </>
              )}
            </div>
          )}
        </button>

        {shot.images.length > 0 && (
          <div className="mt-2.5 flex flex-wrap gap-2" aria-label={`第 ${code} 镜候选图`}>
            {shot.images.map((img) => (
              <button
                key={img.id}
                type="button"
                disabled={workspaceReadOnly}
                onClick={() => selectImage(shot.id, img.id)}
                aria-label={`第 ${code} 镜选定 ${img.label}`}
                aria-pressed={shot.selectedImageId === img.id}
                title={`选定 ${img.label}`}
                className={`w-14 overflow-hidden rounded border-2 transition ${
                  shot.selectedImageId === img.id
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
          <p className="mt-3 text-[11px] leading-5 text-muted">
            人物 · {shot.characterNames.join('、') || '未识别'}
          </p>
        </button>
      </td>

      <td className="px-4 py-4 align-top">
        <dl className="space-y-3 text-xs">
          <div>
            <dt className="text-[11px] text-muted/70">场景</dt>
            <dd className="mt-1 break-words leading-5 text-fg/85">{shot.sceneName || '—'}</dd>
          </div>
          <div>
            <dt className="text-[11px] text-muted/70">景别 / 运镜</dt>
            <dd className="mt-1 text-fg/85">{shot.shotSize} · {shot.cameraMove}</dd>
          </div>
          <div>
            <dt className="text-[11px] text-muted/70">时长</dt>
            <dd className="mt-1 font-mono text-fg/85">{formatDuration(shot.durationMs)}</dd>
          </div>
        </dl>
      </td>

      <td className="px-4 py-4 align-top">
        <div className="flex flex-col items-start gap-2 text-[11px] text-muted">
          <ShotStatusBadge status={shot.status} />
          {shot.video.ready ? (
            <Badge tone="success">有视频</Badge>
          ) : (
            <Badge tone="muted">无视频</Badge>
          )}
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
        </div>

        <div className="mt-4 flex flex-col items-stretch gap-2">
          {shot.imageError && <p className="text-[11px] leading-5 text-error-soft">{shot.imageError}</p>}
          {shot.status === 'draft' ? (
            <Button size="sm" variant="outline" disabled={workspaceReadOnly} onClick={() => confirmShot(shot.id)}>
              确认
            </Button>
          ) : null}
          <Button
            size="sm"
            variant={shot.images.length === 0 ? 'primary' : 'outline'}
            onClick={() => generateImage(shot.id)}
            disabled={workspaceReadOnly || busy || shot.status !== 'confirmed'}
          >
            {shot.imageState === 'unknown' ? '确认后重试' : shot.imageState === 'failed' ? '重试' : shot.images.length === 0 ? '生成分镜图' : '再生成一版'}
          </Button>
          <Button size="sm" variant="ghost" onClick={onOpenEditor}>
            编辑镜头
          </Button>
          <ImageLearning projectId={shot.projectId} targetType="SHOT" targetId={shot.id} />
        </div>
      </td>
    </tr>
  );
}

export function StoryboardTab({ episodeId, projectId }: { episodeId: string; projectId: string }) {
  const shots = useEpisodeShots(episodeId);
  const project = useProject(projectId);
  const { confirmAll, mode, workspaceReadOnly } = useStore();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [activeId, setActiveId] = useState<string | null>(null);
  const [preview, setPreview] = useState<Shot | null>(null);

  const episode = project?.episodes.find((e) => e.id === episodeId);

  const filtered = useMemo(() => {
    if (filter === 'todo') return shots.filter((s) => s.images.length === 0);
    if (filter === 'draft') return shots.filter((s) => s.status === 'draft');
    return shots;
  }, [shots, filter]);

  const active = shots.find((s) => s.id === activeId) ?? null;
  const draftCount = shots.filter((s) => s.status === 'draft').length;
  const noImage = shots.filter((s) => s.images.length === 0).length;

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
                ['draft', '待确认'],
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

        <div className="flex items-center gap-3 text-[11px] text-muted">
          <span>
            {filtered.length} 镜 · {noImage} 待出图
            {draftCount > 0 && <span className="text-warning"> · {draftCount} 待确认</span>}
          </span>
          <Button size="sm" variant="outline" onClick={() => confirmAll(episodeId)} disabled={workspaceReadOnly || draftCount === 0}>
            全部确认
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => navigate(`/projects/${projectId}/episodes/${episodeId}/video`)}
          >
            去生成视频 →
          </Button>
        </div>
      </div>

      <BatchGeneration episodeId={episodeId} type="IMAGE" />
      {mode === 'api' && <StoryboardVariants key={episodeId} episodeId={episodeId} shots={shots} />}

      <div className="min-h-0 flex-1 overflow-auto scroll-thin px-6 pb-6">
        {filtered.length === 0 ? (
          <EmptyState title="没有符合条件的镜头" description="切换筛选条件试试。" />
        ) : (
          <table className="w-full min-w-[944px] table-fixed border-collapse text-left [--storyboard-image-width:clamp(15rem,22vw,22rem)]">
            <caption className="sr-only">
              第 {episode?.index} 集 · {episode?.title} 分镜表。点击画面查看大图，点击描述编辑镜头。
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
                  active={activeId === s.id}
                  onOpenEditor={() => setActiveId(s.id)}
                  onOpenPreview={() => setPreview(s)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>

      <ShotEditor shot={active} episode={episode} onClose={() => setActiveId(null)} />
      <ShotPreview shot={preview ? shots.find(s => s.id === preview.id) ?? null : null} episode={episode} onClose={() => setPreview(null)} />
    </div>
  );
}
