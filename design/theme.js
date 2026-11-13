/**
 * VibeStory 设计令牌（Tailwind CSS v4 主题配置）
 * 由原项目视觉稿整理而来，作为 apps/web 的统一主题来源。
 * Tailwind v4 使用 CSS-first 配置：通过 @theme 暴露这些变量，
 * 同时保留 JS 导出，便于在非 CSS 场景（图表、canvas）复用。
 */

export const colors = {
  'vibe-bg': '#0D0F11',
  'vibe-panel': '#111214',
  'vibe-panel-2': '#262C36',
  'vibe-panel-hover': '#2E3A45',
  'vibe-dash': '#334E68',
  'vibe-stroke': '#576776',
  'vibe-portrait-matte': '#737373',
  'vibe-accent': '#7B57E0',
  'vibe-accent-soft': '#C9B6FF',
  'vibe-accent-ink': '#1B1530',
  'vibe-white': '#FFFFFF',
  'vibe-text': '#E3E3E3',
  'vibe-text-secondary': '#B8C0CC',
  'vibe-text-on-light': '#2B2E48',
  'vibe-success': '#2AA31F',
  'vibe-success-soft': '#8FE084',
  'vibe-warning': '#F5C451',
  'vibe-error': '#F53B30',
  'vibe-error-soft': '#FF9C96',
};

export const breakpoints = {
  sm: '640px',
  md: '768px',
  lg: '1024px',
  xl: '1280px',
  '2xl': '1536px',
  '3xl': '1920px',
};

export const fontSizes = {
  xs: '0.75rem',
  sm: '0.875rem',
  base: '1rem',
  lg: '1.125rem',
  xl: '1.25rem',
  '2xl': '1.375rem',
  '3xl': '1.5rem',
  '4xl': '2rem',
};

export const accentGradient = {
  from: '#c026d3',
  to: '#6d28d9',
  hoverFrom: '#a121cc',
  hoverTo: '#5b21b6',
  glow: 'rgba(192, 38, 211, 0.28)',
};

const theme = { colors, breakpoints, fontSizes, accentGradient };
export default theme;
