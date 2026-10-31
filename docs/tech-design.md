# 技术方案

对应产品定义见 [product.md](product.md)，阶段与验收见 [roadmap.md](roadmap.md)。本文描述**怎么实现**，不重复功能描述。

> **规格来源**：`apps/web` 的已实现页面是这一版的字段与交互事实。本文中凡与页面冲突的描述，一律以页面为准并已改写；页面没有入口的功能不进后端。与上一版的差异见 §18。

## 1. 总体结构

```
apps/web  ──HTTP──▶  apps/api (Spring Boot 单体)
                        ├─ feature 包（auth / project / episode / shot / asset / task）
                        ├─ provider 适配层（text / image / video）
                        ├─ worker（同进程，租约轮询）
                        └─ storage（本地文件 / S3 兼容）
                     PostgreSQL
                     MinIO（或本地目录）
```

**单体优先**。目录 `apps/agent` 保留为概念边界，第一阶段 Agent 逻辑以 `agent` 包的形式置于 `apps/api` 内，因为拆进程的收益为零、成本是调试和事务复杂度。何时拆出见 §9。

**集是第二个聚合根**。项目下挂分集，分集下挂镜头，镜头下挂分镜图与视频。所有跨层查询都带 `episode_id`，因为页面上的列表、筛选、轮询、统计全部以集为边界。

## 2. 后端分层与包结构

采用 package-by-feature，一个业务聚合一个包，包内不再拆 controller / service / repository / impl 四层：

```
com.vibestory
├── common            # 错误响应、分页、时间、ID 生成、当前用户解析、作用域断言
├── auth
│   ├── AuthController
│   ├── AuthService
│   ├── User
│   └── UserRepository
├── project           # Project、概览统计
├── episode           # Episode、正文保存、拆解状态与集级统计
├── shot              # Shot、ShotImage、Video 及其状态流转
├── asset             # Character、SceneAsset、AssetVersion、AssetRef、名称解析
├── task              # Task、TaskAttempt、TaskRepository、TaskWorker、TaskService
├── provider          # TextProvider、ImageProvider、VideoProvider + Mock/真实实现
├── agent             # 拆解编排、工具定义、prompt 与输出解析
├── settings          # 生成能力的提供方配置（按用户保存）
└── storage           # StorageService 接口 + LocalStorage / S3Storage
```

约束：

- 一个包内的 Entity 不跨包直接引用，跨聚合只通过 ID 与 Service 方法交互。
- 简单 CRUD 用 Spring Data JPA；分镜的多维筛选（状态 + 是否有图 + 是否陈旧 + 任务活跃）用 jOOQ。
- 状态流转集中在 `XxxStateMachine` 或 Service 方法上，不在 Controller 里散落 `if`。
- 作用域断言统一走一个 `ScopeGuard`：`userId → projectId → episodeId → shotId` 逐层校验，任一层不匹配即 404。

## 3. 领域模型与状态机

核心对象与字段见 product.md §数据模型。落地时注意：

- 表名 `shot_image`（`image` 与保留字冲突）。
- `shot` 的唯一键是 `(episode_id, seq)`，不是 `(project_id, episode_index, seq)`；集已经是独立表。
- `shot` 冗余 `project_id`，`shot_image` / `video` 冗余 `episode_id`，用于一次校验归属、一次查询筛全集，避免三连 JOIN。冗余列由 Service 写入，不接受前端传值。
- 所有业务表带 `user_id`（除 `asset_version` / `asset_ref` / `shot_image` / `video` 等通过父级间接归属的），便于统一作用域校验。
- 主键用 `BIGSERIAL`：调试直观、索引小；对外暴露时可再加 `public_id`，但本原型不做，直接内部 ID。
- 镜头上的 `scene_name` 与 `character_names` 来自拆解结果，**不是外键**。落库时由 `asset` 包做名称解析，写 `asset_ref`（见 §3.4）。

### 3.1 拆解状态

```
episode.breakdown: IDLE → RUNNING → DONE
```

- 状态存在 `episode` 上，拆解任务的目标是集。
- `DONE` 的判据是「这一集已有镜头」；页面入口（分镜 / 视频 Tab 是否可用）也读同一个判据，避免「状态说 done 但没有镜头」。
- 修改正文不自动删镜头；把 `DONE` 回退为 `IDLE`，提示用户需要重新拆解。
- 重新拆解在**同一事务**内：删除该集旧镜头（及其分镜图、视频、引用），写入新镜头。旧任务与尝试保留，作为历史。

### 3.2 镜头状态机

```
STATUS: DRAFT ⇄ CONFIRMED
```

规则：

- `DRAFT` 镜头不可提交生成（`409`）。
- 确认可撤销：`CONFIRMED → DRAFT` 是对外公开的接口，页面上的「取消确认」直接调用；不要把它实现成内部方法。
- 页面只有这一个镜头状态，没有独立的审片 / 已通过状态，也不要加第二个徽标。
- 整集「全部确认」是批量入口，逐条走同一状态机，不允许绕过；不提供「整集取消确认」。
- 状态变更必须在事务内同时写 `updated_at`，避免并发覆盖。

### 3.3 分镜图 / 视频状态

```
shot_image.status: QUEUED → RUNNING → READY | FAILED
shot_image.selected:  同一 shot 下只能有一行 selected = true（部分唯一索引保证）
                     第一张 READY 自动置 selected；之后由用户显式切换

video.status:      QUEUED → RUNNING → READY | FAILED
video.stale:       由 selected 图与 source_image_id 的差异决定，写入时同步
```

**成功不靠状态字段表达**。页面的 `TaskState` 只有 `idle / queued / running / failed / unknown`：任务完成后状态回到 `idle`，是否「有图 / 有视频」由 `shot_image.selected` 与 `video.status = READY` 表达。后端 API 对前端做同样投影，不要引入一个前端没有的 `SUCCEEDED` 徽标。

**陈旧判定不依赖定时任务**，在 `selectImage` 事务里直接完成：

```sql
UPDATE video v SET stale = true
WHERE v.shot_id = :shotId
  AND v.status = 'READY'
  AND v.source_image_id <> :imageId;

UPDATE video v SET stale = false
WHERE v.shot_id = :shotId
  AND v.status = 'READY'
  AND v.source_image_id = :imageId;
```

第二条不能省：页面允许用户切回原来的候选图，此时陈旧必须自动解除。`video` 上保留 `stale` 列（不只靠 JOIN 计算），因为分镜页的「视频陈旧」筛选与视频页的计数都需要它，而这两处都是列表查询。

### 3.4 资产名称解析

拆解输出的是名字（`sceneName`、`characterNames`），页面也按名字展示引用关系和使用统计。落库规则：

1. 拆解落镜头时，按 `(project_id, name)` 查 `character` / `scene_asset`；
2. 命中则写 `asset_ref(shot_id, role, asset_id)`；未命中则新建资产卡（`description` 留空），再写引用；
3. 名称在项目内唯一：`unique (project_id, lower(name))`，避免「沈夜」与「 沈夜」并存。

生成分镜图时，`input_snapshot` 记录的是**引用资产当前选定的版本号**，不是名字。名称只用于展示与解析，不参与签名。

### 3.5 取消确认与写保护

- 这一版**没有独立的审看环节**：`confirmed` 只表示「镜头内容定了、可以生成」，不代表冻结。
- 已确认镜头仍可改字段、切候选图、重做视频；要退回未确认状态，调 `PUT /api/shots/{id}/status { "status": "draft" }`。
- 因此服务端不需要为已确认镜头单独做 `409` 写保护；唯一的写保护是 `draft` 镜头不可提交生成。
- 重新拆解会整体重建镜头（含确认状态回到 `draft`），执行前必须二次确认。

## 4. 任务与 Worker

### 4.1 表设计

```sql
CREATE TABLE task (
  id              BIGSERIAL PRIMARY KEY,
  user_id         BIGINT NOT NULL,
  project_id      BIGINT,
  episode_id      BIGINT,                    -- 拆解任务的目标集
  type            VARCHAR(32) NOT NULL,      -- BREAKDOWN | IMAGE | VIDEO
  target_type     VARCHAR(32) NOT NULL,      -- EPISODE | SHOT
  target_id       BIGINT NOT NULL,
  status          VARCHAR(16) NOT NULL,      -- QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED | UNKNOWN
  input_signature CHAR(64) NOT NULL,         -- 输入规范化后的 SHA-256
  lease_owner     VARCHAR(64),
  lease_until     TIMESTAMPTZ,
  version         BIGINT NOT NULL DEFAULT 0,
  error_code      VARCHAR(64),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX uk_task_active
  ON task (user_id, type, target_type, target_id, input_signature)
  WHERE status IN ('QUEUED', 'RUNNING');
```

`task.status` 内部保留 `SUCCEEDED`，前端投影为「产物已就绪、徽标回到 idle」。**两者不矛盾**：数据库需要记录终态，UI 不需要多一个成功徽标。API 层做这一步投影，不把 `SUCCEEDED` 直接吐给页面。

部分唯一索引是幂等的**最后一道防线**：应用层先查再插，索引兜底，并发下既不会重复提交也不会插入失败导致脏数据。

### 4.2 领取任务

用 `FOR UPDATE SKIP LOCKED`，不加 Redis 也能多实例安全：

```sql
UPDATE task SET
  status = 'RUNNING',
  lease_owner = :workerId,
  lease_until = now() + interval '90 seconds',
  version = version + 1
WHERE id = (
  SELECT id FROM task
  WHERE status = 'QUEUED'
     OR (status = 'RUNNING' AND lease_until < now())
  ORDER BY id
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
RETURNING *;
```

- 租约 90 秒，Worker 每 30 秒续约一次，`version` 作为乐观锁。
- 进程崩溃后租约到期，任务被其他实例接手；不依赖优雅关闭。
- **外部调用未知结果**：超时或连接中断时不直接置 `FAILED`，置 `UNKNOWN` 并按供应商能力决定是否可查；能力不支持时，把决策交给用户（“外部可能已执行，重试可能重复计费”）。页面上对应 `TaskState.unknown` 与「结果未知」文案。

### 4.3 Worker 职责边界

```
领取任务 → 加载目标对象快照 → 调 Provider → 写入结果 → 更新状态 → 写 attempt 费用
```

Worker 内**不写业务规则**，只负责“把一次外部调用翻译成一条 attempt + 一个结果”。业务校验（镜头是否已确认）在提交时完成，Worker 不再判断。

生成视频时，Worker 必须把**提交时选定的分镜图**记进 `video.source_image_id`，而不是任务结束时的最新图；否则生成期间用户切图会让陈旧判定失效。

### 4.4 尝试记录

每次外部调用写一行 `task_attempt`（`attempt_no` 递增）。重试不新建 task，因此：

- 费用按 attempt 累计，天然可核对。
- 诊断时能看到第几次尝试失败、错误码是什么。
- 页面视频卡上的「尝试 N 次」直读 `video.attempt_count`（由 attempt 汇总投影）。

### 4.5 页面上的时间感

页面用 `setTimeout` 模拟：拆解 1.5 秒、分镜图 0.5 秒进入 running、2.2 秒出候选、视频 0.6 秒进入 running、3 秒出结果并带 18% 失败率。接入后端后，这些延迟换成 Worker 的真实执行时间，前端改为**按集轮询**：

```
GET /api/episodes/{id}/shots?taskActive=true   # 3 秒一次，页面失焦停止
```

一个集一次请求，返回该集全部镜头 + 进行中的任务摘要，不逐镜发请求。

## 5. Provider 适配

```java
public interface ImageProvider {
    ImageResult generate(ImageRequest request);   // 阻塞，由 Worker 线程调用
    ProviderCapabilities capabilities();
}

public interface VideoProvider {
    VideoResult generate(VideoRequest request);
    Optional<VideoResult> poll(String externalId);  // 不支持则为 empty
    ProviderCapabilities capabilities();
}
```

- `capabilities()` 显式声明：是否支持查询、是否支持幂等键、最长时长、最大分辨率、单次费用估算。
- **所有能力判断走 capabilities，不靠代码里散落 `if (provider.equals(...))`。**
- Mock 实现遵循同一套接口，按 `input_signature` 生成确定性输出：固定占位图、固定 3 秒视频、可配置失败率。这让测试可断言。
- 真实实现用 `@ConditionalOnProperty(provider.image=mock|real)` 切换，默认 mock。
- 设置页的四种提供方（`mock / openai / compatible`，图片与视频模型名分开）映射为配置项；**Token 由前端保存在本机，后端只接受管理通道写入**，不在 API 响应中回显。

无外部调用时就地合成媒体，Mock 不联网：

```
src/main/resources/mock/
├── image-base.png     # 叠加文字渲染成不同镜头图
└── clip-3s.mp4        # 固定占位视频
```

页面当前用 `PlaceholderImage` 组件按 seed 画渐变占位图，接入后端后替换为 `<img src={url}>`，其余布局不动。

## 6. 幂等、重试与四种情况

| 情况 | 判定 | 行为 |
| --- | --- | --- |
| 重复提交 | 同 `target` + 同 `input_signature` 且任务活跃 | 复用原 task，返回 `202` + 同 taskId |
| 主动重新生成 | 用户点「重新生成 / 再生成一版 / 重做」 | 生成新 `input_signature`（含 `regenerate_nonce`），新建 task |
| 可重试失败 | `FAILED` 且 `error_code` 在可重试集合 | 新建 attempt，复用 task，不额外计费门槛 |
| 外部结果未知 | 超时 / 连接中断 / 供应商 5xx 无明确语义 | 状态 `UNKNOWN`，UI 提示，由用户决策 |

**页面上的每个生成按钮都是主动生成**：先出图、再出「再生成一版」、出片后「重新生成」。所以 `regenerate_nonce` 在这些按钮里是必带项，否则用户点第二次会拿到同一张候选。重复提交的保护仍然必要——它挡的是网络重试、双击、多标签页，而不是用户的显式操作。

`input_signature` 的规范化必须显式定义并写测试：

```
sha256(type | target_id | sorted(asset_ref versions) | normalized prompt | params | nonce?)
```

换行、空格、JSON key 顺序、浮点精度都要在规范化时消掉，否则“同一次提交”会算出不同签名。

**不承诺外部 exactly-once。** 除非供应商提供幂等键且能力声明为 true，否则最多做到“本地不重复提交”。

## 7. 文件存储

```java
public interface StorageService {
    StoredObject put(InputStream in, String contentType, String key);
    URI presignedGet(String key, Duration ttl);
    void delete(String key);
}
```

- 实现：`LocalStorage`（开发默认，写入 `./data/storage`，被 `.gitignore` 忽略）、`S3Storage`（MinIO）。
- key 规则：`u/{userId}/p/{projectId}/e/{episodeId}/{kind}/{id}-{version}.{ext}`（资产版本类省略 episode 段）。
- 上传校验放在 API 层：大小上限、MIME 白名单（图片 `png/jpeg/webp`，视频 `mp4/webm`）、文件头探测，不只信扩展名。
- 下载走 `presignedGet`，不透过应用进程转发字节。

## 8. 前端结构

页面已经落地，下面是**现状**，不是计划：

```
apps/web/src
├── App.tsx                 # 路由与登录守卫
├── main.tsx                # 入口、主题与样式
├── pages/
│   ├── LoginPage.tsx
│   ├── HomeLayout.tsx      # 工作台外壳（我的项目 / 资产库 / 设置）
│   ├── IndexPage.tsx       # 项目列表、搜索、新建 / 重命名 / 删除
│   ├── AssetsPage.tsx      # 全局资产库（跨项目）
│   ├── SettingsPage.tsx    # 提供方配置
│   ├── ProjectLayout.tsx   # 项目内左栏、分集列表与状态点
│   ├── ProjectOverviewPage.tsx
│   ├── EpisodesPage.tsx
│   ├── EpisodeWorkspacePage.tsx   # 故事 / 分镜 / 视频 三个 Tab
│   ├── AssetLibraryPage.tsx       # 项目内角色库 / 场景库
├── features/
│   ├── StoryTab.tsx        # 剧本编辑与拆解入口
│   ├── StoryboardTab.tsx   # 镜头卡片列表、筛选、确认
│   ├── ShotEditor.tsx      # 400px 右侧编辑抽屉
│   └── VideoTab.tsx        # 已确认镜头出片、预览
├── components/             # Button / Modal / Badge / EmptyState / AssetRow / PlaceholderImage …
└── lib/
    ├── types.ts            # 页面状态形状（后端字段映射的唯一参考）
    ├── store.tsx           # 全局状态 + 所有动作（当前用 localStorage 持久化）
    ├── mock.ts             # 演示数据与提供方默认值
    ├── splitter.ts         # Mock 拆解器
    ├── storage.ts          # localStorage 读写与版本键
    └── id.ts               # uid / timeAgo / formatDuration
```

路由：

```
/login
/                                      我的项目
/assets                                全局资产库
/settings                              设置
/projects/:id                          项目概览
/projects/:id/episodes                 分集列表
/projects/:id/episodes/:episodeId/:tab?      story | storyboard | video
/projects/:id/characters               项目角色库
/projects/:id/scenes                   项目场景库
```

- 当前数据层是 `StoreProvider` + `localStorage`（键 `vibestory.state.v2`，旧键主动清理）。接后端时把 store 的动作逐一替换为 API 调用，页面组件不改形状。
- 接后端后引入 TanStack Query：列表用 query，写操作用 mutation，写后失效 `episodes` / `shots` 两个键。轮询按 §4.5 收敛到集级。
- 状态组件统一：`ShotStatusBadge` / `TaskBadge` 是文案与颜色的唯一来源，页面不自行判断。
- 令牌接入 Tailwind v4 的 `@theme`，颜色只来自 `design/theme.js`，不新增色值。
- **布局事实**：两侧栏统一 280px；编辑抽屉 400px；分镜卡片行三列 `112px / 1fr / 150px`；项目卡片 465:300；登录右侧表单栏 519px。改动布局前先确认这三处尺寸是设计基线。

### 工作区交互要点

- **故事**：左编辑区满高，右栏固定 320px；正文改动即保存（接后端后做 800ms 防抖），字数与镜头数实时显示。
- **分镜**：一行一镜的卡片，不是表格。左侧缩略图 + 候选条（候选图尺寸 28px，选中描紫边），中间描述与元信息，右侧视频状态与「生成 / 再生成」。点文字开抽屉，点图开预览，二者都不跳页。
- **视频**：只列已确认镜头；按钮文案直接表达前置条件（缺首帧 / 未确认），不做静默置灰；失败与陈旧各自一段说明。
- 破坏性操作（删除项目、重新拆解覆盖镜头）二次确认；重新拆解还必须在正文旁提示「旧镜头会被覆盖」。

## 9. 何时拆出 agent 进程

出现下列任一条件再考虑，否则保持单体：

- 拆解与修订需要独立扩容或独立发布节奏。
- 需要跨多个 Worker 共享 Agent 会话状态。
- 需要与 API 不同的运行时或依赖。

拆分时的切口已经预留：`provider` 与 `agent` 包只依赖领域接口，不直接访问 HTTP 层。

## 10. 迁移与数据

- Flyway 迁移按阶段递增：
  - `V1__init.sql`：`user`、`project`
  - `V2__task.sql`：`task`、`task_attempt`
  - `V3__episode.sql`：`episode`、`breakdown` 相关字段
  - `V4__shot.sql`：`shot`
  - `V5__asset.sql`：`character`、`scene_asset`、`asset_version`、`asset_ref`
  - `V6__media.sql`：`shot_image`、`video`
- 不使用 `ddl-auto: update`。
- 关键唯一约束与索引：
  - `project (user_id, updated_at desc)` 列表查询。
  - `episode (project_id, index)` 唯一；`episode (project_id, updated_at desc)`。
  - `shot (episode_id, seq)` 唯一；`shot (episode_id, status)` 支持筛选。
  - `character (project_id, lower(name))`、`scene_asset (project_id, lower(name))` 唯一。
  - `asset_version (owner_type, owner_id, is_selected)` 部分唯一索引 `WHERE is_selected`。
  - `shot_image (shot_id)` 下 `selected = true` 部分唯一索引。
  - `video (shot_id, status)`；`video (shot_id) WHERE stale` 用于陈旧筛选。
  - `task` 的活跃唯一索引见 §4.1。

## 11. 安全与作用域

- 所有请求从会话解析 `userId`，查询一律带 `user_id` 条件；找不到或不属于本人 → `404`，不区分“不存在”与“无权限”。
- 不信任前端传来的从属关系：写入前校验 `shot.episode_id == path.episodeId` 且 `episode.project_id == path.projectId`。
- 资产版本、分镜图、视频的归属沿父链校验；冗余的 `episode_id` 只用于查询加速，不作为授权依据的替代。
- 上传文件不按用户文件名落盘，统一生成 key。
- 密码用 BCrypt，不记录明文；日志不打印 token 与文件内容。
- 设置页里的提供方 Token：前端仅存本机；后端若持久化，加密存储、只回显掩码、不进日志。
- `.gitignore` 已忽略 `/data/`、`/uploads/`、`/generated/`、`/logs/`，生成的媒体不提交。

## 12. 测试策略

| 层 | 手段 | 覆盖重点 |
| --- | --- | --- |
| 单元 | JUnit + AssertJ | 状态机流转、`input_signature` 规范化、陈旧与解除陈旧、名称解析 |
| 集成 | `@SpringBootTest` + Testcontainers(Postgres) | 作用域隔离、幂等唯一索引、任务领取并发、重新拆解覆盖 |
| 并发 | 多线程领取 / 重复提交 | 不产生双任务、不双写 attempt |
| 故障注入 | Mock Provider 配置失败率与超时 | 重试、UNKNOWN 状态、租约回收 |
| 前端 | Vitest + Testing Library | 徽标映射、筛选计数、前置条件文案、空态 |
| 端到端 | Playwright（可选） | 登录 → 建项目 → 建集 → 拆解 → 出图 → 出片 |

必须写成测试的几条（都是“看起来对、实际会错”的地方）：

1. 同一镜头并发提交 10 次，`task` 只有 1 行活跃、1 行 attempt。
2. Worker 在 `RUNNING` 中途被杀，租约过期后任务被重新领取，attempt 递增。
3. 换图后，旧视频 `stale = true`；**切回原图，`stale` 复位为 false**。
4. 不同用户访问同一 `shot_id` / `episode_id` 全部返回 404。
5. 修改正文后重新拆解，旧镜头及其图、视频被清理，任务历史保留。
6. 拆解产出的角色名在项目内重复出现，只建一张卡。

## 13. 本地运行

```
# 依赖
JDK 21、PostgreSQL 16、MinIO（或跳过用 LocalStorage）、Node 20

# 后端
./gradlew bootRun            # 默认 mock provider + local storage
./gradlew test

# 前端
cd apps/web && npm install && npm run dev

# 数据库
docker compose up -d postgres minio   # infra/docker-compose.yml
```

`application-local.yml` 持有本地连接串；`.env.example` 只列键名不写入真实值。本机尚未安装 JDK 21，开始实现前第一件事是补齐环境。

前端现在可以脱离后端独立运行：`npm run dev` 打开的是本地演示版，数据存在浏览器里。这条路径在接入 API 后保留为演示模式。

## 14. 已知取舍

| 取舍 | 原因 |
| --- | --- |
| 单体而非微服务 | 单人开发、无独立扩容需求，拆进程只增加噪音 |
| 轮询而非 WebSocket | 第一阶段 3 秒轮询足够，收益不抵复杂度 |
| 集作为一等对象 | 页面上每集独立剧本、独立拆解、独立统计；用 `episode_index` 字段无法承载 |
| 成功由产物表达，不用 SUCCEEDED 徽标 | 切换候选图时不应把「生成过」的历史抹掉 |
| 冗余 `episode_id` / `project_id` | 作用域校验和列表筛选都要用，JOIN 三次不值得 |
| 资产名称解析而不是名称外键 | 拆解结果可能提到尚未建卡的角色，先落名字再补卡 |
| 数据库任务表而非 MQ | 任务量级小，且能在无中间件的环境完整验证幂等与租约 |
| 本地存储 + 抽象 | 保持可替换，同时不为对象存储增加部署门槛 |
| 不做多人协作 | 单人角色可完整覆盖创作链路，权限模型对简历增益有限 |

## 15. 数据库选型

结论：**PostgreSQL 16**，开发、测试、演示统一，不提供 SQLite 备选路径。

### 15.1 为什么不是 SQLite

SQLite 不是「小一号的 PostgreSQL」，它在并发模型上是另一套东西：

- **单写者**：同一时刻只有一个写事务，其他写全部排队。整个数据库一把写锁。
- **没有行级锁**：`SELECT ... FOR UPDATE SKIP LOCKED` 在 SQLite 上不存在，Worker 无法「各自认领不同任务」，只能串行消费。
- **没有真正的部分唯一索引**：SQLite 支持部分索引语法，但 `WHERE status IN (...)` 这种活跃任务唯一约束没法像 PostgreSQL 那样拦截并发插入。
- **类型系统弱**：`TIMESTAMPTZ`、`JSONB`、`NUMERIC` 精度这些都靠约定；租约时间与费用金额恰好都依赖它们。

本项目的核心机制是「多个 Worker 并发抢任务、抢不到就跳过」，SQLite 从根上不支持这个模型。ArcReel 用 SQLite 是合理的——它是单机自托管产品，任务串行执行可以接受；我们的目标是演示并发安全的任务队列，方向相反。

### 15.2 为什么不是 MySQL

MySQL 8 也能做，但三处能力对不上：

| 能力 | PostgreSQL | MySQL 8 |
| --- | --- | --- |
| `FOR UPDATE SKIP LOCKED` | 支持，语义干净 | 支持，但在复杂子查询加锁时行为更别扭 |
| 部分唯一索引 | 原生 `WHERE` 条件 | 不支持，只能靠生成列 + 唯一索引模拟 |
| `JSONB` 查询与索引 | 原生 GIN 索引 | JSON 索引受限 |
| 金额精度 | `NUMERIC` 无隐式转换 | `DECIMAL` 可用，但方言默认可变 |
| 集成测试 | Testcontainers 官方模块成熟 | 同样可用，但锁语义测试写起来更长 |

真正决定性的是前两条：活跃任务唯一约束与「同一镜头只有一个选中版本」都要用部分唯一索引，MySQL 的替代写法会让 schema 变复杂，且语义更晦涩。

### 15.3 为什么不用 H2 / 内存库做测试

- H2 的 PostgreSQL 兼容模式**不支持**部分索引与 `SKIP LOCKED` 的完整语义。
- 用 H2 测「并发抢任务」，测的是 H2 的锁实现，不是 PostgreSQL 的。
- 结论：集成测试用 Testcontainers 起真实 PostgreSQL 16；只有纯逻辑单测（状态机、签名规范化、陈旧判定）不依赖数据库。

### 15.4 连接与配置约束

- 连接池用 HikariCP，默认上限 `10`；Worker 线程数与连接池上限显式解耦，避免 Worker 数 > 连接数时互相饿死。
- 迁移只走 Flyway，`ddl-auto: validate`；禁止 `update`。
- 时间统一 `TIMESTAMPTZ`，应用层统一 `Instant`，不用本地时区字符串。
- 主键用 `BIGSERIAL`；如需对外暴露再加 `public_id`，原型阶段不做。
- 金额与费用用整数分（`BIGINT` cents），全局统一。

## 16. 原型边界

这一节回答：**这个仓库是「证明能力的原型」，不是「可运营的产品」**。两者的差距要写清楚，避免在文档、简历或面试里把原型说成产品。

### 16.1 定位差异

| | 原型（本仓库） | 实际产品（如 ArcReel） |
| --- | --- | --- |
| 目的 | 证明后端、Agent、全栈工程能力 | 解决真实用户的持续生产需求 |
| 用户 | 单个创作者，可自建账号 | 多租户、有权限与计费 |
| 供应商 | 1 个 Mock + 最多 1 个真实实现 | 10+ 家，能力差异矩阵 |
| 部署 | 本地 Docker Compose，单人演示 | 自托管 / SaaS，升级、备份、迁移 |
| 数据规模 | 演示数据，几十个项目 | 多租户数据，容量与归档 |
| 失败处理 | 明确状态 + 用户决策 | 自动补偿、告警、SLA |
| 运维 | 无 | 监控、日志、告警、值班 |
| 合规 | 无 | 隐私、内容审核、数据保留策略 |

### 16.2 原型明确不做的部分

以下每一条都是产品必须有、原型故意省略的，砍掉是为了让链路可交付：

- **多租户与权限模型**：只有「本人访问本人资源」，没有组织、成员、角色、共享。
- **计费与支付**：只记录用量与估算费用，不扣款、不结算、不开发票；本期连用量页面都不做。
- **内容安全**：不接入审核服务；生成内容的合规由供应商承担。
- **多供应商矩阵**：只接 1 家真实供应商，其余一律 Mock；不做能力降级矩阵。
- **高可用**：单实例 API + 单实例 Worker，无主从、无跨区、无自动故障转移。
- **可观测性体系**：只有结构化日志与基础指标；无链路追踪后端、无告警规则、无 SLO。
- **客户端能力**：只有 Web；无移动端、无桌面端、无剪映/CapCut 草稿导出。
- **成片能力**：到视频片段为止；不做剪辑、调色、混音、字幕轨、数字人。
- **国际化**：中文界面；不做多语言与地区化。
- **发布与升级**：不做自升级、数据迁移工具、版本兼容策略。
- **页面上没有的编辑能力**：手工补镜、删镜、拖拽排序、分镜包导出。

### 16.3 原型必须做扎实的部分

边界另一侧：这些是原型的价值所在，不能因为「只是原型」而糊弄。

- **任务状态机与幂等**：四种提交情况的区分必须真实可测，不能靠「一般不会重复」蒙混。
- **租约与崩溃恢复**：Worker 被杀后任务能被正确接手，attempt 记录可追溯。
- **作用域隔离**：越权一律 404，并有集成测试。
- **版本与陈旧判定**：换图后旧视频标记陈旧而不删除，切回原图能解除；来源版本可回查。
- **Provider 抽象**：Mock 与真实实现共享接口，切换只改配置。
- **可复现演示**：从注册到出片全流程可录屏复现，Mock 不依赖网络。
- **测试对应结论**：文档里每条能力标注「已实现 / 已测试 / 仅 Mock 验证」，不夸大。

### 16.4 表述纪律

写文档、简历、面试回答时遵守：

- 「实现了」= 有代码且有测试或演示；「计划」= 只在 roadmap 里。
- **前端页面**可以说「已实现（本地状态）」，但不能说成「已接后端」；两者分开表述。
- 「接入了真实供应商」只有在实际调用并留下记录后才能说；否则说「Provider 接口预留，默认 Mock」。
- 不把 ArcReel 这类项目的功能列表当作自己的实现范围；参考是参考，实现是实现。
- 不承诺模型层面的角色一致性；只承诺「画面不一致时，修起来更便宜」。

## 17. 文档与页面的关系

从这一版起，**页面是规格来源，文档是它的文字映射**：

- 字段以 `apps/web/src/lib/types.ts` 为准；状态枚举以同文件为准。
- 交互以 `pages/` 与 `features/` 为准；本文的尺寸、筛选名、按钮文案必须能在代码里找到对应。
- 改页面时同步改本文对应章节；不允许「文档里有、页面没有」的功能继续留在必做清单里。
- 若未来后端确实需要页面暂未使用的字段（如内部审计字段），可以加，但必须标注「页面不展示」，不得反向要求页面为它补 UI。

## 18. 与上一版的差异

| 主题 | 上一版 | 本版 | 依据 |
| --- | --- | --- | --- |
| 集 | 镜头的可选分组字段 `episode_index` | 独立聚合 `episode`，带正文与拆解状态 | `EpisodesPage` / `ProjectLayout` |
| 故事文本 | 项目级 `story_text` | `episode.story_content` | `StoryTab` 只编辑当前集 |
| 分镜表 | 图文同表 | 一行一镜的卡片列表 + 400px 抽屉 + 预览弹窗 | `StoryboardTab` / `ShotEditor` |
| 资产库 | 单一独立页面 | 项目内角色库 / 场景库 + 全局 `/assets` | `AssetLibraryPage` / `AssetsPage` |
| 资产引用 | `asset_ref` 存 ID | 页面存名字，后端名称解析后写 `asset_ref` | `types.ts` 的 `characterNames` / `sceneName` |
| 任务状态 | `QUEUED/RUNNING/SUCCEEDED/FAILED` 直接给前端 | 内部含 `SUCCEEDED`，前端投影 `idle/queued/running/failed/unknown` | `TaskState` |
| 陈旧 | 只置 true | true / false 双向同步 | `selectImage` 切回原图 |
| 导出 / 用量 / 手工补镜 | 必做或可选 | 本期不做 | 页面无入口 |
| 镜头状态 | 「确认」+「审看通过」两套 | 只保留 `draft ⇄ confirmed` | `ShotStatusBadge` 单徽标 |
