# VibeStory 产品定义

> 本文以 `apps/web` 的**已实现页面**为准。页面是这一版的规格来源；任何与此前草案冲突的地方，以页面为准，旧表述已在此次修订中删除或改写。改动记录见文末「修订说明」。

## 一句话

AI 短剧的前期制作工作台：以「集」为生产单位，把一集已有的故事文本，变成一套可直接投产的分镜图与视频片段。

## 解决什么问题

单镜头生成工具已经够用，但做一部**多镜头连贯**的短剧时，四件事会持续烧钱和时间：

1. **一致性靠运气**：每张图、每段视频都是独立采样，角色和场景会漂移。单镜头工具只有 prompt，没有身份。
2. **改一处坏一片**：调整一个镜头，为了接戏不得不重做相邻镜头；已确认的成果没有保护。
3. **长任务会失败**：生成要等几分钟，会超时、会失败、会被重复提交，刷新后状态丢失。
4. **剧本没有结构**：一段文本无法被逐镜审阅和局部修改，只能整体重做。

因此产品的核心不是「生成」，而是**把生成结果组织成可累积、可局部返工、可追溯的创作状态**。

## 定位（明确收窄）

- **做**：叙事类 AI 视频的**前期与生产过程管理**——分集剧本、故事拆解、分镜图、图生视频、资产版本、任务追踪。
- **不做**：成片剪辑、调色、混音、字幕、数字人口播、模板化批量投放、多人协作与权限、移动端。
- **不承诺**：模型层面的角色一致性；生成一次即达可用。

产品主张一句可验证的话：

> 不承诺让模型更一致，承诺**画面不一致时，修起来更便宜**。

## 产品还是原型

**本仓库交付的是原型，不是可运营的产品。** 目标是证明后端、Agent 与全栈工程能力，因此主动放弃产品必需、但对证明能力没有增益的部分：

| 维度 | 原型 | 实际产品 |
| --- | --- | --- |
| 用户 | 单租户，本人访问本人资源 | 多租户、组织、角色、共享 |
| 供应商 | 1 个 Mock + 最多 1 家真实 | 多家矩阵、能力降级 |
| 计费 | 只记用量与估算 | 扣款、结算、发票 |
| 部署 | 本地 Compose 演示 | 自托管或 SaaS，可升级 |
| 运维 | 日志与基础指标 | 告警、SLA、值班 |
| 合规 | 无 | 隐私、审核、数据保留 |

原型**必须做扎实**的是任务状态机与幂等、租约与崩溃恢复、作用域隔离、版本与陈旧判定、Provider 抽象、可复现演示。这六条是简历结论的依据，不能因为「只是原型」而简化。

完整的边界清单与表述纪律见 [tech-design.md §16](tech-design.md)。

## 用户

单一角色 **创作者**：写分集剧本、定角色、拆镜、生成、自己确认。不设审片人、不设权限模型。

## 核心概念

**集（Episode）是生产单位，不是镜头的分组字段。** 这是页面结构定下的一等事实：

- 一集有自己的标题、一句话梗概、正文（剧本）和拆解状态。
- 镜头、分镜图、视频、确认状态全部挂在集下面，不跨集共享。
- 角色与场景是**项目级**的字典，跨集复用；集与集之间只共享资产，不共享镜头。

```
项目（Project）
├── 分集（Episode）① ── 正文 → 拆解 → 镜头 → 分镜图 → 视频
├── 分集（Episode）② ── 同上
├── 角色库（Character，项目级，带版本）
└── 场景库（Scene，项目级，带版本）
```

## 核心链路（单集内）

```
粘贴 / 上传这一集的剧本
  → AI 拆解为镜头清单（画面描述 / 场景 / 景别 / 运镜 / 台词 / 时长）
  → 逐镜确认（未确认不能生成）
  → 文生图：逐镜生成分镜图，自动带上角色与场景的当前基准版本
  → 选定首帧 → 图生视频
  → 全镜确认即这一集完成（可随时取消单镜确认）
```

链路的关键性质是**产物逐级成为下级输入**：

- 角色基准图 + 场景基准图 → 决定分镜图画的是谁、在哪；不是 prompt 里再描述一遍。
- 选定的分镜图 → 成为视频生成的首帧；图变了，视频必须显式失效。

一致性由这条引用链保证，而不是由某一句 prompt 保证。

## 信息架构（以页面为准）

```
/login                          账号密码登录（原型：任意合法邮箱 + ≥6 位密码）

/                               工作台外壳：280px 左栏
├── 我的项目                     项目卡片网格 + 虚线「新建项目」卡片
├── /assets                     全局资产库：跨项目汇总角色 / 场景，两个 tab
└── /settings                   模型提供方配置（Mock / OpenAI / 兼容接口）

/projects/:id                   项目内布局：280px 左栏
├── （index）项目概览             统计卡 + 分集列表 + 角色 / 场景摘要
├── episodes                    分集卡片网格（封面拼图、进度条、集名编辑）
├── episodes/:episodeId/story         工作区 Tab 1：故事（剧本正文 + 拆解）
├── episodes/:episodeId/storyboard    工作区 Tab 2：分镜（镜头卡片 + 出图）
├── episodes/:episodeId/video         工作区 Tab 3：视频（已确认镜头出片）
├── characters                  项目角色库
└── scenes                      项目场景库
```

三条约束：

- **工作区三个 Tab 是一条流水线**：`故事 → 分镜 → 视频`，按产物顺序排列。没有镜头时后两个 Tab 禁用（不是隐藏），点击会回到「故事」。
- **资产库有两个入口、一份数据**：项目内的 `/projects/:id/characters|scenes` 只显示本项目资产；`/assets` 是跨项目汇总，用来集中换基准图。二者共用同一个资产行组件。
- **概览页是导航与统计，不是第四步**：它只回答「这个项目做到哪了」，不承载编辑。

## 用户使用路径

1. **登录** —— 邮箱 + 密码；原型只做邮箱格式与最小长度校验，演示账号预填。所有资源按用户作用域隔离。
2. **项目列表** —— 空态引导「新建项目」；有项目时提供名称搜索、每卡悬浮「重命名 / 删除」。卡片展示画面类型、风格、镜头数、更新时间；封面由画面类型与项目名生成的占位图承担。
3. **新建项目** —— 项目名 + 画面类型（电影 / 动漫 / 古风 / 写实 / 科幻 / 线描）+ 风格描述。右侧说明「创建后会发生什么」。
4. **项目概览** —— 四个统计：分集数、镜头数（含已确认）、已选定分镜图数、已出片数。下方是分集列表与角色 / 场景摘要。
5. **分集** —— 卡片网格：封面由这一集前三个已选首帧拼成；显示拆解状态、镜头数、已确认数、视频数、确认进度条；可改集名。空态引导建第一集。
6. **故事（工作区 Tab 1）** —— 左侧是这一集正文的大编辑区，可粘贴或上传 `.txt` / `.md`，底部显示字数与「已拆出 N 个镜头」；右侧是集名、梗概、拆解状态与集进度五栏。点「AI 拆解镜头」把正文拆成镜头；已有镜头时按钮变为「重新拆解」，并提示旧镜头会被覆盖。
7. **分镜（工作区 Tab 2）** —— 一行一镜的**卡片列表**（缩略图 112px + 画面描述/台词/元信息 + 视频状态与操作）。筛选：全部 / 待出图 / 视频陈旧 / 待确认；头部展示「N 镜 · M 待出图 · K 待确认」，提供「全部确认」与「去生成视频」。点行内文字打开右侧 400px 编辑抽屉；点缩略图打开预览弹窗。
8. **视频（工作区 Tab 3）** —— 只列**已确认**镜头，一卡一段视频。筛选：全部 / 已生成 / 未生成 / 失败 / 陈旧（带计数）；头部展示「已出片 x/y · 总时长」。缺首帧或缺确认时，按钮直接写出原因而不是静默置灰。
9. **资产库** —— 拆解后角色与场景自动进入项目资产库；每个资产有版本历史，当前选定的一版即**基准形象**。换基准版本会让依赖它的既有分镜进入「基于旧版本」状态。
10. **设置** —— 选择 Mock / OpenAI / 兼容接口，填写接口地址、Token、图片模型、视频模型。原型阶段只保存在本机浏览器，不发起真实调用。

## 功能模块

| 模块 | 内容 | 状态 |
| --- | --- | --- |
| 账号 | 登录、登出、当前用户；注册留作种子数据 | 页面已实现（本地） |
| 项目 | 列表、搜索、新建、重命名、删除、概览统计 | 页面已实现（本地） |
| 分集 | 新建、改集名、改梗概、正文编辑、状态与进度 | 页面已实现（本地） |
| 故事文本 | 粘贴、`.txt` / `.md` 上传、替换提示 | 页面已实现（本地） |
| 拆解 | Mock 拆镜、重新拆解、镜头清单生成 | 页面已实现（本地） |
| 镜头 | 编辑（描述 / 台词 / 景别 / 运镜 / 场景 / 时长）、单镜确认、整集确认 | 页面已实现（本地） |
| 分镜表 | 卡片列表、四种筛选、编辑抽屉、预览弹窗 | 页面已实现（本地） |
| 分镜图 | 生成候选、候选条、选定当前图、重生成 | 页面已实现（本地） |
| 视频 | 已确认镜头出片、五种筛选、失败提示、重试、陈旧提示、占位播放器 | 页面已实现（本地） |
| 资产库 | 项目内角色库 / 场景库、全局资产库、版本历史、换基准 | 页面已实现（本地） |
| 设置 | 提供方与模型名配置 | 页面已实现（本地） |
| 任务追踪 | 排队 / 执行中 / 失败 / 结果未知；重试与幂等 | 页面已用 Mock 时序表达，后端待实现 |
| 版本链 | 资产版本、首帧来源、图变则视频陈旧 | 页面已用本地状态表达，后端待实现 |
| 手工补镜 / 删镜 / 拖拽排序 | 镜头增删与排序 | **本期不做**（页面未提供） |
| 导出 | 分镜包 zip + 汇总表 | **本期不做**（页面未提供） |
| 用量 | 每镜费用、项目累计、每日额度 | **本期不做**（页面未提供） |

> 「本期不做」不是暂缓，而是从前端规格里删除了入口。后端不应为它们预留表结构或接口，避免再次出现「后端做了但前端看不到」。

## 核心状态设计

镜头是最小工作单元。**四条独立生命周期**，用「确认」与「陈旧」连接：

```
拆解      idle → running → done           （done 的判据是这一集已有镜头）
镜头      draft → confirmed → draft       （未确认不能提交生成；可取消确认）
生成      idle → queued → running → idle  （成功即回到 idle，产物本身说明成功）
                               └→ failed  （外部结果未知时，UI 文案注明「结果未知」）
分镜图    无 → 生成中 → 有候选（第一张完成即自动选定，之后需手动切）
视频      idle → queued → running → idle  （有视频）
                               └→ failed → 重试（attempts +1）
陈旧      视频的 basedOn ≠ 当前 selectedImageId
```

规则：

- **只有「待确认 / 已确认」两种镜头状态**。没有独立的审片环节，也不叠加「已通过」徽标；整集所有镜头确认即视为这一集做完。
- **成功不靠状态字段**：任务完成后回到 `idle`，是否「有图 / 有视频」由产物是否存在表达。这样切换候选图不会把一次成功的历史抹掉。
- **第一张图自动选定**：首个候选完成即成为当前分镜图，用户之后可以手动切换。这是为了避免「有图但没选中」的空档。
- **陈旧是派生值**：`视频.basedOn ≠ 当前选中分镜图` 即陈旧。切回原来的图，陈旧自动解除；旧的视频记录不删除。
- **确认可撤销**：已确认镜头仍可编辑、切图、重做视频；要退回待确认状态点「取消确认」，不锁字段。
- **失败与未知分开表述**：可重试的失败显示「重试」；供应商超时这类「外部可能已执行」的情况，文案必须写明结果未知，由用户确认后再重试。
- **任务与尝试分离**：一个业务任务可有多条尝试；重试新增尝试，不新建任务。视频卡上的「尝试 N 次」读的就是尝试计数。
- **同一镜头 + 同一输入签名重复提交** → 复用未完成的任务；用户点「重新生成 / 重做」才算新任务。

## 数据模型（以页面状态为准）

页面状态是这一版的字段事实。后端表结构应能无损承载它，以下为映射后的最小集：

```
user              id, email, password_hash, created_at

project           id, user_id, name, visual_type, style_prompt, created_at, updated_at
                  -- 封面不建字段：由 visual_type + name 派生占位图

episode           id, project_id, index, title, summary, story_content,
                  breakdown_state(idle|running|done), created_at, updated_at
                  -- index 在项目内唯一；新建时取当前最大 index + 1

shot              id, project_id, episode_id, seq, scene_name, shot_size, camera_move,
                  description, dialogue, duration_ms, status(draft|confirmed),
                  created_at
                  -- 唯一约束 (episode_id, seq)；seq 在集内从 1 递增

shot_image        id, shot_id, label, storage_key, status, selected, task_id,
                  input_snapshot(json), stale_reason, created_at
                  -- selected=true 在 shot 内部分唯一

video             id, shot_id, source_image_id, source_image_version, storage_key,
                  status, attempt_count, error_message, task_id, created_at
                  -- 首帧版本决定陈旧，见下

character         id, project_id, name, description
scene_asset       id, project_id, name, description
asset_version     id, owner_type(character|scene), owner_id, label,
                  storage_key, source(uploaded|generated), is_selected, created_at
asset_ref         id, shot_id, role(character|scene), asset_id

task              id, user_id, project_id, type(breakdown|image|video),
                  target_type, target_id, status, input_signature, lease_owner, lease_until,
                  created_at, finished_at
task_attempt      id, task_id, attempt_no, provider, status, error_code, error_message,
                  cost_cents, started_at, finished_at
```

四条不可省的约束：

- **`episode.story_content` 是拆解的唯一输入**。不存在项目级「故事文本」对象；项目里的文本被拆到每一集。
- **`shot_image.input_snapshot` 记录生成时引用了哪些资产版本**，而不是只存角色 ID；否则换了形象后无法判断哪些图要重做。
- **`video.source_image_version` 记录首帧图版本**；图换版即可判定视频陈旧。页面上的 `basedOn` 就是这个字段的投影。
- **`task.input_signature` 为输入参数的规范化哈希**，用于幂等复用。

页面用 `sceneName` 与 `characterNames` 引用资产，是因为拆解结果可能提到尚未建卡的名字。后端用 `asset_ref` + 名称解析落库：能对上 `(project_id, name)` 的写入引用，对不上的保留名字并在下一次拆解时补建卡片。**不要把名称当作外键。**

> 分镜图表名用 `shot_image`，避免与保留字 `image` 冲突。

## API 草案

以 springdoc 生成的 OpenAPI 为准，前端类型由它生成。端点按页面调用顺序排列：

```
POST   /api/auth/login
POST   /api/auth/logout
GET    /api/me

GET    /api/projects
POST   /api/projects
GET    /api/projects/{id}              # 概览统计在响应里附带，或 GET /overview
PATCH  /api/projects/{id}
DELETE /api/projects/{id}

GET    /api/projects/{id}/episodes
POST   /api/projects/{id}/episodes
PATCH  /api/episodes/{id}              # title / summary
PUT    /api/episodes/{id}/story        # 正文，整体替换
POST   /api/episodes/{id}/breakdowns   -> taskId
GET    /api/episodes/{id}/breakdowns/latest

GET    /api/episodes/{id}/shots?filter=all|todo|stale|draft
PATCH  /api/shots/{id}
PUT    /api/shots/{id}/status          # { status: draft | confirmed }，取消确认就是写回 draft
POST   /api/episodes/{id}/shots/confirm-all

GET    /api/projects/{id}/characters
GET    /api/projects/{id}/scenes
POST   /api/characters/{id}/versions
POST   /api/scenes/{id}/versions
PUT    /api/characters/{id}/selected-version
PUT    /api/scenes/{id}/selected-version
GET    /api/assets                       # 全局资产库（跨项目）

POST   /api/shots/{id}/image-tasks
PUT    /api/shots/{id}/selected-image
POST   /api/shots/{id}/video-tasks
GET    /api/episodes/{id}/shots?taskActive=true   # 轮询收敛点

GET    /api/settings
PUT    /api/settings
```

约定：

- 所有写接口从登录态取 `user_id`；路径资源必须校验归属，越权一律 404（不暴露存在性）。集与镜头还要校验 `episode.project_id == path.project_id`。
- 生成类接口返回 `202` 与 `taskId`，前端**按集轮询** `GET /api/episodes/{id}/shots?taskActive=true`，不逐镜发请求。
- 设置里的 Token 只存本机、只回显掩码；后端不落明文日志。

## 技术方案

| 层 | 选型 |
| --- | --- |
| 前端 | React 19 + TypeScript + Vite + Tailwind CSS v4；主题来自 `design/theme.js` |
| 后端 | Java 21 + Spring Boot 3 + Gradle，package-by-feature |
| 持久化 | PostgreSQL 16 + Flyway；简单 CRUD 用 Spring Data JPA，复杂查询用 jOOQ |
| 任务驱动 | 数据库任务表 + 乐观锁租约轮询，不引入 Redis / Kafka |
| 存储 | 本地目录 + `StorageService` 抽象；真实环境可替换为对象存储 |
| 模型 | `TextProvider` / `ImageProvider` / `VideoProvider` 接口，Mock 与真实实现并存 |
| 契约 | springdoc OpenAPI → 前端生成类型 |

Provider 抽象是本项目最关键的一层：所有生成类调用都必须经过适配器，测试和日常开发默认 Mock。Mock 按 `input_signature` 生成确定性输出（固定占位图、固定时长视频），保证同一输入可复现。

## 实现顺序

不按日历排期，按**依赖关系**排序：每一步都建立在上一阶段可运行的产物之上，每个阶段结束时都有可演示的东西。真实日历时间由投入强度决定，文档不预设。

详细拆分与验收条件见 [roadmap.md](roadmap.md)。

| 序 | 阶段 | 完成标志 |
| --- | --- | --- |
| 1 | 后端骨架与最小纵向链路 | 用 HTTP 客户端走通建项目 → 建集 → Mock 视频任务 → Worker 执行 → 查结果 |
| 2 | 账号、项目与分集 | 登录、项目 CRUD、建集、正文保存，刷新不丢 |
| 3 | 拆解与镜头清单 | 拆解任务产出镜头；确认状态可写回 |
| 4 | 资产库与分镜图 | 角色 / 场景版本参与生成；单镜可独立重做 |
| 5 | 图生视频与陈旧标记 | 选定分镜图作首帧；换图后旧视频显式失效 |
| 6 | 前端联调与观测 | 页面接真实 API；关键故障路径有测试 |

原则：**先把一条链路走通，再往两侧扩功能**。任何阶段都不允许出现“后端做了但前端看不到”或反过来的悬空状态。

## 风险与边界

| 风险 | 应对 |
| --- | --- |
| 真实模型接入成本与不稳定 | 一律走 Provider 适配器，默认 Mock；真实接入作为可选开关 |
| 角色一致性达不到预期 | 不承诺模型能力；用引用链 + 局部返工降低代价，并在文档中如实描述 |
| 范围蔓延 | 前端未提供入口的功能不进后端；每个阶段必须可演示 |
| 页面与文档再次漂移 | 页面是本版规格来源；改页面时同步改本文对应章节 |
| 单机存储不满足多实例 | 存储抽象已就位，替换对象存储不触碰业务代码 |

## 明确不做（本版本）

成片剪辑、调色、混音、字幕轨、数字人、模板化批量生产、多人协作与 RBAC、移动端、计费与支付。

另外，**以下功能已从前端规格中移除，因此本期后端也不实现**：

- 镜头的手工补镜、删除、拖拽排序。
- 分镜包导出。
- 用量 / 费用统计与每日额度。
- 项目封面图上传（封面由画面类型派生）。

## 修订说明（本次对齐）

以 `apps/web` 为准，改掉了以下旧表述：

| 旧表述 | 现表述 | 原因 |
| --- | --- | --- |
| 集是镜头的可选分组字段（`episode_index`） | 集是独立对象，拥有正文、拆解状态与进度 | 页面有分集列表、集封面与集级工作区 |
| 项目级 `story_text` 表 | 正文存在 `episode.story_content` | 「故事」Tab 只编辑当前这一集的正文 |
| 工作台标签含「镜头」 | 三个标签：故事 / 分镜 / 视频 | 页面没有独立的镜头标签 |
| 分镜表是「图文同表」 | 分镜是一行一镜的卡片列表 + 右侧抽屉 | 页面用卡片行 + 抽屉，不是表格 |
| 资产库是流水线外的一个页面 | 项目内角色库 / 场景库 + 全局资产库两个入口 | 页面有两个层级 |
| 导出 / 用量是必做或可选 | 本期不做 | 页面没有入口 |
| 任务状态含 `SUCCEEDED` | `idle/queued/running/failed/unknown`，成功由产物表达 | 页面没有「成功」徽标 |
| 镜头有「确认」+「审看通过」两套状态 | 只保留 `draft / confirmed` 一套，可随时取消确认 | 页面只展示一个状态徽标，不叠加标签 |
