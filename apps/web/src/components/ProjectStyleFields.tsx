import { useState } from 'react';
import { VisualTypeCard } from '@/components/VisualTypeCard';
import { readStyleImage } from '@/lib/image';
import { useStore } from '@/lib/store';
import {
  DEFAULT_VISUAL_TYPE,
  presetsOf,
  resolveStyle,
  visualCategories,
  type VisualCategory,
  type VisualPreset,
} from '@/lib/visualPresets';

export function ProjectStylePicker({
  visualType, setVisualType, currentPreset, allowCustom = true, allowRemove = false,
}: {
  visualType: string;
  setVisualType: (id: string) => void;
  currentPreset?: VisualPreset;
  allowCustom?: boolean;
  allowRemove?: boolean;
}) {
  const { customStyles, addCustomStyle, removeCustomStyle } = useStore();
  const availableStyles = currentPreset?.custom && !customStyles.some(style => style.id === currentPreset.id)
    ? [...customStyles, currentPreset] : customStyles;
  const chosen = resolveStyle(visualType, availableStyles);
  const [category, setCategory] = useState<VisualCategory>(chosen?.category ?? 'live');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState('');
  const categories = allowCustom ? visualCategories : visualCategories.filter(item => item.id !== 'custom');
  const shown = presetsOf(category, availableStyles);

  const uploadStyle = async (file: File) => {
    setUploadError('');
    setUploading(true);
    try {
      const image = await readStyleImage(file);
      const id = await addCustomStyle({ label: file.name.replace(/\.[^.]+$/, ''), image });
      setVisualType(id);
    } catch (err) {
      setUploadError(err instanceof Error ? err.message : '图片处理失败');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted">画面风格</p>
        <p className="text-[11px] text-muted/70">
          已选 <span className="text-fg/85">{chosen?.label ?? visualType}</span>
        </p>
      </div>
      <div role="tablist" aria-label="风格分类" className="mt-2 flex flex-wrap gap-1">
        {categories.map((c) => (
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
              {presetsOf(c.id, availableStyles).length}
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
            {p.custom && allowRemove && customStyles.some(style => style.id === p.id) && (
              <button
                type="button"
                aria-label={`删除自定义风格 ${p.label}`}
                onClick={() => {
                  removeCustomStyle(p.id);
                  if (visualType === p.id) setVisualType(DEFAULT_VISUAL_TYPE);
                }}
                className="absolute -right-1.5 -top-1.5 hidden h-5 w-5 items-center justify-center rounded-full border border-stroke/40 bg-canvas text-[10px] text-muted hover:text-error group-hover/card:flex"
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
            disabled={uploading}
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
      {category === 'custom' && availableStyles.length === 0 && (
        <p className="mt-2 text-[11px] leading-5 text-muted/70">
          上传一张你喜欢的画面作为风格参考。当前生成使用风格名称与描述，参考图随条目保存。
        </p>
      )}
    </div>
  );
}

export function ProjectStyleDescription({ value, onChange }: { value: string; onChange: (style: string) => void }) {
  return (
    <label className="block text-xs text-muted">
      风格描述（可选）
      <textarea
        className="field mt-2 min-h-20 resize-y"
        maxLength={4000}
        placeholder="补充这个风格的具体要求，例如：冷色调、低饱和、35mm 胶片颗粒、夜景为主"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}
