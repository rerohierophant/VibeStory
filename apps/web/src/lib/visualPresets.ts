/**
 * 画面风格预设。
 *
 * 图片约定：把风格图放到 `design/assets/images/style/<id>.jpg`（该目录是 Vite 的
 * publicDir），再把对应预设的 `image` 填成 `/style/<id>.jpg` 即可替换占位图；
 * 未填 `image` 时渲染统一的占位卡片，不影响选择与保存。
 */
export type VisualCategory = 'live' | 'comic' | 'stylized' | 'custom';

export interface VisualPreset {
  /** 持久化到项目上的稳定标识，改动会导致旧项目找不到风格。 */
  id: string;
  category: VisualCategory;
  label: string;
  hint: string;
  image?: string;
  /** 占位卡片的配色（底 → 高光），没有素材时用来区分不同风格。 */
  tone?: [string, string];
  /** 用户在弹窗里上传的自定义风格，不可删除的预设为 false。 */
  custom?: boolean;
}

export const visualCategories: { id: VisualCategory; label: string; note: string }[] = [
  { id: 'live', label: '真人剧', note: '真人质感，适合竖屏短剧与影视化改编。' },
  { id: 'comic', label: '漫剧', note: '插画与动画质感，适合漫画、漫剧与虚拟形象。' },
  { id: 'stylized', label: '风格化', note: '强风格的影像语言，适合做视觉方向的实验。' },
  { id: 'custom', label: '自定义', note: '上传一张参考图，按自己的视觉基准生成。' },
];

export const visualPresets: VisualPreset[] = [
  // 真人剧：暖调为主，靠色温与明度区分
  { id: 'realistic', category: 'live', label: '精品短剧', hint: '真人电视剧 · 大师构图', tone: ['#3a3f44', '#8a8f92'] },
  { id: 'gufeng', category: 'live', label: '精品古装', hint: '专业打光 · 电视剧质感', tone: ['#3b2a24', '#a06f4e'] },
  { id: 'zhangyimou', category: 'live', label: '张艺谋风格', hint: '极致用色 · 仪式感', tone: ['#4a1414', '#c0392b'] },
  { id: 'xianxia', category: 'live', label: '古装仙侠', hint: '临江仙 · 美白滤镜', tone: ['#33465a', '#a9c6d8'] },
  { id: 'minguo', category: 'live', label: '民国谍战', hint: '旗袍弄堂 · 冷灰调', tone: ['#2b2f33', '#6b7378'] },
  { id: 'urban', category: 'live', label: '都市言情', hint: '柔光 · 生活质感', tone: ['#3e3a3a', '#c3a9a0'] },
  { id: 'crime', category: 'live', label: '悬疑刑侦', hint: '低照度 · 冷蓝夜戏', tone: ['#141a24', '#3f5a78'] },
  { id: 'war', category: 'live', label: '战争史诗', hint: '尘烟火光 · 高对比', tone: ['#2f2a22', '#a67c3d'] },
  { id: 'retro', category: 'live', label: '年代烟火', hint: '暖褐褪色 · 胶片味', tone: ['#3c2f24', '#b08b5f'] },

  // 漫剧：饱和度与线稿是主要区分点
  { id: 'anime', category: 'comic', label: '日系赛璐璐', hint: '高饱和 · 清晰线稿', tone: ['#2a3560', '#8fb6e8'] },
  { id: 'guoman3d', category: 'comic', label: '国漫三维', hint: '写实材质 · 国风建模', tone: ['#2b2320', '#7d5a46'] },
  { id: 'ink', category: 'comic', label: '水墨古风', hint: '留白 · 淡彩晕染', tone: ['#22262a', '#9aa3aa'] },
  { id: 'american', category: 'comic', label: '美漫硬派', hint: '粗描边 · 强阴影', tone: ['#1c1c22', '#c9a227'] },
  { id: 'shojo', category: 'comic', label: '少女漫', hint: '柔光 · 粉彩', tone: ['#4a3548', '#e0b7cf'] },
  { id: 'pixel', category: 'comic', label: '像素剧场', hint: '8-bit · 复古游戏', tone: ['#1d2b1f', '#5f9e63'] },

  // 风格化：极端调色，方便做视觉方向的对照
  { id: 'film', category: 'stylized', label: '电影胶片', hint: '颗粒感 · 宽画幅', tone: ['#1b1a24', '#6b5a4a'] },
  { id: 'cyber', category: 'stylized', label: '赛博朋克', hint: '霓虹 · 金属冷调', tone: ['#101a2c', '#7b57e0'] },
  { id: 'lineart', category: 'stylized', label: '黑白线描', hint: '单色 · 结构线稿', tone: ['#1a1d21', '#d9dee4'] },
  { id: 'noir', category: 'stylized', label: '黑白默片', hint: '高反差 · 硬光', tone: ['#141414', '#c9c9c9'] },
  { id: 'hk90s', category: 'stylized', label: '港风胶片', hint: '浓郁色 · 夜街霓虹', tone: ['#28132a', '#c2185b'] },
  { id: 'oil', category: 'stylized', label: '油画质感', hint: '笔触 · 伦勃朗光', tone: ['#2a2119', '#8f6b3e'] },
  { id: 'clay', category: 'stylized', label: '定格黏土', hint: '手作 · 微缩布景', tone: ['#33291f', '#c08552'] },
];

export const DEFAULT_VISUAL_TYPE = 'realistic';

export function findVisualPreset(id: string): VisualPreset | undefined {
  return visualPresets.find((p) => p.id === id);
}

/** 内置预设 + 用户上传的自定义风格，按 id 解析成同一种结构。 */
export function resolveStyle(id: string, custom: VisualPreset[] = []): VisualPreset | undefined {
  return findVisualPreset(id) ?? custom.find((p) => p.id === id);
}

export function visualLabel(id: string, custom: VisualPreset[] = []): string {
  return resolveStyle(id, custom)?.label ?? id;
}

export function presetsOf(category: VisualCategory, custom: VisualPreset[] = []): VisualPreset[] {
  if (category === 'custom') return custom;
  return visualPresets.filter((p) => p.category === category);
}
