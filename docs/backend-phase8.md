# 阶段八：Redis 共享生成提交准入

2026-11-02。基于阶段七的 PostgreSQL / Outbox / RabbitMQ / 独立 Worker 增加共享令牌桶。Redis 只决定一次新生成提交能否开始，任务事实、attempt、幂等、来源与租约继续由数据库保存。本轮全部生成使用 Mock，外部任务恢复仅连接本机 HTTP fixture；无真实图片或视频生成。

## 实现与边界

- `admission/AdmissionProperties` 用供应商 + generation 接口组 + 不透明共享凭证配置 ID 分桶。MiniMax 图片、资产图、视频共用 media 桶；DeepSeek 文本独立。用户、项目、Worker 名称不参与分桶；Mock/live 分离。Redis key 使用配置身份的 SHA-256，不从 API Key 派生，也不保存正文或媒体。
- 同凭证的配置别名必须显式映射到同一 `*_CREDENTIAL_ID` 和 `ADMISSION_NAMESPACE`；本期不自动识别密钥别名。图片 / 视频只有一个 `MEDIA_CREDENTIAL_ID`，不增加供应商矩阵。所有进程须使用相同准入开关和配置。桶的 rate、burst、TTL 配置签名持久化，配置冲突时停止准入；修改参数应在停掉所有 Worker 后统一更新对应数据库配置记录，再启动，不能滚动混用两组参数。
- `acquire.lua` 原子读取 Redis `TIME`、补充令牌、扣减并返回允许结果 / `retryAfterMs`。配置语义为令牌桶 `burst + rate × 区间长度`，不是任意一秒绝不超过固定次数。每 Worker 的类型 Semaphore 仍只约束本进程在跑数量，没有新增全局在途并发许可证。
- 消费者只读核对当前通知 / 作用域 / provider / 目标 / 到期状态后取得本地槽位，申请令牌后才条件领取。拒绝或 Redis 错误通过短事务修改 `not_before`、等待原因与派发轮次；V9 触发器在同一事务写入新 Outbox。当前通知 ACK、释放槽位；任务保持 QUEUED，不创建 attempt、不写生成失败、不热 requeue。领取竞态可保守消耗额度，不退款。
- 许可有效期使用单调时钟，从 Redis 调用前开始计时，包含网络与落库耗时。适配器在完成本地图片 / 首帧 / JSON 准备后、实际发出新请求之前调用一次性 `SubmissionBoundary`。许可过期则在同一 attempt 中重新申请、保持续约；本地心跳已判定失效或数据库租约失效均禁止调用。不预取、不跨任务复用，也不重复提交一次执行上下文。
- Redis 连接、超时、脚本错误均停止新生成提交。已发出的调用继续收尾；已有视频 external ID 的查询 / 下载恢复不消耗提交额度、不重新创建 attempt。查询 QPS 沿用原有间隔，本阶段没有限制供应商所有 HTTP 操作。
- `INFO server.run_id` 和持久 marker 用于检测 Redis 重启 / 桶丢失。`generation_bucket` 在数据库行锁内推进单一恢复代次；Redis 重建在锁外执行，Lua 校验代次与随机恢复 token，重复完成不重复补充。重建从零开始，数据库激活后再准入。初次初始化、空闲 TTL 过期和意外丢失均保守从零恢复；可能少发，不凭空获得突发额度。
- token hash TTL 为 `ceil(burst / rate × 1000) + ttlMargin`，marker 不过期。运行单节点、`noeviction`；不承诺 Redis 主从切换、系统时钟跳变或跨数据库部署的严格额度。`INFO`、Lua、hash、TIME、过期命令须由部署 ACL 允许，否则故障关闭。
- V10 新增桶恢复与准入事件表、任务等待字段，未修改 V1～V9。事件只含任务 / attempt / 桶身份、允许 / 拒绝 / 错误、Redis 准入时间、提交边界时间和等待时长。`submitted_at` 是应用发起边界，不等于远端接收或业务成功。允许数可高于调用数，例如领取竞争或许可过期；拒绝和 Redis 错误不进入供应商生成失败率。

本期不增加缓存、Redis 任务锁 / 队列、全局在途许可证、计费 / 每日配额、Sentinel / Cluster 或新管理页面。

## API 与页面

OpenAPI 0.8.0，前端契约由 springdoc 生成：

| 接口 / 字段 | 行为 |
| --- | --- |
| `GET /api/admission-metrics` | 当前用户的等待任务数；按共享桶 ID 聚合该用户的允许、拒绝、错误、许可过期、提交次数和累计等待毫秒。不暴露其他用户的额度使用明细 |
| `Task.waitReason / notBefore` | 持久化等待原因与下次可执行时间；仍为 queued/running，不增加任务状态 |
| `GenerationBatch.Member.waitReason / notBefore` | 刷新恢复等待信息；页面显示「等待生成名额」或「生成服务暂缓接收，等待恢复」，自动继续 |

准入等待不提供人工“重试生成”入口，原有死信暂停仍由明确恢复入口处理。任务终态清理等待提示；已有历史产物与来源不变。

## 本机启动

默认 `ADMISSION_ENABLED=false`，数据库模式与纯 RabbitMQ 模式均可继续运行。启用 Redis 必须使用 rabbit 模式，配置错误会拒绝启动，不能通过数据库 Worker 绕过领取前准入。

```sh
# PostgreSQL 16、RabbitMQ 4.4.0、Redis 8.10.3 已启动。
# 所有进程共享数据库、媒体绝对路径、MQ namespace 和准入配置。
scripts/dev-redis.sh api
scripts/dev-redis.sh worker
scripts/dev-redis.sh worker
npm run dev --prefix apps/web
```

`dev-redis.sh` 读取本机 `.env` 后强制三类 Provider 为 Mock、清空当前进程供应商密钥、启用 rabbit + Redis，显式使用 JDK 21。`.env.example` 包含 Redis 连接、两个共享配置 ID、rate/burst、短许可与错误退避。默认演示值 1 次/秒、burst 2，不代表真实供应商限额。Redis 凭证仅放本机环境，前端与业务 API 不返回。

Compose 固定 `redis:8.10.3-alpine`，回环端口、持久化 AOF、noeviction。本机实际使用 Homebrew Redis 8.10.3、RabbitMQ 4.4.0、PostgreSQL 16；未运行 Docker / Testcontainers 启动分支。

## 验证入口与证据

```sh
# 两个终端分别保持可丢弃的本机测试服务运行：
scripts/dev-test-rabbitmq.sh
scripts/dev-test-redis.sh

# 真实中间件专项。多进程部分另起并独占一个随机端口 Redis，
# 仅停止 / 重启它自己创建的进程，不操作共享 Redis 服务。
TEST_REDIS_PORT=6389 \
TEST_RABBITMQ_PORT=5679 \
TEST_RABBITMQ_MANAGEMENT_PORT=15679 \
  scripts/verify-phase8-mock.sh

# 原有数据库模式基线，显式关闭准入，全部免费。
scripts/verify-all-mock.sh

# 原 MQ 回归，显式关闭准入；只重启可丢弃的本机 RabbitMQ 节点。
TEST_RABBITMQ_PORT=5679 \
TEST_RABBITMQ_MANAGEMENT_PORT=15679 \
TEST_RABBITMQ_NODE=vibestory_phase7@localhost \
  scripts/verify-phase7-mock.sh
```

真实 Redis 专项 `redisTest` 与普通 `test`、`rabbitTest` 分开执行，默认测试排除两个中间件标签，不能把未运行的专项算通过。

- **17 项 Redis 专项**：真实 Redis Lua / 配置身份与独立容量 / 多线程令牌桶上界 / 延迟 Outbox / 越权与旧通知 / 桶丢失竞争 / TTL / 幂等恢复 / 配置冲突 / 实际脚本错误 / 本地准备导致许可过期后同 attempt 再申请 / 租约与心跳失效 / 用户指标隔离 / 终态等待清理。含真实 MQ 通知 + 本机 HTTP fixture 的 external ID 恢复，0 次创建、1 次查询、1 次下载，复用原 attempt。JUnit：`apps/api/build/reports/tests/redisTest/index.html`。
- **6 组多进程 / 故障 / 页面检查**：两个独立 JVM 共享图片 / 视频桶；重复通知不额外提交；Redis 进程实际停止、Worker 重启、Redis 新 run_id 恢复及单桶删除；另运行 1 项 Chrome 批量页面用例。`MockSubmissionProbe` 在实际 Mock 适配器入口记录 task/attempt/许可 ID / 时间，用独立文件与数据库准入记录核对调用次数和许可年龄。逐代次验证原子准入曲线；恢复后从零补充。它证明应用准入和本机 Mock 调用，不冒充供应商端精确 QPS 或吞吐提升。
- **数据库模式回归 136 项后端、17 项组件、6 项 Chrome**，含生产构建、组件类型、OpenAPI 导出 / 类型生成与 HTTP SIGKILL / 租约恢复。
- **RabbitMQ 专项 11 项**与阶段七独立 JVM / 死信 / 崩溃 / Broker 重启 / 页面检查继续单列，不混成一次默认 test。

最终证据：

| 验证 | 本机摘要 / 报告 |
| --- | --- |
| Redis 双 JVM / 停机重启 / 页面 | `data/verification/phase8-e60f30fc08/summary.json` |
| 独立 Mock 入口记录、准入曲线 | 同目录 `*-probe.jsonl`、`admission-audit.json` |
| Redis 17 项专项 | `apps/api/build/reports/tests/redisTest/index.html` |
| 数据库模式完整基线 | `data/verification/phase6-b993af14f8/summary.json` |
| 六项基线 Chrome | `data/verification/web-4665152562/browser/` |
| MQ 独立进程 / 故障 / 页面回归 | `data/verification/phase7-ae5ad632fe/summary.json` |
| MQ 11 项专项 | `apps/api/build/reports/tests/rabbitTest/index.html` |

Redis 多进程验收记录了 31 次实际 Mock 适配器进入（28 次批次故障场景 + 页面 3 次），与持久化提交记录逐项对齐，无重复 task；准入到入口的最大本机间隔约 236ms，低于本轮 2s 许可。这是本次运行观察，不是性能指标或真实供应商时延结论。许可过期由独立的 100ms 许可专项注入验证，不把本轮未过期记录当成过期覆盖。

所有过程使用隔离 PostgreSQL schema / MQ namespace / 媒体目录，结束后清理；日志、探测文件、准入曲线和摘要保存在忽略的 `data/verification/`。Redis 17 项与 RabbitMQ 11 项加数据库 136 项为 164 项后端测试，但分三次任务运行，不能称作默认 test 一次执行 164 项。

实现期间发现并修复了多 Worker 恢复完成与旧 RECOVERING 观察之间的竞争，避免同一次状态丢失推进多个代次。后续仅在适配器的新请求边界校验短许可，避免本地媒体准备耗尽许可后无检查提交。没有新增真实供应商验证、集群故障或性能结论。

协议依据：[Redis Lua 原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)、[Redis TIME](https://redis.io/docs/latest/commands/time/)、[Redis 共享限流](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)。验收范围以本机实际测试为准。
