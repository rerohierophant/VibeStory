# VibeStory

**AI 短剧前期制作工作台。** 上传一段故事文本，产出可投产的分镜图与视频片段；创作过程可编辑、可恢复、可追溯。

- 产品定位、用户路径与数据模型：[docs/product.md](docs/product.md)
- 技术方案与任务机制：[docs/tech-design.md](docs/tech-design.md)
- 实现顺序、必做/可选标记与阶段验收：[docs/roadmap.md](docs/roadmap.md)
- 第一阶段后端的运行、接口与验证记录：[docs/backend-phase1.md](docs/backend-phase1.md)
- 第二阶段项目、风格与分集持久化：[docs/backend-phase2.md](docs/backend-phase2.md)
- 第三阶段拆解、镜头与文本模型：[docs/backend-phase3.md](docs/backend-phase3.md)
- 第四阶段资产版本、分镜图与 MiniMax：[docs/backend-phase4.md](docs/backend-phase4.md)
- 第五阶段视频与前后端联调：[docs/backend-phase5.md](docs/backend-phase5.md)
- 第六阶段观测、测试与收尾：[docs/backend-phase6.md](docs/backend-phase6.md)
- 第七阶段批次、RabbitMQ 与独立 Worker：[docs/backend-phase7.md](docs/backend-phase7.md)
- 第八阶段 Redis 共享生成准入：[docs/backend-phase8.md](docs/backend-phase8.md)
- 第九阶段自然语言工作台与 MCP：[docs/backend-phase9.md](docs/backend-phase9.md)
- 第十阶段团队管理与项目协作：[docs/backend-phase10-team-management.md](docs/backend-phase10-team-management.md)
- 第十一阶段分镜创作方案：[docs/creative-variants-plan.md](docs/creative-variants-plan.md)
- 第十二阶段主 / 子 Agent、Skill 与上下文压缩：[docs/backend-phase12.md](docs/backend-phase12.md)
- 第十三阶段图片纠正、用户偏好与创作配方：[docs/image-learning.md](docs/image-learning.md)

## 核心链路

**集是生产单位**：项目下每一集有自己的剧本、拆解状态、镜头和产物。

```
项目 → 新建一集 → 粘贴 / 上传这一集的剧本
     → AI 拆解镜头清单（画面描述 / 场景 / 景别 / 运镜 / 台词 / 时长）
     → 逐镜确认 → 资产库（项目级角色卡、场景卡 + 基准形象）
     → 文生图分镜（第一张自动选定）→ 图生视频（可随时取消确认回改）
```

链路的关键性质是**产物逐级成为下级输入**：人物与场景基准版本在分镜图提交时固定；选定的分镜图成为视频首帧。引用链保证来源可追溯，实际参考图输入受供应商能力限制，视觉一致性仍须检查生成结果。

页面入口：项目概览 → 分集 → 工作区三 Tab（故事 / 分镜 / 视频）；角色库 / 场景库在项目内，另有跨项目的全局资产库。

产品主张是一句可验证的话：**不承诺让模型更一致，承诺画面不一致时修起来更便宜。**

## 平台核心功能

1. **项目管理**：新建时选择画面风格并补充风格描述；按真人剧、漫剧、风格化、自定义四类选择，含 22 个内置预设，支持上传自定义参考图。项目保存稳定的风格 ID；卡片展示镜头数与更新时间，支持搜索、重命名、删除。
2. **分集**：每集独立剧本、独立拆解、独立进度；集卡片用前三个已选首帧拼封面。
3. **故事文本**：粘贴或上传 `.txt` / `.md`，只编辑当前这一集的正文，原文保留，可重新拆解。
4. **拆解**：AI 把这一集正文拆成镜头清单，结果可逐条编辑，确认后才进入生产。
5. **资产库**：仅人物与场景属于可复用资产，维护项目级基准形象与版本历史；另有跨项目汇总的全局资产库。分镜图属于镜头产物，不进入资产库。
6. **分镜**：固定表头的大图表格 + 右侧编辑抽屉，三种筛选；单镜 / 本集批量生成，逐镜展示接收、复用或阻塞结果。
7. **分镜图**：文生图任务，多候选，第一张自动选定；每张图记录提交生成时的人物与场景版本，在预览与编辑抽屉中查看。切换资产基准版本不改写历史来源。
8. **视频**：只列已确认镜头，以提交时选定的分镜图为首帧；失败可重试，保留首帧来源。切图后已有视频仍可查看与使用，按需重新生成。
9. **确认**：单镜确认 / 取消确认、整集确认；只有「待确认 / 已确认」一个状态，不叠加审片标签。

## 技术栈

| 层 | 选型 | 职责 |
| --- | --- | --- |
| 前端 | React 19 + TypeScript + Vite + Tailwind CSS | 我的项目、分集、工作区三 Tab、资产库、设置 |
| 设计系统 | `design/theme.js` + `design/tokens.css` | 深色主题、字体、渐变 CTA、断点 |
| 业务后端 | Java 21 + Spring Boot 3 + Gradle | 用户、项目、分集、镜头、资产、任务 |
| 数据访问 | Spring Data JPA + jOOQ（复杂查询） | 事务边界、乐观锁、分页查询 |
| 存储 | PostgreSQL 16 + Flyway | 结构化状态、批次、任务与 Outbox 持久化 |
| 任务分发 | RabbitMQ 4.4.0（可选模式） | 文本 / 图片 / 视频通道、独立 Worker、手动 ACK 与死信 |
| 生成准入 | Redis 8.10.3（可选，依赖 MQ 模式） | 多 Worker 共享提交速率、短许可、故障关闭 |
| 文件 | 本地私有文件（已实现）；S3 兼容存储（计划） | 上传素材与生成媒体 |
| Agent | 主 / 子 Agent + Markdown Skill + 受控业务工具 | 按需加载 Skill、多轮工具调用、草稿修复、持久化运行与上下文压缩 |
| MCP | Java MCP SDK + 本地 STDIO Server | 通过现有 Spring API 操作项目、分集、镜头与 Mock 出图 |
| 契约 | `packages/contracts` + springdoc OpenAPI | API、任务事件、工具入参出参 |

目录是职责边界。阶段一至六默认使用数据库租约轮询；阶段七增加 Outbox + RabbitMQ 与同代码库独立 Worker，任务事实和租约继续留在 PostgreSQL。第八阶段已增加可选 Redis 共享生成提交准入，故障时暂停新提交；默认仍无需 Redis。

选 Java 21 的理由不是语言偏好，而是求职面：会 Java 可以投 Go 岗位，反向不成立。项目本身瓶颈在外部视频 API 的分钟级等待，不在并发吞吐。

数据库选 PostgreSQL 而不是 SQLite 的原因：SQLite 是单写者模型，没有 PostgreSQL 的行级锁与 `FOR UPDATE SKIP LOCKED` 任务领取机制。SQLite 也支持部分唯一索引，不能把它当作区别。完整对比见 [tech-design.md §15](docs/tech-design.md)。

## 这个仓库是原型，不是产品

目标是证明后端、Agent 与全栈能力，因此主动不做多租户、计费、内容审核、多供应商矩阵、高可用、移动端与成片剪辑。反过来说，任务状态机与幂等、租约与崩溃恢复、作用域隔离、版本与来源追溯这四项必须做扎实，它们是所有结论的依据。

完整边界与表述纪律见 [tech-design.md §16](docs/tech-design.md)。

## 项目结构

```text
VibeStory/
├── apps/
│   ├── web/                  # React 创作工作台（已接后端，保留离线演示）
│   ├── api/                  # 业务规则、任务 Worker 与持久化 Agent 运行时
│   ├── agent/                # 受控创作工具共享契约
│   └── mcp/                  # 本地 STDIO MCP Server
├── skills/                  # 可编辑 Markdown Skill；个人覆盖不提交
├── packages/
│   └── contracts/            # API、任务、事件与工具共享契约
├── design/
│   ├── README.md             # 设计系统说明与页面对应
│   ├── theme.js              # Tailwind 主题令牌
│   ├── tokens.css            # 框架无关的 CSS 变量与组件类
│   └── assets/               # Audiowide 字体、画面风格预设图标
├── docs/
│   ├── product.md            # 产品定位、用户路径、功能与数据模型
│   ├── tech-design.md        # 技术方案、事务边界、任务机制
│   └── roadmap.md            # 实现顺序、标记与阶段验收
└── infra/                    # 后续部署、数据服务与观测配置
```

## 当前状态

**截至 2026-11-05，当前原型范围已实现并完成全 Mock 验收。** 在原有生成链路上增加自然语言工作台、团队协作、分镜方案、主 / 子 Agent、文件 Skill 库、三层上下文压缩，以及图片纠正、明确评价、作用域偏好和版本化创作配方。最新独立 PostgreSQL 16 验收通过 189 项后端、17 项组件和 17 项 Chrome 用例，包含类型检查、契约生成、前端构建与 HTTP 崩溃恢复；失败、跳过与浏览器重试均为零。详见[图片学习验收](docs/image-learning.md#2026-11-05-验收结果)。这些结果不扩大历史真实供应商验证范围，也不代表线上部署或真实创作质量验证。

旧开发库曾执行现已缺失原文件的 V18 迁移；沿用该库前需恢复原文件，或使用独立新库。当前独立库验收通过，迁移限制见[阶段十二记录](docs/backend-phase12.md)。

**前端核心 API 联调已完成**：真实会话登录、项目 / 分集 / 正文、拆解与镜头编辑、资产版本与上传、分镜图候选选择、视频生成与播放器均接后端。TanStack Query 缓存按用户分隔，活动集三秒轮询、失焦停止；保存失败保留草稿。后端不可用时明确显示离线演示，使用独立的浏览器数据。设置页只读显示后端实际模型，不保存供应商密钥。

**阶段一、二后端已实现**：Java 21 + Spring Boot 3.5 + Gradle Wrapper，Flyway + PostgreSQL 16。包含登录与作用域隔离、项目 CRUD / 搜索 / 统计、自定义参考图、分集编辑与正文持久化，以及 Mock 视频任务与租约 Worker。正文变更保留镜头，视频保留提交时首帧；删除项目级联清理现存关联数据，旧 Worker 无法写回，媒体通过持久化清理记录最终删除。`packages/contracts` 保存 springdoc OpenAPI 与生成的 TypeScript 类型。36 项自动化测试及真实 HTTP / JVM 重启验收通过；本机通过 Homebrew PostgreSQL 16 验证，Docker / Testcontainers 路径尚未在本机执行。详见 [阶段一](docs/backend-phase1.md)与[阶段二说明](docs/backend-phase2.md)。

**阶段三后端已实现**：持久化拆解任务、正文与镜头修订号校验、覆盖确认、单镜编辑 / 确认 / 取消确认、整集确认、项目内人物 / 场景名称解析。支持 Mock 与 OpenAI-compatible JSON 文本输出；本机已配置 `deepseek-flash`，真实业务 API 验证以 1 次尝试将 850 字符剧本拆成 36 镜，并在两次 JVM 重启后恢复编辑结果。原始响应保存在有作用域校验的尝试诊断中，密钥仅在忽略的本地环境配置中。完整验证范围见 [阶段三说明](docs/backend-phase3.md)。

页面中没有入口的功能（导出、用量、镜头增删排序、封面图上传）本期不做，后端不为其预留表结构。

## 工程主线

- **后端**：作用域隔离、任务状态机、租约轮询、幂等与重试、资产版本、生成来源快照。
- **Agent**：受控工具、可编辑的结构化输出、人工确认、按镜头定向修订。
- **全栈**：原创 UI、明确的任务状态、局部返工、来源追溯、错误恢复。

**阶段四后端已实现**：资产上传 / 生成版本与基准选择、项目 / 全局资产使用统计、分镜图任务与候选选择、提交时来源快照，以及 Mock / MiniMax 文生图适配。MiniMax `image-01` 仅提交一次真实文生图，1 次 attempt、1 张 1280×720 JPEG；付费任务不自动重放。图片与资产选择经两次 JVM 重启恢复，参考图能力以本机 fixture 验证。详见 [阶段四说明](docs/backend-phase4.md)。

**阶段五已完成**：MiniMax 原生图生视频、提交时首帧冻结、外部任务号持久化、重启后只恢复查询、失败与 UNKNOWN 分流，以及 MP4 校验 / 实测时长。真实供应商生成仅 1 次，复用已有图片，输出 1364×768、实测 5.875 秒；原业务任务另有 1 次本地配置拒绝，共 2 个本地 attempt。111 项后端测试及免费浏览器联调通过，详细边界和样片见 [第五阶段](docs/backend-phase5.md)。

**阶段六收尾**：新增按用户隔离、可跨重启查询的任务指标和提交后结构化日志；补齐组件测试、实际视频时长展示与免费验收入口。用户已取消录屏。本轮所有生成测试均用 Mock，验收范围和环境限制见 [第六阶段](docs/backend-phase6.md)。

当前数据库模式回归为 **136 项后端 + 17 项组件 + 6 项 Chrome 测试**，以及真实 HTTP 续约 / SIGKILL / 重启回归；真实 RabbitMQ 专项单独统计于阶段七记录。证据对应如下；历史真实供应商验证的范围不随 Mock 测试扩大。

| 能力 | 实现位置 | 验证入口 |
| --- | --- | --- |
| 登录、用户作用域、项目 / 分集 / 正文、风格 | `auth`、`common/ScopeGuard`、`project`、`episode`、`style` | `BackendIntegrationTest`、`PhaseTwoIntegrationTest`、HTTP / Chrome |
| 拆解、编辑确认、人物场景名称解析 | `breakdown`、`shot` | `PhaseThreeIntegrationTest`、Chrome |
| 资产版本、候选图和提交时来源 | `asset`、`image` | `PhaseFourIntegrationTest`、组件 / Chrome |
| 视频、首帧、幂等、租约与恢复 | `task`、`provider` | `PhaseFiveIntegrationTest`、并发 / SIGKILL / Chrome |
| 任务指标与提交后日志 | `task/TaskMetrics`、`task/TaskEvents`、`common/ApiErrors` | `PhaseSixIntegrationTest`、跨重启 HTTP、Chrome |
| 批次、Outbox、RabbitMQ / 独立 Worker、死信与恢复 | `batch`、`dispatch`、`task` | `PhaseSevenIntegrationTest`、`rabbitTest`、MQ HTTP / Chrome |
| 前端草稿、筛选、错误恢复与媒体播放 | `apps/web/src/lib`、`features` | Vitest 组件测试、Playwright 浏览器验收 |

**阶段七已实现**：整集图片 / 视频批次、事务 Outbox、RabbitMQ 4.4.0 分类队列、独立 API / Worker、双 Worker 协作、租约恢复、死信暂停与显式恢复，以及派发指标。页面批次刷新可恢复；生成输入与来源继续冻结。本轮全部 Mock / 本机 fixture，运行与实际故障验收见 [阶段七说明](docs/backend-phase7.md)。

以上后端路径均位于 `apps/api/src/main/java/com/vibestory`，测试位于相应 `src/test`；完整验证命令和风险对应测试见第六阶段记录。Agent 受控工具编排仍是后续方向，不包含在这七阶段完成声明中。

本机启动：`scripts/dev-api.sh` 与 `npm run dev --prefix apps/web`。登录使用本机 `.env` 种子账号；免费开发用第六阶段文档中的 Mock 启动命令，全套免费回归用 `scripts/verify-all-mock.sh`。本机 `.env` 原有 DeepSeek / MiniMax 配置保留，不会被验收脚本改写。


MQ 本机启动：`scripts/dev-mq.sh api` 与两个终端分别运行 `scripts/dev-mq.sh worker`，共享 PostgreSQL / RabbitMQ 和同一绝对媒体根目录。免费 MQ 验收使用 `scripts/dev-test-rabbitmq.sh` + `scripts/verify-phase7-mock.sh`，具体隔离端口与可丢弃节点配置见阶段七文档；数据库模式全套回归仍用 `scripts/verify-all-mock.sh`。

**阶段八已实现**：Redis 服务端时间 + Lua 令牌桶，多 Worker 共用生成提交额度；额度不足时数据库延迟派发、零新增 attempt，短许可在实际请求前校验。Redis 故障关闭、单一恢复代次从零补充，已有外部任务号继续查询恢复。运行 `scripts/dev-redis.sh api|worker`，真实 Redis 专项与本机故障证据见 [阶段八记录](docs/backend-phase8.md)。

## Codex MCP

VibeStory 提供本地 STDIO MCP Server。Java MCP SDK 处理协议，工具通过现有 Spring API 登录并执行分镜操作。任务 ID、改镜提案和出图批次都由原后端保存。MCP 提交拆镜时保护已有镜头，改镜预览与保存分开。改镜提案当前用于个人项目。

1. 在仓库根目录执行 `scripts/java21.sh :mcp:installDist`。
2. 启动本机 Mock API。以下命令在读入现有数据库和登录配置后切换 Provider。

   ```bash
   set -a
   source .env
   set +a
   TEXT_PROVIDER=mock IMAGE_PROVIDER=mock VIDEO_PROVIDER=mock MOCK_ONLY=true \
     TEXT_API_KEY='' IMAGE_API_KEY='' VIDEO_API_KEY='' \
     DISPATCH_MODE=database APP_ROLE=all ADMISSION_ENABLED=false WORKER_ENABLED=true \
     scripts/java21.sh :api:bootRun
   ```

3. 用 Codex 打开并信任本仓库，重新开启会话。项目的 `.codex/config.toml` 会注册 `vibestory`，可用 `/mcp` 查看。项目配置将写工具设为审批模式。MCP 默认使用本机 `.env` 的种子账号和 `PORT`。可在同一文件用 `VIBESTORY_MCP_EMAIL`、`VIBESTORY_MCP_PASSWORD`、`VIBESTORY_MCP_URL` 覆盖。

可以对 Codex 说「用 VibeStory 把《雨巷》第 2 集已保存的正文拆成镜头，完成后列出来」。`list_projects` 用来定位分集，`submit_breakdown` 和 `get_task` 跟踪拆镜，`list_shots` 读取结果。改镜使用 `preview_shot_edit` 和 `apply_shot_edit`，也可用 `discard_shot_edit` 放弃。整集确认后可用 `submit_image_batch` 和 `get_image_batch` 提交和查询指定镜头的出图批次。

所有提交生成的 MCP 工具要求对应 Provider 为 Mock。应用改镜提案也要求图片 Provider 为 Mock，因为站内创建的部分提案会同时出图。预览和出图批次使用调用方提供的 UUID，同一次请求重试时复用。

协议验收可用 `scripts/java21.sh :api:bootJar :mcp:installDist` 构建，再向 `python3 scripts/verify-mcp.py` 提供本机临时 PostgreSQL 库的 `DATABASE_URL`、`DATABASE_USER`、`DATABASE_PASSWORD`。验收库名以 `vibestory_mcp_` 开头。脚本使用独立账号和存储目录，强制全 Mock。
