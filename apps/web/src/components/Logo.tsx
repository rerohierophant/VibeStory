import { useId } from 'react';

export const brandMarkPath = 'M23 29 H38 L48 73 L58 29 H73';

/**
 * VibeStory 品牌标记。
 *
 * 图形是一条线同时承担「分镜框顶边」与「字母 V」两个角色：
 * 顶边在中段断开，两侧向下折成 V。缩到 16px 时横臂退化，只剩 V。
 *
 * 渐变与 design/tokens.css 的 --vibe-gradient-from / --vibe-gradient-to 同源；
 * 独立 SVG 版本见 design/assets/brand/，改动时两边一起改。
 */
export function LogoMark({
  size = 36,
  className = '',
  title = 'VibeStory',
}: {
  size?: number;
  className?: string;
  title?: string;
}) {
  const uid = useId().replace(/:/g, '');
  const gradId = `vs-grad-${uid}`;
  const sheenId = `vs-sheen-${uid}`;

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 96 96"
      role="img"
      aria-label={title}
      className={`shrink-0 ${className}`}
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#d946ef" />
          <stop offset="1" stopColor="#7c3aed" />
        </linearGradient>
        <linearGradient id={sheenId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.22" />
          <stop offset="0.55" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect width="96" height="96" rx="24" fill={`url(#${gradId})`} />
      <rect width="96" height="96" rx="24" fill={`url(#${sheenId})`} />
      <path
        d={brandMarkPath}
        fill="none"
        stroke="#ffffff"
        strokeWidth={7.5}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/**
 * 横版锁定：标记 + 字标。
 * 字标用 Audiowide（--vibe-font-display），与 design/assets/brand/lockup.svg 的轮廓版保持同一比例。
 */
export function Logo({
  size = 32,
  className = '',
  wordmarkClassName = 'text-[15px]',
  showWordmark = true,
}: {
  size?: number;
  className?: string;
  wordmarkClassName?: string;
  showWordmark?: boolean;
}) {
  return (
    <span className={`inline-flex items-center gap-2.5 ${className}`}>
      <LogoMark size={size} />
      {showWordmark && (
        <span className={`font-display leading-none tracking-[0.16em] text-fg ${wordmarkClassName}`}>
          VIBESTORY
        </span>
      )}
    </span>
  );
}
