# 本地开发与验收

从 [README 的快速开始](../README.md#快速开始)启动离线演示或完整 Mock 工作台。以下命令都从仓库根目录运行。macOS 上的开发脚本通过 `java_home -v 21` 选择 JDK 21；环境变量说明见 [.env.example](../.env.example)。

## 本地运行模式

| 模式 | 启动入口 | 所需服务 |
| --- | --- | --- |
| 默认数据库派发 | `scripts/dev-api.sh` | PostgreSQL 16；默认 `DISPATCH_MODE=database`、`APP_ROLE=all`。按快速开始在 `.env` 启用全 Mock。 |
| RabbitMQ 派发 | 两个终端分别运行 `scripts/dev-mq.sh api`、`scripts/dev-mq.sh worker` | PostgreSQL 16、RabbitMQ 4.4.0；脚本强制 Mock。 |
| RabbitMQ + Redis 准入 | 两个终端分别运行 `scripts/dev-redis.sh api`、`scripts/dev-redis.sh worker` | PostgreSQL 16、RabbitMQ 4.4.0、Redis 8.10.3；脚本强制 Mock 并启用共享准入。 |

可用 [Compose 配置](../infra/docker-compose.yml)启动本地依赖。在 RabbitMQ 模式下，可启动多个 Worker；API 和 Worker 必须共享数据库、派发模式、MQ namespace、绝对媒体根目录和 Skill 根目录。启用 Redis 时还需统一准入配置、凭证配置 ID 与速率／突发容量。不要让数据库模式和 RabbitMQ 模式的 Worker 消费同一个库。

### Flyway 迁移

使用新建的 PostgreSQL 16 库启动，Flyway 会应用[仓库里的迁移](../apps/api/src/main/resources/db/migration/)。如果旧开发库记录了 `V18__storyboard_creative_memory.sql`，当前仓库缺少该文件。继续用旧库需从其原版本找回原始迁移；也可改用新的独立库。不要编辑已执行的迁移或用空文件补 V18。

## 验收入口

先安装前端和契约依赖，并准备本机 Chrome：

```sh
npm ci --prefix apps/web
npm ci --prefix packages/contracts
```

- **数据库模式全量 Mock**：用 `VERIFY_DATABASE_URL` 指向专用 PostgreSQL 16 验收库（数据库用户名和密码沿用本机 `.env`），然后执行 [`scripts/verify-all-mock.sh`](../scripts/verify-all-mock.sh)。脚本关闭 Redis 准入、强制三类供应商为 Mock，执行后端测试、契约生成、组件测试、类型检查、构建、HTTP 崩溃恢复和浏览器验收。使用本机 PostgreSQL 跑 Java 集成测试时，也需配置 `TEST_DATABASE_URL`、`TEST_DATABASE_USER`、`TEST_DATABASE_PASSWORD`，测试使用独立 schema。
- **RabbitMQ 专项**：用 [`scripts/dev-test-rabbitmq.sh`](../scripts/dev-test-rabbitmq.sh)启动可丢弃的本地节点。按[阶段七验收参数](archive/backend-phase7.md#验证入口与证据边界)运行 [`scripts/verify-phase7-mock.sh`](../scripts/verify-phase7-mock.sh)，脚本会重启所指定的测试节点。
- **Redis 专项**：在隔离 RabbitMQ 和 [`scripts/dev-test-redis.sh`](../scripts/dev-test-redis.sh)启动的 Redis 上，按[阶段八验收参数](archive/backend-phase8.md#验证入口与证据)运行 [`scripts/verify-phase8-mock.sh`](../scripts/verify-phase8-mock.sh)。多进程故障验收另起自己的 Redis 进程。

只需更新 API 契约时，按[共享契约](../packages/contracts/README.md)单独导出。全量验收的日志和摘要保存在本机被 Git 忽略的 `data/verification/`；脚本结束时会打印本轮摘要路径。验收统一走 Mock，不运行归档中旧的真实生成命令。

## 已记录的验收

| 证据 | 实际结果与范围 |
| --- | --- |
| [2026-11-05 数据库模式全量 Mock](archive/image-learning.md#2026-11-05-验收结果) | 独立 PostgreSQL 16：189 项后端、17 项组件、17 项 Chrome 用例通过，含类型、契约、构建和 HTTP 崩溃恢复。失败、跳过和浏览器重试均为零。 |
| [RabbitMQ 专项](archive/backend-phase7.md#验证入口与证据边界) · [Redis 专项](archive/backend-phase8.md#验证入口与证据) | 本机真实 RabbitMQ 和 Redis，生成走 Mock；分别验证双 Worker、死信与恢复、共享准入与故障关闭。与上面的数据库模式回归分开统计。本机记录未覆盖 Docker / Testcontainers 启动分支。 |
| [历史真实文本](archive/backend-phase3.md#验证与边界) · [图片](archive/backend-phase4.md#验证) · [视频](archive/backend-phase5.md#验证与费用边界) | 图片验收只生成一张，参考图协议使用本机 fixture；视频只有一次真实生成请求，另有一次供应商调用前的本地拒绝，共两个本地 attempt。外部任务恢复的常规验收只用本机 HTTP fixture。 |

这些结果对应各自记录的版本和环境。Mock 验证流程、作用域和状态，不代表真实视觉质量、返工减少或供应商吞吐。
