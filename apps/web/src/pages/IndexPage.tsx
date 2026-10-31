import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/Button';
import { EmptyState } from '@/components/EmptyState';
import { Modal } from '@/components/Modal';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { StyleSwatch, VisualTypeCard } from '@/components/VisualTypeCard';
import { timeAgo } from '@/lib/id';
import { readStyleImage } from '@/lib/image';
import { useStore, useProjects } from '@/lib/store';
import type { Project, VisualType } from '@/lib/types';
import type { VisualPreset } from '@/lib/visualPresets';
import {
  DEFAULT_VISUAL_TYPE,
  findVisualPreset,
  presetsOf,
  resolveStyle,
  visualCategories,
  visualLabel,
  type VisualCategory,
} from '@/lib/visualPresets';

function ProjectCard({
  project,
  shots,
  customStyles,
  onOpen,
  onRename,
  onDelete,
}: {
  project: Project;
  shots: number;
  customStyles: VisualPreset[];
  onOpen: () => void;
  onRename: () => void;
  onDelete: () => void;
}) {
  const preset = resolveStyle(project.visualType, customStyles);

  return (
    <article className="group relative overflow-hidden rounded-card border border-stroke/20 bg-panel transition hover:border-stroke/40">
      <button type="button" onClick={onOpen} className="block w-full text-left">
        <span className="relative block overflow-hidden">
          {preset ? (
            <StyleSwatch
              preset={preset}
              ratio="465 / 300"
              className="transition duration-500 group-hover:scale-[1.02]"
            />
          ) : (
            <PlaceholderImage
              seed={project.name}
              ratio="465 / 300"
              rounded="rounded-none"
              className="transition duration-500 group-hover:scale-[1.02]"
            />
          )}
          {/* 风格标签压在图上：卡片宽度有限，文字区留给项目名和风格描述 */}
          <span className="absolute left-2 top-2 flex items-center gap-1 rounded-full border border-white/10 bg-black/50 px-2 py-0.5 text-[11px] leading-5 text-white/85 backdrop-blur">
            {visualLabel(project.visualType, customStyles)}
          </span>
        </span>
        <div className="space-y-2 p-4">
          <h3 className="truncate text-[15px] font-medium">{project.name}</h3>
          <p className="truncate text-xs text-muted">{project.style || '未填写风格描述'}</p>
          <div className="flex items-center justify-between text-[11px] text-muted/80">
            <span>{shots} 个镜头</span>
            <span>更新于 {timeAgo(project.updatedAt)}</span>
          </div>
        </div>
      </button>

      <div className="absolute right-3 top-3 flex gap-1 opacity-0 transition group-hover:opacity-100">
        <button
          type="button"
          onClick={onRename}
          className="rounded bg-black/55 px-2 py-1 text-[11px] text-white/85 backdrop-blur hover:bg-black/75"
        >
          重命名
        </button>
        <button
          type="button"
          onClick={onDelete}
          className="rounded bg-black/55 px-2 py-1 text-[11px] text-error-soft backdrop-blur hover:bg-black/75"
        >
          删除
        </button>
      </div>
    </article>
  );
}

function NewProjectModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { createProject, customStyles, addCustomStyle, removeCustomStyle } = useStore();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [visualType, setVisualType] = useState<VisualType>(DEFAULT_VISUAL_TYPE);
  const [category, setCategory] = useState<VisualCategory>('live');
  const [style, setStyle] = useState('');
  const [error, setError] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');

  const shown = presetsOf(category, customStyles);
  const chosen = resolveStyle(visualType, customStyles);

  const uploadStyle = async (file: File) => {
    setUploadError('');
    setUploading(true);
    try {
      const image = await readStyleImage(file);
      const id = addCustomStyle({ label: file.name.replace(/\.[^.]+$/, ''), image });
      setVisualType(id);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : '图片处理失败');
    } finally {
      setUploading(false);
    }
  };

  const submit = () => {
    if (!name.trim()) {
      setError('项目名称不能为空');
      return;
    }
    const id = createProject({ name, visualType, style });
    setName('');
    setStyle('');
    setError('');
    onClose();
    navigate(`/projects/${id}`);
  };

  return (
    <Modal open={open} onClose={onClose} width="max-w-[1120px]" labelledBy="new-project-title">
      <div className="sticky top-0 z-10 rounded-t-modal border-b border-stroke/20 bg-panel/95 px-8 py-6 backdrop-blur">
        <h2 id="new-project-title" className="text-xl font-semibold">
          新建短剧项目
        </h2>
        <p className="mt-1.5 text-sm text-muted">
          先定画面风格；故事文本进入项目后再上传，拆解结果可以随时修改。
        </p>
      </div>

      <div className="space-y-5 px-8 py-6">
        <label className="block text-xs text-muted">
          项目名称
          <input
            className="field mt-2"
            placeholder="例如：长夜将明"
            value={name}
            onChange={(e) => {
              setName(e.target.value);
              setError('');
            }}
            autoFocus
          />
          <span className="mt-1 block h-4 text-[11px] text-error">{error}</span>
        </label>

        <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_300px]">
          <div>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <p className="text-xs text-muted">画面风格</p>
              <p className="text-[11px] text-muted/70">
                已选 <span className="text-fg/85">{chosen?.label ?? visualType}</span>
              </p>
            </div>
            <div role="tablist" aria-label="风格分类" className="mt-2 flex gap-1">
              {visualCategories.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  role="tab"
                  aria-selected={c.id === category}
                  onClick={() => setCategory(c.id)}
                  className={`rounded-card px-3 py-1.5 text-xs transition ${
                    c.id === category
                      ? 'bg-hover/70 text-fg'
                      : 'text-muted hover:bg-hover/40 hover:text-fg'
                  }`}
                >
                  {c.label}
                  <span className="ml-1.5 text-[10px] text-muted/70">
                    {presetsOf(c.id, customStyles).length}
                  </span>
                </button>
              ))}
            </div>
            <p className="mt-1.5 text-[11px] text-muted/70">
              {visualCategories.find((c) => c.id === category)?.note}
            </p>

            <div
              role="radiogroup"
              aria-label="画面风格"
              className="mt-3 grid max-h-[42vh] grid-cols-2 gap-3 overflow-y-auto scroll-thin pr-1 sm:grid-cols-3 xl:grid-cols-4"
            >
              {shown.map((p) => (
                <div key={p.id} className="group/card relative">
                  <VisualTypeCard
                    preset={p}
                    selected={p.id === visualType}
                    onClick={() => setVisualType(p.id)}
                  />
                  {p.custom && (
                    <button
                      type="button"
                      aria-label={`删除自定义风格 ${p.label}`}
                      onClick={() => {
                        removeCustomStyle(p.id);
                        if (visualType === p.id) setVisualType(DEFAULT_VISUAL_TYPE);
                      }}
                      className="absolute -right-1.5 -top-1.5 hidden h-5 w-5 items-center justify-center rounded-full border border-stroke/40 bg-base text-[10px] text-muted hover:text-error group-hover/card:flex"
                    >
                      ×
                    </button>
                  )}
                </div>
              ))}

              {category === 'custom' && (
                <label
                  className={`flex aspect-auto flex-col items-center justify-center gap-1.5 rounded-card border border-dashed border-stroke/40 px-3 py-6 text-center text-[11px] text-muted transition hover:border-accent/60 hover:text-fg ${
                    uploading ? 'pointer-events-none opacity-60' : 'cursor-pointer'
                  }`}
                >
                  <input
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file) void uploadStyle(file);
                      e.target.value = '';
                    }}
                  />
                  <span className="text-lg leading-none">＋</span>
                  <span>{uploading ? '处理中…' : '上传参考图'}</span>
                  <span className="text-[10px] text-muted/60">JPG / PNG，自动压缩</span>
                </label>
              )}
            </div>
            {uploadError && <p className="mt-2 text-[11px] text-error">{uploadError}</p>}
            {category === 'custom' && customStyles.length === 0 && (
              <p className="mt-2 text-[11px] leading-5 text-muted/70">
                上传一张你喜欢的画面作为基准，AI 会按它的光影与色调生成分镜。图片只存在本机浏览器里。
              </p>
            )}
          </div>

          <aside className="rounded-panel border border-stroke/20 bg-black/20 p-5">
            <p className="text-xs font-medium text-muted">创建后会发生什么</p>
            <ol className="mt-4 space-y-3.5 text-xs leading-5 text-muted">
              {[
                ['上传故事文本', '粘贴或上传 .txt / .md，原文会保留。'],
                ['AI 拆解镜头', '按集拆成镜头清单，逐条可编辑，确认后才进入生产。'],
                ['资产定妆', '从台本抽取角色与场景，各自选定一张基准形象。'],
                ['生成分镜', '逐镜文生图，角色与场景的基准图自动参与。'],
                ['出视频', '以选定的分镜图为首帧，生成动态片段。'],
              ].map(([title, desc], i) => (
                <li key={title} className="flex gap-3">
                  <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-stroke/40 text-[10px]">
                    {i + 1}
                  </span>
                  <span>
                    <b className="font-medium text-fg/90">{title}</b>
                    <br />
                    {desc}
                  </span>
                </li>
              ))}
            </ol>
          </aside>
        </div>

        <label className="block text-xs text-muted">
          风格描述（可选）
          <textarea
            className="field mt-2 min-h-20 resize-y"
            placeholder="补充这个风格的具体要求，例如：冷色调、低饱和、35mm 胶片颗粒、夜景为主"
            value={style}
            onChange={(e) => setStyle(e.target.value)}
          />
        </label>
      </div>

      <div className="sticky bottom-0 flex items-center justify-between gap-3 rounded-b-modal border-t border-stroke/20 bg-panel/95 px-8 py-4 backdrop-blur">
        <p className="text-[11px] text-muted/70">创建后仍可修改画面风格，已生成的分镜会标记为陈旧。</p>
        <div className="flex items-center gap-3">
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={submit}>创建项目</Button>
        </div>
      </div>
    </Modal>
  );
}

export function IndexPage() {
  const { renameProject, deleteProject, shots, customStyles } = useStore();
  const navigate = useNavigate();
  const projects = useProjects();
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState<Project | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [deleting, setDeleting] = useState<Project | null>(null);
  const [filter, setFilter] = useState('');
  const [styleFilter, setStyleFilter] = useState<VisualCategory | 'all'>('all');

  const shotCount = useMemo(() => {
    const map = new Map<string, number>();
    shots.forEach((s) => map.set(s.projectId, (map.get(s.projectId) ?? 0) + 1));
    return map;
  }, [shots]);

  const categoryOf = (visualType: VisualType): VisualCategory | undefined =>
    findVisualPreset(visualType)?.category ?? (customStyles.some((p) => p.id === visualType) ? 'custom' : undefined);

  const visible = projects.filter(
    (p) =>
      p.name.toLowerCase().includes(filter.toLowerCase()) &&
      (styleFilter === 'all' || categoryOf(p.visualType) === styleFilter),
  );

  const categoryCount = useMemo(() => {
    const map = new Map<VisualCategory, number>();
    for (const p of projects) {
      const c = categoryOf(p.visualType);
      if (c) map.set(c, (map.get(c) ?? 0) + 1);
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projects, customStyles]);

  return (
    <div className="flex h-full flex-col">
      <main className="min-h-0 flex-1 overflow-y-auto scroll-thin px-10 py-8">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold">我的项目</h1>
            <p className="mt-1 text-sm text-muted">
              {projects.length === 0
                ? '还没有项目，从一个故事开始。'
                : `共 ${projects.length} 个短剧项目`}
            </p>
          </div>
          {/* 空状态里搜索框和头部按钮都没有意义，只留空状态那一个主入口 */}
          {projects.length > 0 && (
            <div className="flex items-center gap-3">
              <input
                className="field h-10 w-56 py-0 text-sm"
                placeholder="搜索项目名称"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
              />
              <Button variant="outline" className="shrink-0" onClick={() => setCreating(true)}>
                新建项目
              </Button>
            </div>
          )}
        </div>

        {projects.length > 0 && (
          <div role="tablist" aria-label="按风格分类筛选" className="mt-5 flex flex-wrap gap-1">
            {[{ id: 'all' as const, label: '全部' }, ...visualCategories].map((c) => {
              const active = c.id === styleFilter;
              const count = c.id === 'all' ? projects.length : (categoryCount.get(c.id) ?? 0);
              return (
                <button
                  key={c.id}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setStyleFilter(c.id)}
                  className={`rounded-card px-3 py-1.5 text-xs transition ${
                    active ? 'bg-hover/70 text-fg' : 'text-muted hover:bg-hover/40 hover:text-fg'
                  }`}
                >
                  {c.label}
                  <span className="ml-1.5 text-[10px] text-muted/70">{count}</span>
                </button>
              );
            })}
          </div>
        )}

        {visible.length === 0 ? (
          <div className="mt-10">
            <EmptyState
              title={
                projects.length === 0
                  ? '这里还空着'
                  : filter
                    ? '没有匹配的项目'
                    : '这个分类下还没有项目'
              }
              description="新建一个短剧项目，上传故事文本后即可开始拆解镜头。"
              action={
                !filter ? <Button onClick={() => setCreating(true)}>新建项目</Button> : undefined
              }
            />
          </div>
        ) : (
          <div className="mt-8 grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
            {visible.map((p) => (
              <ProjectCard
                key={p.id}
                project={p}
                shots={shotCount.get(p.id) ?? 0}
                customStyles={customStyles}
                onOpen={() => navigate(`/projects/${p.id}`)}
                onRename={() => {
                  setRenaming(p);
                  setRenameValue(p.name);
                }}
                onDelete={() => setDeleting(p)}
              />
            ))}
            <button
              type="button"
              onClick={() => setCreating(true)}
              className="flex min-h-[300px] flex-col items-center justify-center gap-2 rounded-card border border-dashed border-stroke/40 text-muted transition hover:border-accent/60 hover:text-fg"
            >
              <span className="text-2xl leading-none">＋</span>
              <span className="text-sm">新建项目</span>
            </button>
          </div>
        )}
      </main>

      <NewProjectModal open={creating} onClose={() => setCreating(false)} />

      <Modal open={!!renaming} onClose={() => setRenaming(null)} width="max-w-md" labelledBy="rename-title">
        <div className="p-6">
          <h2 id="rename-title" className="text-lg font-medium">
            重命名项目
          </h2>
          <input
            className="field mt-4"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            autoFocus
          />
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setRenaming(null)}>
              取消
            </Button>
            <Button
              onClick={() => {
                if (renaming) renameProject(renaming.id, renameValue);
                setRenaming(null);
              }}
            >
              保存
            </Button>
          </div>
        </div>
      </Modal>

      <Modal open={!!deleting} onClose={() => setDeleting(null)} width="max-w-md" labelledBy="delete-title">
        <div className="p-6">
          <h2 id="delete-title" className="text-lg font-medium">
            删除「{deleting?.name}」？
          </h2>
          <p className="mt-3 text-sm leading-6 text-muted">
            项目下的镜头、资产与生成结果会一并删除，且无法恢复。
          </p>
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="ghost" onClick={() => setDeleting(null)}>
              取消
            </Button>
            <Button
              variant="danger"
              onClick={() => {
                if (deleting) deleteProject(deleting.id);
                setDeleting(null);
              }}
            >
              确认删除
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
