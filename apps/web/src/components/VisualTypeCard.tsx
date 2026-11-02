import type { ButtonHTMLAttributes } from 'react';
import type { VisualPreset } from '@/lib/visualPresets';

interface SwatchProps {
  preset: VisualPreset;
  ratio: string;
  className?: string;
}

/**
 * 风格预览图：`preset.image` 有值就用真图，否则退回按预设配色生成的占位图。
 * 占位图右上角带「占位」标记，提醒这里还没填素材。
 */
export function StyleSwatch({ preset, ratio, className = '' }: SwatchProps) {
  if (preset.image) {
    return (
      <img
        src={preset.image}
        alt=""
        className={`block w-full object-cover ${className}`}
        style={{ aspectRatio: ratio, objectPosition: preset.imagePosition }}
      />
    );
  }
  const [dark, light] = preset.tone ?? ['#2b3a48', '#7d8b98'];
  const vertical = ratio.includes('3 / 4');
  return (
    <div
      className={`relative w-full overflow-hidden ${className}`}
      style={{
        aspectRatio: ratio,
        background: `radial-gradient(circle at 62% 30%, ${light} 0%, transparent 62%), linear-gradient(${vertical ? 200 : 145}deg, ${dark} 0%, ${light} 58%, ${dark} 100%)`,
      }}
    >
      <div
        className="absolute inset-0 opacity-[0.1]"
        style={{
          backgroundImage:
            'repeating-linear-gradient(0deg, rgba(255,255,255,0.7) 0 1px, transparent 1px 4px)',
        }}
      />
      <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-black/60 to-transparent" />
      <span className="absolute bottom-1.5 left-1.5 rounded bg-black/50 px-1 py-0.5 text-[9px] leading-none text-white/60">
        占位
      </span>
    </div>
  );
}

interface CardProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children' | 'type'> {
  preset: VisualPreset;
  selected: boolean;
}

/** 弹窗里的可选卡片：预览 + 名称 + 一句差异说明。 */
export function VisualTypeCard({ preset, selected, className = '', ...rest }: CardProps) {
  return (
    <button
      {...rest}
      type="button"
      role="radio"
      aria-checked={selected}
      aria-label={`${preset.label}，${preset.hint}`}
      className={`group relative w-full overflow-hidden rounded-card border text-left transition ${
        selected
          ? 'border-accent shadow-[0_0_0_1px_var(--vibe-accent),0_0_24px_rgba(123,87,224,0.28)]'
          : 'border-stroke/25 hover:border-stroke/50'
      } ${className}`}
    >
      <StyleSwatch
        preset={preset}
        ratio="16 / 9"
        className="opacity-90 transition group-hover:opacity-100"
      />
      <span className="block px-2.5 pb-2 pt-2">
        <span className="block truncate text-[11px] font-medium">{preset.label}</span>
        <span className="mt-0.5 block truncate text-[10px] text-muted/80">{preset.hint}</span>
      </span>
      {selected && (
        <span className="absolute right-1.5 top-1.5 flex h-[18px] w-[18px] items-center justify-center rounded-full bg-accent text-[10px] leading-none text-white">
          ✓
        </span>
      )}
    </button>
  );
}
