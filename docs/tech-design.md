# 技术方案

对应产品定义见 [product.md](product.md)，阶段与验收见 [roadmap.md](roadmap.md)。本文描述**怎么实现**，不重复功能描述。

> **规格来源**：`apps/web` 的已实现页面是这一版的字段与交互事实。本文中凡与页面冲突的描述，一律以页面为准并已改写；页面没有入口的功能不进后端。与上一版的差异见 §18。

> **实现状态（2026-11-05）**：当前原型范围已实现至阶段十三。生成链路、RabbitMQ / Redis 之外，已实现团队协作、分镜方案、主 / 子 Agent、Markdown Skill 文件库、三层上下文压缩，以及图片纠正、用户偏好与版本化配方。最新独立 PostgreSQL 全 Mock 验收为 189 项后端、17 项组件、17 项 Chrome，见 [image-learning.md](image-learning.md)；Agent 设计与迁移限制见 [backend-phase12.md](backend-phase12.md)。支持 DeepSeek 文本、MiniMax 图片 / 视频与 Mock，真实视频历史仅生成一次，本轮未调用真实供应商。当前接口以页面、代码与 springdoc 为准；中间件专项分别见 [backend-phase7.md](backend-phase7.md)和 [backend-phase8.md](backend-phase8.md)，不与本次数据库模式回归混计。

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

上图保留阶段一至六的数据库模式。**业务保持模块化单体**；第七阶段已让同一后端代码库以 API / Worker 两种角色运行，用 RabbitMQ 分发任务（§19）；第八阶段已接 Redis，共享多个 Worker 的生成提交速率。两步都保留 PostgreSQL 中的任务、attempt、来源快照与租约，不拆分项目、镜头、资产等领域服务。

目录 `apps/agent` 保留为概念边界；Agent 工具编排尚未实现，进程拆分条件见 §9。第七、八阶段不以 Agent / MCP 已完成为前提，未来工具仍调用同一套业务服务。

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
- 简单 CRUD 用 Spring Data JPA；分镜的多维筛选（确认状态 + 是否有图 + 任务活跃）用 jOOQ。
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
- 可复用资产只有人物（`character`）与场景（`scene_asset`）；`shot_image` 是镜头产物，不进入资产库。
- `project.visual_type` 使用 `VARCHAR` / `TEXT` 保存页面 `visualType` 的稳定字符串 ID；具体契约见 §3.6，不能限定为固定数量的画面类型枚举。

### 3.1 拆解状态

```
episode.breakdown: IDLE → RUNNING → DONE
```

- 状态存在 `episode` 上，拆解任务的目标是集。
- `DONE` 的判据是「这一集已有镜头」；页面入口（分镜 / 视频 Tab 是否可用）也读同一个判据，避免「状态说 done 但没有镜头」。
- 修改正文不自动删镜头；把 `DONE` 回退为 `IDLE`，提示用户需要重新拆解。
- 重新拆解在**同一事务**内：删除该集旧镜头（及其分镜图、视频、引用），写入新镜头。旧任务与尝试保留，作为历史。
- 阶段三保存独立 `story_revision` 与 `shot_revision`。提交时冻结正文、场景目录、模型配置和修订号；等待期间改正文或镜头，结果以 CANCELED / `BREAKDOWN_SOURCE_CHANGED` 结束，不覆盖新修改。仅改集名或梗概不改变正文修订号。
- `episode.breakdown_task_id` 指向当前拆解；正常终态同步更新缓存状态。进程异常、租约回收后，API 从持久化 task 状态投影，避免 UNKNOWN / FAILED 留下 RUNNING 徽标。

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

当前 `video` 与 `shot_image` 均只写成功产物，不以媒体行承载排队、执行、失败或未知状态；这些状态来自 `task`。`shot_image.status` 沿用早期表约束，当前完成写入为 READY，视频表不设 `status`。

```
task.status:       QUEUED → RUNNING → SUCCEEDED | FAILED | UNKNOWN | CANCELED
shot_image.status: READY（成功时插入）
shot_image.selected:  同一 shot 下只能有一行 selected = true（部分唯一索引保证）
                     第一张 READY 自动置 selected；之后由用户显式切换

video:             成功时插入，以 task_id 关联
```

页面的 `TaskState` 只有 `idle / queued / running / failed / unknown`：任务完成后状态回到 `idle`，成功由持久化产物与 `ready` 表达。分镜的 todo 筛选按候选数组是否为空。后端 API 对前端做同样投影，不引入一个前端没有的 `SUCCEEDED` 徽标。

分镜图与视频均只记录生成来源，不设陈旧状态、筛选或相关索引。更换资产基准版本、切换当前分镜图都不改写已有产物的来源与可用性。

`selectImage` 只更新当前选图；视频的 `source_image_id` / `source_image_version` 在提交生成时固定。视频卡片与预览按该来源显示首帧，不使用当前选图替代历史输入。旧数据缺少首帧来源时显示「未记录」。已有视频继续计入「已生成」，用户可以按需重新生成。

### 3.4 资产名称解析

拆解输出的是名字（`sceneName`、`characterNames`），页面也按名字展示引用关系和使用统计。落库规则：

1. 拆解落镜头时，按 `(project_id, name)` 查 `character_asset` / `scene_asset`；
2. 命中则写 `asset_ref(shot_id, role, asset_id)`；未命中则新建资产卡（`description` 留空），再写引用；
3. 名称在项目内唯一：`unique (project_id, lower(name))`，避免「沈夜」与「 沈夜」并存。

提交分镜图生成时固定人物与场景的基准版本，快照写入任务输入；Worker 使用此输入，完成后保存在每张图的 `input_snapshot`。名称只用于展示与解析，版本 ID 参与签名。

前端 `ShotImage.inputSnapshot` 与 `input_snapshot` 对应：

```ts
{
  characters: [{ assetId, name, versionId, versionLabel }],
  scene: { assetId, name, versionId, versionLabel }
}
```

- 快照复制资产与版本的身份、名称，不持有可变的资产对象；生成期间或完成后换基准版本，都不能改写该图来源。
- 预览与编辑抽屉展示「生成来源」，不显示分镜图陈旧、基于旧版或需要重做的状态。
- 旧数据没有 `inputSnapshot` 时显示「未记录」，不按当前基准版本补填。名称未能关联到版本时保留名称，缺失的 ID / 版本名留空，页面显示「未关联资产版本」。

### 3.5 取消确认与写保护

- 这一版**没有独立的审看环节**：`confirmed` 只表示「镜头内容定了、可以生成」，不代表冻结。
- 已确认镜头仍可改字段、切候选图、重做视频；要退回未确认状态，调 `PUT /api/shots/{id}/status { "status": "draft" }`。
- 因此服务端不需要为已确认镜头单独做 `409` 写保护；唯一的写保护是 `draft` 镜头不可提交生成。
- 已有镜头时，「重新拆解」先弹出「重新拆解这一集？」确认框，说明被覆盖的本集镜头数及手动修改、确认状态、分镜图和视频结果；取消、Esc 或点击遮罩只关闭弹窗，不启动任务。点「确认重新拆解」才重建镜头，确认状态回到 `draft`；当前剧本、人物库和场景库保留。首次拆解不弹窗。

### 3.6 画面风格与自定义参考图

- 内置预设以 `apps/web/src/lib/visualPresets.ts` 为目录来源：当前真人剧 `live` 9 项、漫剧 `comic` 6 项、风格化 `stylized` 7 项；`custom` 是用户自定义分类。分类与展示名由所选风格 ID 解析，不作为项目的 `visual_type` 值。
- API 的 `visualType` 为字符串，允许内置预设 ID 或当前用户自定义风格的 ID；服务端按目录与归属校验，不接受任意未解析的字符串。自定义条目对外的 ID 同样用字符串表达，前端不依赖其生成格式。
- 阶段二已用 `custom_style` 持久化稳定 ID、`user_id`、名称、参考图存储引用与移除时间，投影为页面 `VisualPreset` 的 `id/category/label/hint/image/custom`。当前联机模式将参考图提交后端，读取私有图片 URL；离线演示保留 data URL。项目响应保留所选条目元数据，移除选择器条目后仍可显示已有项目的风格。
- 参考图经大小、MIME、文件头、解码与像素数检查后重新编码，由 `LocalStorage` 保存，`image` 返回带作用域鉴权的接口 URL；项目仅保存风格 ID，当前数据库列 `style` 独立保存页面 `Project.style`。风格参考图按用户归属管理，不要求所属项目。
- 删除自定义条目只将其移出可选目录，已有项目仍能解析其名称和图片；新项目不可选已移除条目。最后一个项目更换风格或删除后，才清理该图片。`Project.visualPreset` 用于恢复包含已移除条目的既有项目。
- 后端内置目录从页面的 `visualPresets.ts` 生成：`node scripts/sync-visual-presets.cjs`；`--check` 检查目录是否一致，不修改前端。已有图片来源与许可仍以 `design/assets/STYLE_SOURCES.md` 为准。
- 内置预设数量和展示名可扩展，已有 ID 保持稳定；没有绑定预览图片的内置项继续使用 `tone` 配色占位，不要求为了创建项目先补齐所有素材。
- 风格预览只用于风格选择，项目封面是独立的展示概念。当前列表与概览按项目 ID 显示「暂无封面」占位，不使用内置或自定义风格参考图；本期不新增封面字段或上传入口。

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

阶段一至六用 `FOR UPDATE SKIP LOCKED` 与租约实现数据库内的并发领取；Redis 不参与任务所有权判定。以下为领取语义示意，实际实现还校验 Worker 的供应商配置并处理原 attempt 恢复：

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
- 进程崩溃后租约到期，任务由已启用对应供应商的 Worker 接手；不依赖优雅关闭。MiniMax 视频有 external ID 时复用同一 attempt 继续查询；没有 ID 时进入 UNKNOWN，不自动重新提交。
- **外部调用未知结果**：超时或连接中断时不直接置 `FAILED`，置 `UNKNOWN` 并按供应商能力决定是否可查；能力不支持时，把决策交给用户（“外部可能已执行，重试可能重复计费”）。页面上对应 `TaskState.unknown` 与「结果未知」文案。

### 4.3 Worker 职责边界

```
领取任务 → 读取持久化输入快照 → 调 Provider → 事务内写入结果与任务 / attempt 终态
```

生成前置规则（如镜头是否已确认）收口在提交服务；Worker 使用已冻结的输入执行，不自行确认镜头或改写创作内容。执行前和写回时仍须检查任务状态、作用域是否存在、provider 配置与租约所有权，防止删除或接管后的晚到写入；阶段七的消息消费者复用这些检查。

生成视频时，Worker 必须把**提交时选定的分镜图**记进 `video.source_image_id`，而不是任务结束时的最新图，确保生成期间切图也能追溯本次视频的真实输入。

### 4.4 尝试记录

`task_attempt` 记录执行尝试，新的业务执行递增 `attempt_no`；消息投递、限流等待与同一外部任务的多次查询不分别计尝试。重试复用 task；已有 external ID 的视频恢复复用原 attempt。因此：

- 调用次数按 attempt 记录；当前未实现费用账单或供应商账单核对，不将尝试次数当实际费用。
- 诊断时能看到第几次尝试失败、错误码是什么。
- 页面视频卡上的「尝试 N 次」直读 `video.attempt_count`（由 attempt 汇总投影）。

### 4.5 页面上的时间感

联机页面使用 Worker 的持久化状态，前端对活动集三秒轮询、页面隐藏时停止。离线演示保留原本的定时器模拟：

```
GET /api/episodes/{id}/shots?taskActive=true   # 3 秒一次，页面失焦停止
```

一个集一次请求，返回该集全部镜头 + 进行中的任务摘要，不逐镜发请求。

### 4.6 第七阶段与后续 Redis 的职责衔接

| 组件 | 职责 | 状态依据 |
| --- | --- | --- |
| PostgreSQL | 任务 / 批次、输入快照、attempt、幂等、租约、Outbox、延迟执行与恢复 | 唯一业务事实来源 |
| RabbitMQ（阶段七） | 把可执行任务通知投递给独立 Worker，支持分类消费与有界消息重投 | 消息到达不等于任务执行或业务完成 |
| Redis（阶段八已实现） | 对共享同一供应商凭证的多个 Worker，原子限制新生成提交的速率与突发量 | 临时准入状态，不保存业务完成状态 |

接入顺序固定为 **现有数据库任务机制 → RabbitMQ + 独立 Worker → Redis 共享限流**。第七阶段无需 Redis；第八阶段复用第七阶段的队列、延迟执行与多 Worker 验收。详细协议与边界见 §19、§20。

## 5. Provider 适配

```java
public interface ImageProvider {
    Result generate(Input input, String signature); // 阻塞，由 Worker 虚拟线程调用
}

public interface VideoProvider {
    VideoResult generate(VideoRequest request);
    Optional<VideoResult> poll(String externalId);  // 不支持则为 empty
    ProviderCapabilities capabilities();
}
```

- `ImageProviders.capabilities(provider)` 显式声明查询 / 幂等支持、人物参考数量、场景参考能力与提示词上限；视频能力由 `VideoProvider.capabilities()` 声明。当前没有单次费用估算。
- 图片参考图选择与租约恢复使用能力声明，供应商字段转换留在适配器中。
- Mock 实现遵循同一套接口，按 `input_signature` 生成确定性输出：固定占位图、固定 3 秒视频、可配置失败率。这让测试可断言。
- 默认 Mock；调度器按任务冻结的 provider 选择适配器，同时校验当前真实凭证的接口地址与任务一致。
- 阶段三文本实现为 `TextProvider` + `MockTextProvider` / `OpenAiCompatibleTextProvider`。`TextProviders` 按任务冻结的 provider 调度，并校验当前凭证对应的 endpoint 未改变。Mock 能安全重放；真实文本适配器声明不支持查询及外部幂等，超时 / 5xx / 租约丢失进入 UNKNOWN。不会把当前接口凭证发送到旧任务中的另一地址。
- 真实文本用 Chat Completions 的 JSON object 模式，另做字段、枚举、时长、Unicode 与镜头数量验证；格式失败保留脱敏原始响应到 `task_attempt`，只经用户作用域诊断接口读取，不写入公开日志。具体配置与上游文档见 [backend-phase3.md](backend-phase3.md)。
- 阶段四图片为 `MockImageProvider` 与 `MiniMaxImageProvider`，图片仅接入 MiniMax 原生协议。MiniMax 只发送单个人物 Data URL；其余引用保留来源与限制说明。一次任务固定一张图，无隐式重试；真实未知结果需明确确认。图片诊断仅保留状态码等元数据，不保存原始图片响应。配置与验收见 [backend-phase4.md](backend-phase4.md)。
- 第五阶段视频为 `MiniMaxVideoProvider` / `VideoProviders`，仅接入 Hailuo 2.3 Fast，768P / 6 秒请求规格。外部任务号先持久化再查询，已有任务只恢复查询与下载。MP4 校验后记录实测时长，首帧来源沿用提交快照。
- 设置页只读展示 `/api/runtime` 实际配置；本机环境配置 DeepSeek 文本与 MiniMax 图片 / 视频，不做通用供应商接入管理。密钥不进入浏览器或 API 响应。

Mock 不联网。`MockVideoProvider` 使用 JCodec 在运行时合成 320×180、24fps、72 帧的固定 3 秒占位 MP4，不需要 FFmpeg。`MockImageProvider` 按签名合成确定性 PNG。媒体只在运行时保存，不提交生成文件。

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

阶段一实现约定：文本的连续空白折叠为一个空格；对象 key 排序；`assetVersions` 引用集合排序，其余数组保序；浮点生成参数统一到 6 位小数并去尾零；整数身份完整保留，nonce 按不透明字符串原样参与签名。规范化后的 JSON 同时作为实际执行快照，避免签名与执行输入不一致。

**不承诺外部 exactly-once。** 除非供应商提供幂等键且能力声明为 true，否则最多做到“本地不重复提交”。

## 7. 文件存储

本节的通用接口与 S3 预签名属于后续存储阶段。当前实现本地 `LocalStorage`，支持风格参考图、人物 / 场景版本上传与生成图片。私有图片与视频均经作用域鉴权接口读取，视频支持 Range。尚未接入对象存储。

项目 / 分集删除在同一数据库事务中级联清理行并写入 `storage_cleanup`。清理器默认每 30 秒删除服务端构造的作用域目录，保留记录以重试崩溃或旧 Worker 晚到的文件。任务完成先锁项目，再检查 task 的 owner / version / 租约，和删除操作使用一致的锁顺序，不能复活已删除范围。文件系统不参与数据库事务，媒体清理是最终完成，不能把 HTTP 204 描述为磁盘已经同步清空。

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
│   ├── StoryboardTab.tsx   # 大图分镜表、筛选、确认
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
- **布局事实**：两侧栏统一 280px；编辑抽屉 400px；分镜表五列（镜号 / 画面 / 描述台词 / 参数 / 状态操作），最小宽 944px，主图宽 `clamp(240px, 22vw, 352px)`，候选图宽 56px；项目卡片 465:300；登录右侧表单栏 519px。分镜表在较窄窗口内横向滚动，避免压缩画面。

### 工作区交互要点

- **故事**：左编辑区满高，右栏固定 320px；正文改动即保存（接后端后做 800ms 防抖），字数与镜头数实时显示。
- **分镜**：一行一镜的五列表格，表头在表内滚动时固定。画面列显示 16:9 大图与候选条（候选图宽 56px，显示名称与紫色选中描边）；描述与台词、镜头参数、状态操作分列。点击画面开预览，点击描述或「编辑镜头」开 400px 抽屉，二者都不跳页。
- **分镜生成来源**：预览与编辑抽屉展示当前候选图提交时的人物 / 场景版本，编辑区另列「下次生成引用」。分镜图不作为资产；筛选只有全部 / 待出图 / 待确认。
- **视频**：只列已确认镜头；筛选只有全部 / 已生成 / 未生成 / 失败。按钮文案直接表达前置条件（缺首帧 / 未确认），不做静默置灰；失败原因与首帧来源可查看。
- 破坏性操作（删除项目、重新拆解覆盖镜头）二次确认；重新拆解的覆盖提示集中在确认弹窗，不另加常驻提示。上传文件替换正文时保留原有说明。拆解执行中优先显示「拆解中…」并禁用按钮，已有旧镜头也不能遮盖运行状态。

## 9. 何时拆出 agent 进程

出现下列任一条件再考虑，否则保持单体：

- 拆解与修订需要独立扩容或独立发布节奏。
- 需要跨多个 Worker 共享 Agent 会话状态。
- 需要与 API 不同的运行时或依赖。

拆分时的切口已经预留：`provider` 与 `agent` 包只依赖领域接口，不直接访问 HTTP 层。

## 10. 迁移与数据

- 已落地的 Flyway 迁移：
  - `V1__init.sql`：`app_user`、`project` 与视频链路依赖的最小 `episode`、`shot`、`shot_image`。
  - `V2__task.sql`：`task`、`task_attempt`、成功视频结果 `video`。
  - `V3__project_episode_and_styles.sql`：`custom_style`、`storage_cleanup`、现存表的删除级联与列表索引。
  - `V4__breakdown_shots_and_asset_names.sql`：正文 / 镜头修订号、拆解任务与诊断字段、`character_asset`、`scene_asset`、有复合外键作用域约束的 `asset_ref`。
  - `V5__asset_versions_and_image_tasks.sql`：`asset_version` 及选中约束、项目级资产任务、分镜图片任务、候选标签 / 媒体类型 / 任务关联与清理目录约束。
  - `V6__limit_image_provider_to_minimax.sql`：图片任务仅允许 Mock / MiniMax；保留文本的 OpenAI-compatible 协议用于 DeepSeek。
  - `V7__minimax_video.sql`：视频供应商约束与持久化 external task ID，支持恢复原尝试。
  - `V8__measured_video_duration.sql`：媒体时长使用实际 MP4 数据，不限制为名义输出时长。
- `V9__batch_and_dispatch.sql`：批次 / 成员、Outbox、派发轮次、`not_before`、派发阻塞与死信归档，以及事务内 Outbox 触发器。
- `V10__generation_admission.sql`：桶配置 / 恢复代次、准入事件与任务等待字段。
- 后续扩展继续新增迁移，不能改写已执行的 V1～V10。迁移编号与开发阶段不再一一对应。
- 不使用 `ddl-auto: update`。
- 关键唯一约束与索引：
  - `project (user_id, updated_at desc)` 列表查询。
  - `episode (project_id, index)` 唯一；`episode (project_id, updated_at desc)`。
  - `shot (episode_id, seq)` 唯一；`shot (episode_id, status)` 支持筛选。
  - `character (project_id, lower(name))`、`scene_asset (project_id, lower(name))` 唯一。
  - `asset_version(character_id)` 与 `asset_version(scene_id)` 各自 `WHERE is_selected` 部分唯一索引，以明确外键代替多态 owner_id。
  - `shot_image (shot_id)` 下 `selected = true` 部分唯一索引。
  - 阶段一 `video` 仅记录成功产物，以唯一 `task_id` 关联；进行中与失败状态保存在 `task`。
  - `task` 的活跃唯一索引见 §4.1。

## 11. 安全与作用域

- 所有请求从会话解析 `userId`，查询一律带 `user_id` 条件；找不到或不属于本人 → `404`，不区分“不存在”与“无权限”。
- 不信任前端传来的从属关系：写入前校验 `shot.episode_id == path.episodeId` 且 `episode.project_id == path.projectId`。
- 资产版本、分镜图、视频的归属沿父链校验；冗余的 `episode_id` 只用于查询加速，不作为授权依据的替代。
- 上传文件不按用户文件名落盘，统一生成 key。
- 密码用 BCrypt，不记录明文；日志不打印 token 与文件内容。
- 设置页不接收供应商 Token。凭证仅从本机后端环境读取，不写入输入快照、前端状态或响应；旧离线配置中的 Token 在存取时清空。
- `.gitignore` 已忽略 `/data/`、`/uploads/`、`/generated/`、`/logs/`，生成的媒体不提交。

## 12. 测试策略

| 层 | 手段 | 覆盖重点 |
| --- | --- | --- |
| 单元 | JUnit + AssertJ | 状态机流转、`input_signature` 规范化、生成来源快照、名称解析 |
| 集成 | `@SpringBootTest` + Testcontainers(Postgres) | 作用域隔离、幂等唯一索引、任务领取并发、重新拆解覆盖 |
| 并发 | 多线程领取 / 重复提交 | 不产生双任务、不双写 attempt |
| 故障注入 | Mock Provider 配置失败率与超时 | 重试、UNKNOWN 状态、租约回收 |
| 前端（已实现） | Playwright + Chrome，真实 Vite / Spring / PostgreSQL | 核心创作链路、草稿失败保留、跨账号、离线演示、自定义风格 |
| 前端组件（已实现） | Vitest + React Testing Library | 徽标映射、筛选计数、前置条件、空态、历史首帧与实测时长 |
| 端到端（已实现） | Playwright + Chrome，全部 Mock | 登录 → 建项目 → 建集 → 拆解 → 出图 → 出片，保存失败与跨用户隔离 |

必须写成测试的几条（都是“看起来对、实际会错”的地方）：

1. 同一镜头并发提交 10 次，`task` 只有 1 行活跃、1 行 attempt。
2. Mock Worker 在 `RUNNING` 中途被杀，租约过期后可安全重放并递增 attempt；视频有 external ID 时接续原 attempt，缺少可恢复身份的真实请求进入 UNKNOWN。
3. 生成期间或完成后切图，视频仍绑定提交时的首帧；已有视频继续计入已生成，预览展示真实来源。
4. 不同用户访问同一 `shot_id` / `episode_id` 全部返回 404。
5. 修改正文后重新拆解，旧镜头及其图、视频被清理，任务历史保留。
6. 拆解产出的角色名在项目内重复出现，只建一张卡。

### 第六阶段观测与验收

`GET /api/task-metrics` 只返回当前用户最近 24 小时创建的任务，按类型 / provider 分组。数据库统计状态数量、首轮排队时长和已结束 attempt 的墙钟时长；失败率分母仅包含已知成功 / 失败任务，UNKNOWN 与取消单列。无分母或时长样本时不捏造 0，重试不重复计任务数。应用重启不清空指标；删除历史会移除样本。

Logstash JSON 日志只记录任务、attempt 与来源身份等允许的字段，生命周期事件在事务提交后输出；回滚不记成成功。作用域拒绝记录访问者和路由模板，不泄露外部用户身份、查询参数或正文。没有添加指标存储服务、告警、链路追踪或费用统计。

`scripts/verify-all-mock.sh` 强制 Mock 并清空供应商密钥，`MOCK_ONLY=true` 在配置混入真实模型时拒绝启动。回归入口和明确统计口径见 [第六阶段记录](backend-phase6.md)。用户已取消录屏。

### 第七阶段与后续 Redis 的验收范围

第七阶段使用真实 PostgreSQL + RabbitMQ 验证 Outbox 投递、重复消息、手动 ACK、死信、两 Worker 领取和崩溃接管；第八阶段已使用真实 Redis 验证跨 Worker 共享限流与故障关闭，详见 [阶段八记录](backend-phase8.md)。生成全部使用 Mock；外部任务查询 / 下载恢复沿用本机 HTTP fixture，不发起真实生成请求。适配器单测不能代替真实中间件协议验收；没有运行的 Docker / Testcontainers 分支继续单独标注。具体条件见路线图阶段七、八。

## 13. 本地运行

```
# 依赖：JDK 21、PostgreSQL 16；阶段一不需要 MinIO
cp .env.example .env        # 补齐数据库密码、种子邮箱及密码
set -a; source .env; set +a
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres

# 后端
scripts/dev-api.sh          # 读取 .env，显式 JDK 21；默认 Mock + 本地存储
scripts/java21.sh test      # 默认 Testcontainers，需要 Docker
scripts/java21.sh bootJar
python3 scripts/verify-phase1.py   # 真实 HTTP 与 SIGKILL / 重启验收

# 前端
cd apps/web && npm install && npm run dev

```

JDK 21 已安装；macOS 开发脚本通过 `java_home -v 21` 避开终端默认 Java 11。连接配置通过环境变量注入，`.env.example` 无真实凭证。无 Docker 时可显式设置 `TEST_DATABASE_URL/USER/PASSWORD`，测试在 PostgreSQL 16 中创建并清理随机隔离 schema；不会切换到 H2。完整步骤见 [backend-phase1.md](backend-phase1.md)。

前端启动先探测 API，存在后端时使用会话与持久化数据；不可用时明确进入独立的本地演示。联机期间网络失败保留错误与草稿，不切换到本地写入。

第七阶段已增加 RabbitMQ 4.4.0 的 Compose 配置、环境模板和 `scripts/dev-mq.sh api|worker` 入口；`scripts/dev-test-rabbitmq.sh` 启动本机隔离测试节点。默认 database/all 模式保留用于已有 Mock 回归；rabbit 模式中 API 不执行任务。所有进程须使用相同模式，不能混跑两种 Worker。媒体共享绝对根目录，完整启动与验收说明见 [backend-phase7.md](backend-phase7.md)。阶段八已增加可选 Redis，`scripts/dev-redis.sh api|worker` 强制 Mock 启用准入，默认仍关闭；见 [阶段八记录](backend-phase8.md)。

## 14. 已知取舍

| 取舍 | 原因 |
| --- | --- |
| 模块化单体，阶段七独立运行 Worker | 业务模型与事务留在同一代码库；独立进程用于批量任务执行和故障隔离，不拆领域微服务 |
| 轮询而非 WebSocket | 第一阶段 3 秒轮询足够，收益不抵复杂度 |
| 集作为一等对象 | 页面上每集独立剧本、独立拆解、独立统计；用 `episode_index` 字段无法承载 |
| 成功由产物表达，不用 SUCCEEDED 徽标 | 切换候选图时不应把「生成过」的历史抹掉 |
| 冗余 `episode_id` / `project_id` | 作用域校验和列表筛选都要用，JOIN 三次不值得 |
| 资产名称解析而不是名称外键 | 拆解结果可能提到尚未建卡的角色，先落名字再补卡 |
| 数据库任务表作为事实来源，阶段七用 MQ 分发 | 现有幂等与租约继续有效；RabbitMQ 服务于整集批量提交与独立 Worker，不承担业务状态存储 |
| 阶段八 Redis 共享限流 | 两个及以上 Worker 共享同一模型凭证时协调提交速率；阶段七先使用本地并发限制 |
| 本地存储 + 抽象 | 保持可替换，同时不为对象存储增加部署门槛 |
| 不做多人协作 | 单人角色可完整覆盖创作链路，权限模型对简历增益有限 |

## 15. 数据库选型

结论：**PostgreSQL 16**，开发、测试、演示统一，不提供 SQLite 备选路径。

### 15.1 为什么不是 SQLite

SQLite 不是「小一号的 PostgreSQL」，它在并发模型上是另一套东西：

- **单写者**：同一时刻只有一个写事务，其他写全部排队。整个数据库一把写锁。
- **没有行级锁**：`SELECT ... FOR UPDATE SKIP LOCKED` 在 SQLite 上不存在，Worker 无法「各自认领不同任务」，只能串行消费。
- **部分唯一索引不是差异点**：SQLite 也能用部分唯一索引约束活跃任务（[官方文档](https://www.sqlite.org/partialindex.html)）；本项目选择 PostgreSQL 的决定性原因是行级锁与 `SKIP LOCKED` 的任务领取模型。
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
- 结论：集成测试用 Testcontainers 起真实 PostgreSQL 16；只有纯逻辑单测（状态机、签名规范化、来源快照）不依赖数据库。

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
| 用户 | 私有个人项目、固定团队角色和项目共享 | 产品级多租户、细粒度权限与计费 |
| 供应商 | Mock + DeepSeek 文本 + MiniMax 图片 / 视频 | 多家，能力差异矩阵 |
| 部署 | 本地 Docker Compose，单人演示 | 自托管 / SaaS，升级、备份、迁移 |
| 数据规模 | 演示数据，几十个项目 | 多租户数据，容量与归档 |
| 失败处理 | 明确状态 + 用户决策 | 自动补偿、告警、SLA |
| 运维 | 无 | 监控、日志、告警、值班 |
| 合规 | 无 | 隐私、内容审核、数据保留策略 |

### 16.2 原型明确不做的部分

以下每一条都是产品必须有、原型故意省略的，砍掉是为了让链路可交付：

- **产品级多租户与权限模型**：原型支持固定团队角色与团队项目共享，范围不包括部门树、项目级 ACL、SSO 或租户运维。详见[阶段十记录](backend-phase10-team-management.md)。
- **计费与支付**：只记录任务与 attempt，不估算或核对费用；本期不做用量页面、扣款、结算或发票。
- **内容安全**：不接入审核服务；生成内容的合规由供应商承担。
- **多供应商矩阵**：仅 DeepSeek 文本与 MiniMax 图片 / 视频，另保留 Mock；不扩展通用模型矩阵。
- **高可用**：数据库模式同进程执行；阶段七已增加本机独立 Worker 与两 Worker 并发 / 恢复演示，无服务高可用、RabbitMQ / Redis 集群或跨区故障转移。媒体仍共享同一本地存储根目录。
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
- **版本与来源追溯**：每张分镜图保留提交时的人物 / 场景版本，每段视频保留提交时首帧；切图不改写已有产物来源，历史可回查。
- **Provider 抽象**：Mock 与真实实现共享接口，切换只改配置。
- **可复现演示**：从登录到出片全流程有自动化浏览器验收，生成全部用 Mock；没有注册页面，用户已取消录屏。
- **测试对应结论**：文档里每条能力标注「已实现 / 已测试 / 仅 Mock 验证」，不夸大。

### 16.4 表述纪律

写文档、简历、面试回答时遵守：

- 「实现了」= 有代码且有测试或演示；「计划」= 只在 roadmap 里。
- **前端页面**当前已完成核心 API 联调，可以据实描述已接后端；离线演示、Mock 验收与真实供应商验证仍须分开表述。
- 「接入了真实供应商」只有在实际调用并留下记录后才能说；否则说「Provider 接口预留，默认 Mock」。
- 不把 ArcReel 这类项目的功能列表当作自己的实现范围；参考是参考，实现是实现。
- 不承诺模型层面的角色一致性；只承诺「画面不一致时，修起来更便宜」。

## 17. 文档与页面的关系

从这一版起，**页面是规格来源，文档是它的文字映射**：

- 字段以 `apps/web/src/lib/types.ts` 为准；状态枚举以同文件为准。
- 交互以 `pages/` 与 `features/` 为准；本文的尺寸、筛选名、按钮文案必须能在代码里找到对应。
- 改页面时同步改本文对应章节；不允许「文档里有、页面没有」的功能继续留在必做清单里。
- 若未来后端确实需要页面暂未使用的字段（如内部审计字段），可以加，但必须标注「页面不展示」，不得反向要求页面为它补 UI。
- 第七阶段「本集批量生成」已在分镜 / 视频页实现；按 §19 提交批次并展示成员接受结果，刷新从数据库恢复进度。页面不自动确认、选首帧或连跑后续步骤。

## 18. 与上一版的差异

| 主题 | 上一版 | 本版 | 依据 |
| --- | --- | --- | --- |
| 集 | 镜头的可选分组字段 `episode_index` | 独立聚合 `episode`，带正文与拆解状态 | `EpisodesPage` / `ProjectLayout` |
| 故事文本 | 项目级 `story_text` | `episode.story_content` | `StoryTab` 只编辑当前集 |
| 分镜表 | 小缩略图卡片列表 | 固定表头的五列大图表格 + 400px 抽屉 + 预览弹窗 | `StoryboardTab` / `ShotEditor` |
| 资产库 | 单一独立页面 | 项目内角色库 / 场景库 + 全局 `/assets` | `AssetLibraryPage` / `AssetsPage` |
| 资产引用 | `asset_ref` 存 ID | 页面存名字，后端名称解析后写 `asset_ref` | `types.ts` 的 `characterNames` / `sceneName` |
| 任务状态 | `QUEUED/RUNNING/SUCCEEDED/FAILED` 直接给前端 | 内部含 `SUCCEEDED`，前端投影 `idle/queued/running/failed/unknown` | `TaskState` |
| 产物来源 | 换版本后判定有效性 | 只保留生成来源，切图不改写已有视频 | 分镜来源快照 / 视频 `basedOn` |
| 导出 / 用量 / 手工补镜 | 必做或可选 | 本期不做 | 页面无入口 |
| 镜头状态 | 「确认」+「审看通过」两套 | 只保留 `draft ⇄ confirmed` | `ShotStatusBadge` 单徽标 |
| 任务分发扩展 | 阶段一至六数据库轮询 | 阶段七已加入 Outbox + RabbitMQ + 独立 Worker | `batch` / `dispatch`、`BatchGeneration` |
| 共享生成额度 | 每进程本地并发控制 | 阶段八已加入可选 Redis 共享提交准入 | `admission` / Lua、真实 Redis + 双 JVM 验收 |

## 19. 第七阶段：RabbitMQ 与整集批量任务分发（已实现）

### 19.1 业务场景与范围

创作者在同一集内选择多个已确认镜头，一次提交分镜图或视频生成。API 需要尽快返回批次身份，由独立 Worker 排队执行；视频等待不应占用 HTTP 请求，也不应阻塞文本、图片通道。单镜生成继续使用同一任务服务与派发链路。

本阶段新增「本集批量生成」页面入口及批次查询，不增加跨集流水线、自动选择首帧或无人值守重生成。图片与视频分别提交：视频批次只使用提交时已选首帧。未确认、缺首帧等情况逐镜阻塞；相同输入的活跃任务复用，不同输入的活跃生成逐镜报告冲突，不自动确认、补图或并行重做。Agent / MCP 后续可复用批次接口，本阶段不实现 Agent Runtime。

### 19.2 部署与队列结构

```text
Web / 后续 CLI、MCP
  → API：事务写入 batch / task / outbox
  → Outbox Relay：确认投递 → RabbitMQ
  → 独立 Worker：数据库领取指定任务 → Provider → 数据库记录结果
                       ↘ 租约恢复 / 到期重派发 ↗
```

- API、Relay、Worker 来自同一代码库，以运行角色区分；API 不执行媒体任务，Relay 可先与 API 同进程。各进程连接同一 PostgreSQL，并共享同一本地媒体存储根目录。
- 一个任务交换机，按文本、图片、视频路由到三个持久化工作队列；消息持久化，配置死信交换机与队列。Mock 与真实配置使用不同的队列命名空间，Worker 仍校验持久化 provider，Mock 验收不能消费真实任务。
- Worker 按媒体类型设置本地执行槽位，支持两个独立 Worker 同时消费。`prefetch` 只限制未 ACK 的消息；任务接管后仍须由本地执行槽位限制实际在跑数量，不能靠提前 ACK 绕过限制。
- 消息只包含带版本的 `eventId`、`taskId`、`dispatchGeneration`、任务类型与内部作用域 ID。正文、凭证、媒体与生成参数从数据库快照读取，不写入消息；消息中的作用域字段只用于交叉校验，不作为授权来源。

### 19.3 批次、Outbox 与幂等

- `generation_batch` / 成员记录保存用户、项目、集、提交请求身份，以及每个镜头的接收、复用或阻塞结果。提交时固定成员与生成输入，查询时汇总任务状态；批次完成表示成员均有明确结局，不表示全部成功。
- 批量请求带稳定的提交幂等键：同用户、同集、同请求复用批次，键相同但内容不同返回冲突；主动重新生成必须换请求键 / nonce。逐镜仍走既有作用域、确认状态、签名与活跃任务唯一约束。阻塞成员不创建任务，合格成员的任务、批次成员及 Outbox 在同一事务中提交；数据库失败则整笔事务回滚。
- 每次真正进入可派发状态，在同一事务写入任务和 `task_outbox`，按 `(task_id, dispatch_generation)` 唯一。重复 HTTP 提交复用现有记录；用户允许的失败重试增加派发轮次，保留 task 身份，attempt 仍由执行机制管理。
- Relay 通过短租约 / `SKIP LOCKED` 领取到期 Outbox，发布后收到 publisher confirm 且没有 mandatory return 才标记已发送。事务回滚不发送；已发送但未记成功时允许重投，由消费者去重。多 Relay 共享投递租约，网络发送期间不持有长数据库事务。
- 批次查询只返回本人范围内成员与结果。RabbitMQ 的 confirm、ACK 和队列清空均不能直接投影为业务成功；生成结果和完成状态继续读取 PostgreSQL。

### 19.4 消费、ACK 与崩溃恢复

1. 消费者先校验消息契约与本地执行名额，再按 `taskId + dispatchGeneration` 在短事务中领取任务；校验状态、`not_before`、作用域父链和 provider，写入租约与 attempt。初次执行创建 attempt；已有 external ID 的视频恢复复用原 attempt。
2. **领取事务提交后 ACK**，随后在受控执行槽位中执行长任务。ACK 表示数据库已接管，不等待数分钟的生成结束。若事务提交前断开，消息可重投；提交后、执行前崩溃，由数据库租约恢复接手。
3. 旧派发轮次、已在有效租约内执行或已终结的重复消息直接 ACK，不新增 attempt、不重复调用供应商。任务及其项目已删除时丢弃通知，不重建资源；真实处理失败不能伪装成已删除。
4. 定时协调器扫描到期的 `QUEUED` 任务与租约过期任务。仍排队且派发等待超时的任务重置当前轮次 Outbox 为待发送，保持原通知有效；进入新的恢复执行轮次时才条件轮转派发身份。存在待发送或未超过派发等待时限的通知时，不反复创建新轮次，避免积压中不断作废旧消息。此机制覆盖 ACK 后崩溃、消息丢失与早到通知；领取仍以数据库条件更新为准，消息顺序不代表业务顺序。
5. 沿用现有恢复语义：Mock 可安全重放；视频有 external ID 时继续查询 / 下载，复用原 attempt；真实请求无可恢复外部身份时进入 `UNKNOWN`，由用户明确决定重试。MQ 重投不赋予供应商 exactly-once，也不能自动重放 `UNKNOWN`。
6. 单进程数据库模式与 MQ 模式互斥选择新任务派发入口；两种模式复用状态机、租约、输入快照和结果落库。MQ 模式的协调器只做恢复裁决 / 重派发，不额外启动直接调用 Provider 的轮询器。

### 19.5 延迟、死信与观测

- 基础设施瞬时失败采用有限次数、带退避的投递重试；数据库 `available_at` / `not_before` 决定到期重投，不使用无限 `nack(requeue=true)` 或为等待任务长期占住消费线程。
- 契约不合法、类型不支持或超出投递重试预算的消息进入死信。死信只表示派发异常，不等于生成业务失败；有效 task 保留可查询的派发问题与人工处理入口，暂停协调器的自动重派，防止毒消息被无限复活。修复后显式重派发，仍需校验任务状态；`UNKNOWN` 不通过死信重放绕过用户确认。
- 新生成尝试仍遵循 §6 的失败可重试判定。投递重试不增加 attempt；只有业务允许的重新执行才增加，已有外部任务恢复不增加。
- 记录 Outbox 待发送数 / 最老等待时间、投递重试、死信、重复通知、各类型排队与执行数、租约恢复；指标只描述实际采样，不预设吞吐量或耗时提升。
- 已增加 RabbitMQ 依赖、配置、启动入口、V9 迁移与真实 Broker 协议测试。固定 RabbitMQ 4.4.0 单节点；详细默认值、API 和验收入口见 [阶段七记录](backend-phase7.md)。不引入 Kafka、服务注册中心或 RabbitMQ 集群。

协议依据：[RabbitMQ 发布确认与消费确认](https://www.rabbitmq.com/docs/confirms)、[死信交换机](https://www.rabbitmq.com/docs/dlx)。实际实现、故障注入与证据边界见 [阶段七记录](backend-phase7.md)。

## 20. 第八阶段：Redis 多 Worker 共享生成提交限流（已实现）

### 20.1 业务场景、依赖与计量口径

阶段七已有两个及以上独立 Worker，可能同时使用同一份 DeepSeek / MiniMax 凭证处理批量任务。每个进程各自限流会叠加调用量，因此本阶段用 Redis 统一限制**新生成请求的提交速率和突发量**。场景成立后接入，顺序在 RabbitMQ 与多 Worker 验收之后。

本期限制的是生成提交，不是计费额度、每日配额或用户套餐；不增加通用缓存、Redis 分布式任务锁或另一套任务队列。每进程 Semaphore 继续限制在跑任务数；共享提交速率不等于全局在途并发上限，两种指标分开记录。已有 external ID 的恢复查询 / 下载不重新消耗生成提交额度，沿用现有查询间隔与超时；本期不宣称已实现供应商所有 HTTP 操作的全局 QPS 限制。

### 20.2 准入算法与作用域

- 使用 Redis Lua 脚本原子执行令牌补充、判断与扣减，以 Redis 服务端时间计算；配置补充速率 `rate` 和桶容量 `burst`。返回 `allowed` 与 `retryAfterMs`，验收按令牌桶的速率 / 突发语义断言，不能写成任意一秒都绝不超过固定次数。
- 本期桶键为供应商 + 接口标识 + 不透明的共享凭证配置 ID，按同一凭证的生成提交总量限流；MiniMax 图片 / 视频共用桶，DeepSeek 使用自己的桶。同一凭证的多个用户、配置别名与 Worker 必须映射到同一共享 ID，不能按进程或用户拆出独立额度。暂不增加能力子桶或复杂配额树。
- Redis key、消息与日志不包含原始 API Key、正文、用户上传或参考图。限流配置由服务端管理，不开放新的供应商矩阵或配额管理页面。
- 空闲桶的 TTL 至少覆盖从空桶补满的时间并留余量，不能通过过早过期反复获得突发额度。单节点 Redis；不声明跨主从切换的严格配额保证。

### 20.3 与任务领取的衔接

1. 对可开始的新生成任务，消费者先只读校验任务仍在当前派发轮次、状态为 `QUEUED` 且已到期，再获取本地执行名额并申请 Redis 令牌。Redis 网络调用不放进数据库行锁事务。
2. 未获额度时，用状态与派发轮次条件更新 `not_before` / 等待原因，轮转派发身份并事务写入到期 Outbox；任务保持 `QUEUED`，释放本地名额后 ACK 当前通知。不新增 attempt，不写 `FAILED`，也不阻塞租约心跳或让消息立即热重投。若该通知已被其他 Worker 接管，条件更新不生效，不能改写对方任务。
3. 获得令牌后再按 §19.4 的条件事务领取任务并提交调用。若领取竞态失败、任务已删除或随后失去租约，本次令牌可保守消耗，不自动返还，避免重复退款放大准入量；保留数据库 fencing，Redis 许可不代表获得任务所有权。
4. 业务重试、主动重生成与可安全重放的 Mock 都按新提交重新申请额度；已有外部任务号的恢复走查询 / 下载通道。所有入口共用执行服务，后续 MCP 不能绕过准入。

限流上界以 Redis 原子准入时刻计量；许可只供紧接着的一次条件领取与生成提交使用，不预取或跨任务复用。记录准入与实际请求发起时间，超出短许可有效期后须重新申请，已领取时保留原 attempt / 续约，不无许可调用或再建 attempt。验收分别核对准入曲线、真实请求次数及许可到调用的延迟；网络 / 调度可能改变供应商观察到的到达时间，不把本地准入上界直接宣称为供应商端任意窗口的精确 QPS 保证。

### 20.4 不可用与恢复

- Redis 连接失败、超时或脚本错误时，对**新生成提交停止放行**：保留 `QUEUED` 并记录可恢复等待原因，经数据库退避后再派发；不降级成各进程独立限流，也不切换为无限制提交。
- 已发出的调用、外部任务查询 / 下载、租约续约与结果落库继续按现有机制执行。Redis 故障不撤销远程任务，不把其重投为新的付费生成。
- 检测到 Redis 重启或限流状态丢失时暂停新准入，由单一恢复协调者在数据库锁下推进持久化恢复代次，各 Worker 对齐代次，将受影响桶从零令牌重建后恢复补充，避免各进程自行填满桶。严格速率结论限定在已验证的单节点正常运行与恢复策略范围内，不延伸为高可用保证。
- 观测允许 / 拒绝 / Redis 错误次数、等待时长与按共享桶聚合的提交计数；用户任务页仍读取数据库状态，Redis 不保存任务完成信息。
- 已增加 Spring Data Redis、Lua 脚本、配置及本地启动项；阶段七的 RabbitMQ / Outbox / 租约机制保持相同的业务语义。

算法依据：[Redis 共享限流说明](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)。具体参数用可配置 Mock 场景验证，不把演示参数描述为真实供应商限额。

### 20.5 实现与实测入口

默认 `ADMISSION_ENABLED=false`，启用仅支持 rabbit 模式。`AdmissionProperties` 管理共享身份与演示参数；`GenerationAdmission` / `AdmissionStore` 实现领取前准入、数据库延迟派发、短许可与恢复；适配器 `SubmissionBoundary` 在完成本地准备后校验许可；`/api/admission-metrics` 按用户隔离。marker 永不过期、tokens TTL 覆盖补满时间与余量，所有缺失（含自然过期）保守从零重建。同一凭证的配置别名须映射同一配置 ID，配置冲突停止准入。

真实 Redis 17 项专项与双 JVM / Redis 停机重启 / 单桶丢失 / Chrome 验收、原数据库和 MQ 回归详见 [阶段八记录](backend-phase8.md)。生成全部 Mock，恢复仅本机 HTTP fixture。它们验证应用准入与本机调用，不代表远端任意窗口的精确 QPS、真实吞吐提升或中间件集群保证。
