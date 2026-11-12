# 阶段七：整集批量生成与 RabbitMQ

> 历史记录。当前规格和使用方式见[项目文档](../../README.md#验证与文档)。

2026-11-02。实现基于阶段一至六，保持 Java 21、PostgreSQL 16 与原有状态机；Redis 和 Agent 编排尚未实现。所有本轮生成使用 Mock；唯一非 Mock 适配器恢复检查连接本机 HTTP fixture，仅查询 / 下载，不提交生成。

## 已实现

- 分镜页 / 视频页新增本集批量入口：选择至多 200 镜、显示前置提示，逐镜返回 `accepted / reused / blocked`。不自动确认、选首帧或连跑下一步。刷新通过服务端最近十个批次恢复状态；单镜生成继续可用。
- `generation_batch` 保存用户 / 项目 / 集与请求键，成员保存镜头、接受结果及 task ID。同键同内容复用整个批次，同键不同内容返回 409；新一轮生成使用新请求键。不同输入的活跃任务在批量提交时报告冲突，单镜既有 nonce 语义保持兼容。
- V9 新迁移新增批次、Outbox、派发轮次、`not_before`、派发阻塞与死信归档。触发器保证任意提交 / 重试路径的 QUEUED 状态和 Outbox 在同一事务：首次排队生成一条通知，恢复 / 重试轮转身份；回滚不会留下任务或通知。数据库模式也写入 Outbox，但不运行 Relay。
- Relay 以 `SKIP LOCKED` 和短租约领取，网络发送不占用数据库事务。持久化消息必须同时满足 publisher confirm 与没有 mandatory return 才标记 SENT；租约 owner / 到期条件拒绝旧 Relay 写回。发布后未记发送可重复通知。
- `${MQ_NAMESPACE}.mock` / `.live` 隔离 Mock 与真实任务，各有 `text / image / video` 持久化工作队列及 `.dead` 交换机 / 队列。消息仅含版本、event/task/generation 和内部作用域 ID；正文、媒体、参数、密钥不进入消息。Worker 继续核对持久化 provider、父链与目标身份。
- `DISPATCH_MODE=database|rabbit` 互斥选择本进程的派发入口；MQ 的 API 角色只负责提交、Relay 与协调，独立 Worker 消费。所有进程须使用相同模式、数据库、namespace 与绝对媒体根目录，勿混跑数据库 Worker 与 MQ Worker。
- 消费者在领取事务提交后手动 ACK，随后进入已有执行器；按类型共享本地 Semaphore，ACK 不释放执行名额。`MQ_PREFETCH` 与文本 / 图片 / 视频在跑槽位分别配置。
- 旧通知、有效租约重复、终态和已删除任务不会创建新 attempt。排队等待超时复用原轮次重投，恢复执行才轮转轮次。Mock 过期租约可重放；真实无外部身份进入 UNKNOWN，有视频 external ID 则复用原 attempt 查询 / 下载。
- 发布 / 消费基础设施失败最多五次，数据库时间驱动指数退避，不热 requeue。发布预算耗尽保留数据库派发阻塞；这不伪称 Broker 已收到死信。消费者毒消息或耗尽预算进入真实 DLX，Relay 将其按身份归档并暂停有效排队任务；数据库不可用时不 ACK 死信，通道关闭后留在 DLQ。归档只存摘要和允许的身份，不保存原始消息。
- `GET /api/dispatch-metrics` 按当前用户统计 Outbox 等待、最老等待、发布 / 消费重试、重复通知、关联死信、各类任务排队 / 执行 / 阻塞与租约恢复。无法关联用户的坏消息不暴露到用户指标。`POST /api/tasks/{id}/dispatch/resume` 仅恢复已暂停的 QUEUED 任务；UNKNOWN 仍走原有明确确认重试。

## API 与契约

| 路径 | 行为 |
| --- | --- |
| `POST /api/episodes/{id}/generation-batches` | `{ type: "IMAGE" | "VIDEO", requestKey, shotIds: string[], regenerate }`，202 |
| `GET /api/episodes/{id}/generation-batches?type=IMAGE` | 当前用户 / 集 / 类型的最近十个批次 |
| `GET /api/generation-batches/{id}` | 逐镜接受结果和数据库任务进度；越权 404 |
| `POST /api/tasks/{id}/dispatch/resume` | 修复后显式恢复派发，创建新派发身份，不增加 attempt |
| `GET /api/dispatch-metrics` | 当前用户持久化派发指标 |

`GenerationBatch.state=complete` 仅表示所有成员有结局；阻塞、失败、未知和取消分别计数，不能把它等同于全部生成成功。`paused` 表示剩余排队成员全部派发暂停。OpenAPI 版本 0.7.0，前端由 springdoc 快照生成，不手写另一套批次接口类型。内部 MQ v1 JSON Schema 位于 `packages/contracts/task-notification.v1.schema.json`，字段 / 版本 / 类型由契约测试守护。

## 本地启动

普通数据库模式继续使用 `scripts/dev-api.sh`；全免费基线继续使用 `scripts/verify-all-mock.sh`，脚本显式覆盖模式为 database/all，并清空供应商密钥。

MQ 模式先启动 PostgreSQL 16 和 RabbitMQ 4.4.0。Compose 已固定 `rabbitmq:4.4.0-management`，仅绑定回环地址；本机本轮使用 Homebrew RabbitMQ 4.4.0 / Erlang 29.1.2，未运行 Docker 或 Testcontainers 启动分支。

```sh
# 在 .env 中配置数据库和 RabbitMQ 凭证；dev-mq.sh 强制三类 Provider 为 Mock。
# 每个终端须使用相同的绝对 STORAGE_ROOT。
scripts/dev-mq.sh api
scripts/dev-mq.sh worker
scripts/dev-mq.sh worker
npm run dev --prefix apps/web
```

`dev-mq.sh` 读取本机数据库 / MQ 配置，强制 Mock-only 并清空当前进程供应商密钥，显式 JDK 21；Worker HTTP 端口为随机回环端口（不作为服务发现入口）。RabbitMQ 不可用时 API 仍可启动并接收任务，通知在数据库等待恢复；预算耗尽后须显式恢复派发。队列单节点，无中间件集群或高可用承诺。

## 验证入口与证据边界

```sh
# 无 Docker 的 macOS：先安装固定版本的本机 RabbitMQ；此脚本不改共享服务配置。
scripts/dev-test-rabbitmq.sh

# 另一终端：仅重启该可丢弃的本机测试节点，绝不停止默认节点。
TEST_RABBITMQ_PORT=5679 \
TEST_RABBITMQ_MANAGEMENT_PORT=15679 \
TEST_RABBITMQ_NODE=vibestory_phase7@localhost \
  scripts/verify-phase7-mock.sh

# 最后保留已有阶段的数据库模式回归。
scripts/verify-all-mock.sh
```

MQ 验收独立使用随机 PostgreSQL schema、MQ namespace 与媒体目录，完成后清理 schema / 队列；摘要与日志保存在忽略的 `data/verification/phase7-*`。`rabbitTest` 是显式独立测试任务，每次调用实际执行；普通 `test` 按标签排除它，不能把未运行的 MQ 测试算作通过。

数据库模式全套回归：**136 项后端、16 项组件、6 项 Chrome**，零失败、零跳过；组件 TypeScript、生产构建、springdoc 导出 / 类型生成与原有真实 HTTP 崩溃恢复均通过。摘要：`data/verification/phase6-16140b285f/summary.json`；浏览器：`data/verification/web-90604d7629/`。

真实 RabbitMQ 专项：**11 项**协议 / 消费者测试（7 项协议、4 项实际消费者），零失败、零跳过；另有 **7 组** HTTP / 多进程 / 页面检查通过，包括 **1 项**真实 MQ Chrome 测试（同一批量页面用例另在数据库模式基线中运行，不当作新增独立用例）。最终 RabbitMQ 版本 4.4.0 / Erlang 29.1.2，通过仓库 `dev-test-rabbitmq.sh` 启动的隔离单节点执行。

最终摘要：`data/verification/phase7-3f3be5b597/summary.json`，浏览器报告与故障进程日志在同一目录。真实 Broker JUnit 报告：`apps/api/build/reports/tests/rabbitTest/index.html`；后端共 **147 项**为数据库模式 136 项与 MQ 专项 11 项之和，不能把它们当作同一次默认 `test` 执行。以下列出实际证据。

| 风险 | 实际证据 |
| --- | --- |
| 批次幂等 / 逐镜阻塞 / 整体事务 | 18 项 `PhaseSevenIntegrationTest`；并发 10 个请求只有一个批次，阻塞成员无任务；注入成员写库异常后批次 / task / Outbox 一起回滚 |
| 共享通知契约 | 1 项 `TaskNotificationContractTest`；字段、版本、支持类型对齐 `packages/contracts/task-notification.v1.schema.json` |
| 发布确认 / mandatory return / 持久化 / 分类路由 | `RabbitProtocolIntegrationTest` 使用真实 Broker；未路由返回不标记 SENT，Broker confirm 不投影业务完成 |
| 确认 / SENT 记录丢失窗口 | 真实发布后不记录 SENT，手动过期 Relay 租约后重投；只有一次有效领取。模拟的是故障窗口，没有声称做过网络代理丢确认包 |
| 领取提交后未 ACK | 关闭真实 AMQP 底层通道，Broker 重投；SQL 有效租约拒绝第二次执行 |
| ACK 后崩溃 / 旧 Worker 写回 | 实际 SIGKILL 一个 JVM，由另一个 JVM 接管；Mock 两条 attempt 为 ABANDONED / SUCCEEDED，旧 owner / version 写回被拒绝 |
| 双 Worker 与本地槽位 | 两个独立 JVM 的持久化 owner 前缀证明协作；独立消费者测试确认提前 ACK 后在跑数量仍受槽位限制 |
| MQ 停止 / 恢复 | 对隔离节点实际 `stop_app / start_app`，API 仍接受任务；Outbox 重试恢复，仅一个业务 attempt |
| 数据库基础设施错误 | 在真实消费者领取 / 失败记账边界注入 SQL 异常；有界 Outbox 重试、耗尽进入真实 DLX；记账也失败则拒绝到 DLQ，不伪装成资源已删除。没有停止本机共享 PostgreSQL 服务 |
| 毒消息 / 人工恢复 | 不支持版本通过实际消费者进入 DLX，身份归档后暂停协调器；显式恢复生成新通知，失败派发不增加 attempt |
| 外部任务恢复 | 真实 MQ 通知 + 本机 HTTP fixture；0 次创建、1 次查询、1 次下载，复用原 attempt；不自动重放 UNKNOWN |
| 页面与来源 | Chrome 经真实 MQ 批量提交图片 / 视频，显示阻塞并在刷新后恢复；生成期间换资产基准 / 首帧仍保留提交来源；单镜回归通过 |

验收中发现并修复了 MQ 关闭时初始化线程与容器列表的竞争；生命周期现在在锁内关闭并禁止再次初始化。首轮全套回归的两项浏览器失败来自并行重建覆盖运行中 JAR，日志为类加载失败；验收脚本改为复制固定制品后已重跑全套通过。失败记录保留于 `data/verification/phase6-afa380b4c8/`，不计入通过证据。

协议依据：[RabbitMQ confirms 与 ACK](https://www.rabbitmq.com/docs/confirms)、[Spring AMQP confirms / returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html)、[RabbitMQ DLX](https://www.rabbitmq.com/docs/dlx)。这些文档说明协议语义；本项目验证范围以实际测试与本机故障注入为准。没有外部 exactly-once、吞吐提升、真实批量供应商负载或高可用结论。
