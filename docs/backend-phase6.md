# 第六阶段：联调与验收收尾

实现与验收日期：2026-11-02。范围是已有创作链路的观测、组件测试、故障恢复验收与文档，不增加产品功能。用户已取消录屏，本阶段不产出录屏。

## 任务观测

新增登录后可读的 `GET /api/task-metrics`，仅统计当前用户最近 24 小时创建的任务，按任务类型与供应商分组。直接读取 PostgreSQL 的任务 / 尝试记录，不使用 JVM 内存累计，因此重启后保留；删除业务历史后，对应样本也会移除。

| 字段 | 口径 |
| --- | --- |
| `queued/running/succeeded/failed/unknown/canceled` | 当前任务状态数量；一个任务只计一次，不因 attempt 增加而重复 |
| `knownOutcomes` | `succeeded + failed` |
| `failureRate` | `failed / knownOutcomes`，0～1；UNKNOWN、取消、排队与执行中均不进入分母；无已知终态时不返回该值 |
| `firstQueueWait` | 从任务创建到第一次 attempt 开始的毫秒数，含样本数、平均与最大值；不冒充重试排队时长 |
| `attemptElapsed` | 已结束 attempt 的开始到结束的墙钟时长，含成功、失败、UNKNOWN、取消和租约放弃；恢复原 attempt 时包含中断 / 恢复等待，不等于供应商纯执行时间 |

无时长样本时返回 `samples: 0`，省略平均与最大值。统计窗口和数据使用同一数据库只读快照；任务聚合与 attempt 时长先分开，避免多次尝试把任务数放大。没有新增费用页面、监控服务或性能指标承诺。

控制台使用 Spring Boot 原生 Logstash JSON 格式（[官方说明](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html#features.logging.structured)）。生命周期事件包括 `attempt_started`、`attempt_resumed`、`attempt_finished`、`task_terminal`；记录任务 / attempt 身份、状态与提交时的首帧或资产版本 ID。只输出允许的元数据，不序列化提示词、文件路径、接口地址、原始响应或密钥。事务提交后才输出状态事件，回滚与失效租约不输出成功事件；日志不是事务消息，崩溃仍以数据库记录为准。

作用域拒绝经统一异常处理记录 `scope_not_found`，只包含访问者 ID、HTTP 方法和路由模板。对外保持 404；日志也不区分不存在和外部用户资源，不泄露目标归属、原始 URL 或查询参数。

## 前端补全

新增 Vitest + React Testing Library 组件验收，覆盖视频 / 分镜筛选与计数、前置条件、任务徽标、无镜头 / 无确认 / 无筛选结果的空态、旧视频与失败新尝试共存、首帧历史来源。组件测试禁止使用 `fetch`。

修正视频总时长、视频预览和分镜预览中的视频时长：优先使用成片 `durationMs`，历史没有实测时长时才回退到镜头计划值。镜头参数继续展示计划时长。

浏览器验收补充：切换候选图等待服务器持久化、提交视频等待任务被接受，再验证生成期间切回原图；取消重新拆解保留原产物；保存失败时实际点击确认拆解，断言没有提交生成请求；观测接口只能看到当前账号。没有用固定延迟代替业务完成条件。

## 免费回归入口

```sh
# 安装依赖后，一条命令完成本阶段验收；自动读取忽略的 .env
npm ci --prefix apps/web
npm ci --prefix packages/contracts
scripts/verify-all-mock.sh
```

入口顺序运行：Java 测试与 bootJar → springdoc 导出 → TypeScript 契约生成 → 组件测试与类型检查 → 前端构建 → HTTP / SIGKILL / 重启 → Chrome 浏览器流程 → diff 检查。任一步失败即停止，`data/verification/phase6-*/summary.json` 保存真实结果和分步骤日志；不会把失败运行改成成功。

验证进程显式强制三个供应商为 Mock，清空三个 API Key，并启用 `MOCK_ONLY=true`；只要误配任一真实适配器，应用启动即失败。Worker 仍只领取当前配置对应的任务，不会消费数据库内真实生成任务。浏览器只允许访问本机验收地址。旧的真实供应商协议测试使用本机 HTTP fixture，不发送真实生成请求。没有运行真实生图 / 视频脚本，也没有修改已有视频的一次性标记或本机正式模型配置。

日常免费联机使用：

```sh
scripts/dev-api.sh --args='--vibestory.mock-only=true --vibestory.text.provider=mock --vibestory.image.provider=mock --vibestory.video.provider=mock'
npm run dev --prefix apps/web
```

## 验收记录

- 后端：117 项测试通过，零失败、零跳过；比第五阶段新增 5 项观测集成测试、1 项 Mock-only 配置测试。
- 组件：13 项测试通过；单独 TypeScript 检查通过。
- 真实 HTTP：登录、10 次重复提交、单次活跃任务 / attempt、续约、SIGKILL 后 attempt 2 恢复、跨用户 404，以及阶段二至四的持久化 / 来源 / 清理回归通过。任务指标经 JVM 重启保留。
- Chrome 完整联调：5 项通过，零重试、零跳过；包括登录到播放、保存失败阻止生成、会话 / scope、离线模式、自定义风格。
- OpenAPI 版本为 `0.6.0`，共享类型重新生成；前端生产构建通过。

最终全套成功记录：`data/verification/phase6-66d3ed5a98/summary.json`；Chrome 截图与测试报告在 `data/verification/web-411b31cf78/`，HTTP 服务日志在 `data/verification/` 对应本轮运行目录。此前 `phase6-f5ff3fe120` 的浏览器测试发现点击选图后立即读取接口的验收时序问题；补上服务器状态等待后，全套重新通过，失败记录保留。

六条关键风险的可执行证据：

| 风险 | 测试 / 脚本 |
| --- | --- |
| 十次同输入并发只落一个活跃任务和 attempt | `BackendIntegrationTest.tenConcurrentSubmissionsAndClaimsCreateOnlyOneTaskAndAttempt`，HTTP 验收 |
| 崩溃后租约恢复，旧 Worker 不能写回 | `BackendIntegrationTest.expiredLeaseIncrementsAttemptAndFencesOldWorkerWrites`，HTTP SIGKILL |
| 生成期间 / 完成后切图仍保留原首帧 | `PhaseFiveIntegrationTest.selectedImageChangeDoesNotRewritePendingOrReadySourceAndDraftCannotRetry`，Chrome / 组件验收 |
| 其他用户访问镜头 / 分集返回 404 | `BackendIntegrationTest.allResourceLevelsHideOtherUsersData`，阶段二至四 scope 测试 |
| 重新拆解清理旧产物并保留任务历史 | `PhaseThreeIntegrationTest.rebreakdownDeletesOnlyCurrentEpisodeMediaAndKeepsTasksAttemptsAndAssets` |
| 重复人物名只建一张卡 | `PhaseThreeIntegrationTest.repeatedCharacterAndSceneNamesResolveOnceAndScopeReferencesToProject` |

本机没有 Docker，集成验收使用真实 Homebrew PostgreSQL 16 的随机隔离 schema，未执行 Docker / Testcontainers 启动分支。Testcontainers 默认路径保留且不会静默跳过或降级为 H2。阶段一至五的真实供应商记录属于此前验证，本轮全部使用 Mock，不增加真实图片或视频调用。没有进行性能基准、视觉质量评测、部署上线或 Agent 工具编排验收。
