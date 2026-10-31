import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, ShotStatusBadge, TaskBadge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { Modal } from '@/components/Modal';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { formatDuration } from '@/lib/id';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';
import type { Episode, Shot } from '@/lib/types';import { ShotEditor } from './ShotEditor';

type Filter = 'all' | 'todo' | 'stale' | 'draft';

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
                视频片段 · {formatDuration(shot.durationMs)}
                {shot.video.stale && <Badge tone="warning">基于旧图，需要重做</Badge>}
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
          <div>
            <p className="text-xs text-muted">引用资产</p>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {shot.characterNames.map((n) => (
                <Badge key={n} tone="accent">
                  {n}
                </Badge>
              ))}
              <Badge tone="neutral">{shot.sceneName}</Badge>
            </div>
          </div>
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
  const { generateImage, selectImage, confirmShot } = useStore();
  const selected = shot.images.find((i) => i.id === shot.selectedImageId);
  const busy = shot.imageState === 'queued' || shot.imageState === 'running';

  return (
    <div
      className={`grid grid-cols-[112px_minmax(0,1fr)_150px] gap-4 rounded-card border p-2.5 transition ${
        active ? 'border-accent/60 bg-accent/5' : 'border-stroke/15 bg-panel/40 hover:border-stroke/35'
      }`}
    >
      <div className="space-y-1.5">
        <button type="button" onClick={onOpenPreview} className="block w-full">
          {selected ? (
            <PlaceholderImage seed={selected.seed} ratio="16 / 9" label={code} />
          ) : (
            <div
              className="flex flex-col items-center justify-center gap-1 rounded-card border border-dashed border-stroke/35 bg-black/25 text-[10px] text-muted"
              style={{ aspectRatio: '16 / 9' }}
            >
              {busy ? (
                <>
                  <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-accent/40 border-t-accent" />
                  {shot.imageState === 'queued' ? '排队中' : '生成中'}
                </>
              ) : (
                <span>尚无分镜图</span>
              )}
            </div>
          )}
        </button>

        {shot.images.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {shot.images.map((img) => (
              <button
                key={img.id}
                type="button"
                onClick={() => selectImage(shot.id, img.id)}
                title={`选定 ${img.label}`}
                className={`w-7 overflow-hidden rounded border-2 transition ${
                  shot.selectedImageId === img.id
                    ? 'border-accent'
                    : 'border-transparent opacity-55 hover:opacity-100'
                }`}
              >
                <PlaceholderImage seed={img.seed} ratio="4 / 3" rounded="rounded-none" />
              </button>
            ))}
          </div>
        )}
      </div>

      <button type="button" onClick={onOpenEditor} className="min-w-0 text-left">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-[11px] text-muted">{code}</span>
          <ShotStatusBadge status={shot.status} />
          {shot.images.length === 0 && <Badge tone="muted">无图</Badge>}
        </div>

        <p className="mt-2 line-clamp-2 text-sm leading-6 text-fg/90">
          {shot.description || '（无画面描述）'}
        </p>
        {shot.dialogue && (
          <p className="mt-1 line-clamp-1 text-[13px] leading-6 text-muted">「{shot.dialogue}」</p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted">
          <span>{shot.sceneName}</span>
          <span>· {shot.shotSize}</span>
          <span>· {shot.cameraMove}</span>
          <span>· {formatDuration(shot.durationMs)}</span>
          {shot.characterNames.length > 0 && <span>· {shot.characterNames.join('、')}</span>}
        </div>
      </button>

      <div className="flex flex-col justify-between gap-2">
        <div className="flex items-center justify-end gap-1.5 text-[11px] text-muted">
          {shot.video.ready ? (
            <>
              {shot.video.stale ? (
                <Badge tone="warning">视频陈旧</Badge>
              ) : (
                <Badge tone="success">有视频</Badge>
              )}
            </>
          ) : (
            <Badge tone="muted">无视频</Badge>
          )}
          {shot.video.state !== 'idle' && <TaskBadge state={shot.video.state} />}
        </div>

        <div className="flex justify-end gap-1.5">
          {shot.status === 'draft' ? (
            <Button size="sm" variant="outline" onClick={() => confirmShot(shot.id)}>
              确认
            </Button>
          ) : null}
          <Button
            size="sm"
            variant={shot.images.length === 0 ? 'primary' : 'outline'}
            onClick={() => generateImage(shot.id)}
            disabled={busy || shot.status !== 'confirmed'}
          >
            {shot.images.length === 0 ? '生成分镜图' : '再生成一版'}
          </Button>
        </div>
      </div>
    </div>
  );
}

export function StoryboardTab({ episodeId, projectId }: { episodeId: string; projectId: string }) {
  const shots = useEpisodeShots(episodeId);
  const project = useProject(projectId);
  const { confirmAll } = useStore();
  const navigate = useNavigate();
  const [filter, setFilter] = useState<Filter>('all');
  const [activeId, setActiveId] = useState<string | null>(null);
  const [preview, setPreview] = useState<Shot | null>(null);

  const episode = project?.episodes.find((e) => e.id === episodeId);

  const filtered = useMemo(() => {
    if (filter === 'todo') return shots.filter((s) => s.images.length === 0);
    if (filter === 'stale') return shots.filter((s) => s.video.stale);
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
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5">
            {(
              [
                ['all', '全部'],
                ['todo', '待出图'],
                ['stale', '视频陈旧'],
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
          <Button size="sm" variant="outline" onClick={() => confirmAll(episodeId)} disabled={draftCount === 0}>
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

      <div className="flex-1 space-y-2 overflow-y-auto scroll-thin p-6">
        {filtered.length === 0 ? (
          <EmptyState title="没有符合条件的镜头" description="切换筛选条件试试。" />
        ) : (
          filtered.map((s) => (
            <ShotRow
              key={s.id}
              shot={s}
              code={shotCode(s)}
              active={activeId === s.id}
              onOpenEditor={() => setActiveId(s.id)}
              onOpenPreview={() => setPreview(s)}
            />
          ))
        )}
      </div>

      <ShotEditor shot={active} episode={episode} onClose={() => setActiveId(null)} />
      <ShotPreview shot={preview} episode={episode} onClose={() => setPreview(null)} />
    </div>
  );
}
