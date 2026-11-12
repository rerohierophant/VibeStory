# 设计系统与品牌

## 主题与页面

工作台通过 CSS 导入设计令牌，并用 Tailwind 的 `@theme inline` 映射颜色、圆角和展示字体。图表或 Canvas 等 JavaScript 场景可以复用同一套主题导出。修改共享值时同步两份定义。 [CSS 令牌](tokens.css) · [JS 主题](theme.js) · [工作台样式入口](../apps/web/src/styles/index.css)

新增界面沿用现有的文字、背景和状态色。主渐变留给当前页面的主要确认操作，列表和空状态的次要入口使用描边按钮。正文使用 PingFang SC 或系统字体，Audiowide 用于品牌展示。页面尺寸和交互以实际组件为准。 [页面组件](../apps/web/src/pages/) · [工作区组件](../apps/web/src/features/) · [创作流程](../docs/workflow.md)

## 品牌标记

标记以断开的分镜框顶边组成字母 V，使用白色描边和 `#d946ef` 到 `#7c3aed` 的固定渐变。保留原有方向、长宽比和圆角底板，四周留白。 [品牌标记 SVG](assets/brand/mark.svg)

| 场景 | 用法 |
| --- | --- |
| 登录页与侧栏 | React 组件组合标记和 Audiowide 字标，登录页桌面版用 40px，侧栏用 30px。 [Logo 组件](../apps/web/src/components/Logo.tsx) |
| 独立展示 | 使用 96×96 的标记或 328×96 的横版组合。横版的字标已转为轮廓。 [横版 SVG](assets/brand/lockup.svg) |
| 浏览器标签页 | 使用针对 16–32px 加粗笔画的图标。 [favicon](../apps/web/public/favicon.svg) |

Audiowide 来自 Google Fonts，遵循 SIL Open Font License 1.1，分发时保留许可说明。 [本地字体文件](assets/fonts/Audiowide-Regular.ttf)

## 画面风格与参考图

内置风格的名称、分类、稳定 ID、图片和卡片取景位置由前端预设定义。风格图片存放在 `apps/web/public/style/`，Vite 通过 `/style/` 提供预览。修改预设后，从仓库根运行 `node scripts/sync-visual-presets.cjs` 同步后端目录。 [前端预设](../apps/web/src/lib/visualPresets.ts) · [本地参考图](../apps/web/public/style/) · [同步脚本](../scripts/sync-visual-presets.cjs)

参考图用于原型的风格选择卡片。逐图 Pin、上传者、原始文件信息和许可核验状态保存在素材目录。 [素材来源与署名](assets/STYLE_SOURCES.md) · [原始采集记录](assets/style-references.json)
