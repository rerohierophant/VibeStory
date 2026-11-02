# 第一阶段后端

本文保留第一阶段的实现与验收记录。当前已完成阶段二，新增接口、风格和正文规则见 [backend-phase2.md](backend-phase2.md)。

实现与验证日期：2026-11-02。范围是登录 → 建项目 → 建集 → SQL 占位镜头 / 首帧 → 提交 Mock 视频任务 → Worker → 查询 / 读取结果。前端仍使用 localStorage；没有接入真实供应商、Agent、分镜图生成或资产库。

## 已实现

- Java 21、Spring Boot 3.5.16、Gradle 8.14.3 Wrapper；按 auth / project / episode / task / provider / storage 组织包。简单用户、项目与分集 CRUD 用 JPA，领取与事务状态更新用 jOOQ / PostgreSQL。
- BCrypt 种子账号、会话登录 / 登出、`GET /api/me`、CSRF；账号只从环境变量初始化，没有公开注册接口。已有账号的密码不会在重启时被重置。
- 项目创建 / 查询 / 列表、分集创建 / 列表。项目仅接受页面目录中的 22 个内置风格 ID，自定义风格留在第二阶段；分集序号在项目行锁内分配。
- Flyway `V1/V2`、Hibernate `validate`。复合外键约束 user → project → episode → shot，ScopeGuard 校验作用域；跨用户返回 404。
- 活跃任务部分唯一索引、事务内 upsert；同一镜头、同一规范化输入且任务活跃时复用 task。新操作使用新 `regenerateNonce`；同一次请求重发沿用原 nonce。
- `FOR UPDATE SKIP LOCKED` 领取；默认租约 90 秒、30 秒续约，数据库时间判定过期。每次领取写独立 attempt，租约 owner + version + 未过期条件同时限制续约和终态写入。
- 崩溃后，旧尝试标记 `ABANDONED`，Mock 安全重放并新增 attempt；最多 3 次。已知失败可重试同一 task；`UNKNOWN` 必须明确确认，达到上限不能继续重试。当前只有 Mock 适配器支持自动恢复，不承诺真实供应商 exactly-once。
- 本地 Mock 固定 3 秒 MP4，可配置延迟和确定性失败率；`known/unknown` 两种故障注入。媒体在运行时生成，存入忽略目录，经鉴权接口读取并支持 Range。
- 视频的首帧身份、版本与输入路径在提交时固定；后续切图不覆盖来源，也不把已有产物标为陈旧。阶段一首帧是 SQL fixture 的逻辑引用，Mock 不解码首帧，不代表已实现真实图生视频。
- OpenAPI 从实际运行的 springdoc 导出到 `packages/contracts/openapi.json`，TypeScript 类型从它生成。项目返回基础字段，分集通过独立列表接口读取；前端完整状态聚合留待联调。

`V1` 提前建立的分集、镜头与首帧只是链路前置模型，不代表第二、三阶段业务全部完成。后续必须新增 Flyway 迁移，不能重写已执行的迁移文件。

## 运行

在仓库根目录执行。macOS 脚本自动选用已安装的 JDK 21，不要求改动全局 Java 默认版本。

**这台机器已经配置好被 Git 忽略的 `.env`**，数据库进程当前已启动，可直接运行 `scripts/dev-api.sh`。开发种子账号的邮箱与随机密码保存在该本地文件中，没有提交或回显。以下 Compose 步骤用于另一台有 Docker 的新环境；不要覆盖已有 `.env`。

```sh
cp -n .env.example .env
# 编辑 .env：填写 DATABASE_PASSWORD、SEED_EMAIL、SEED_PASSWORD
set -a; source .env; set +a
docker compose --env-file .env -f infra/docker-compose.yml up -d postgres
scripts/dev-api.sh
```

API 默认监听 `127.0.0.1:8080`；Swagger UI 为 `/swagger-ui.html`，机器契约为 `/v3/api-docs`。`scripts/dev-api.sh` 读取根目录 `.env`；直接运行 Gradle / JAR 时自行导出环境变量。

本机未安装 Docker，本次已安装并使用 Homebrew PostgreSQL 16.15，在 `127.0.0.1:55432` 手动启动，未配置登录自启动。开发库为 `vibestory_dev`，测试库为 `vibestory_test`。没有配置文件时，可按本机数据库用户设置 `DATABASE_USER`，将 URL 指向该开发库。若数据库进程已停止，使用：

```sh
/opt/homebrew/opt/postgresql@16/bin/pg_ctl \
  -D /opt/homebrew/var/postgresql@16 -l /tmp/vibestory-postgres16.log \
  -o '-h 127.0.0.1 -p 55432' start
```

`.env` 与媒体、日志、数据库内容不提交。种子密码至少 6 字符、至多 72 个 UTF-8 字节。`MOCK_DELAY` 默认 `3s`，`MOCK_FAILURE_RATE` 默认 `0`；失败率 `1` 表示必失败，`MOCK_FAILURE_MODE=unknown` 模拟未知结果。

## HTTP 调用

1. `GET /api/auth/csrf`，保存 `JSESSIONID` cookie，取得 `headerName/token`。
2. `POST /api/auth/login`，JSON 为 `{ "email": "...", "password": "..." }`；带相同 cookie 和 `X-CSRF-TOKEN`。
3. 登录后重新读取 CSRF token，登录会轮换会话与 token；后续所有写请求携带它。
4. `POST /api/projects`：`{ "name": "雨夜", "visualType": "realistic", "style": "电影光影" }`。
5. `POST /api/projects/{id}/episodes`：`{ "title": "第一集" }`。
6. 用 fixture 插入已确认镜头与已选首帧，没有对外的手工建镜头接口：

```sh
psql -v user_id=1 -v episode_id=1 -f scripts/fixtures/phase1-shot.sql
```

`psql` 使用 `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`；传入刚才 `/api/me` 与建集返回的真实 ID。fixture 中不存在或不属于用户的集会导致事务失败。

7. `POST /api/shots/{shotId}/video-tasks`：`{ "regenerateNonce": "一次点击生成并在重发时保持的值" }` → `202 { "taskId": "..." }`。
8. `GET /api/tasks/{id}`：`queued → running → idle + ready:true`，含 `basedOn/attempts/history/videoUrl`。内部任务保留 `SUCCEEDED`；页面状态不增加成功徽标。
9. 带登录 cookie 读取 `videoUrl`；已知失败可 `POST /api/tasks/{id}/retry {}`。未知结果须显式 `{ "acknowledgeUnknown": true }`，保留原快照并新增 attempt。
10. `POST /api/auth/logout` → 204，撤销当前会话。

没有首帧或镜头未确认返回 409；无登录态返回 401；CSRF 无效返回 403；跨用户资源返回 404。参数校验与业务错误使用 `application/problem+json`。

## 验证与证据边界

```sh
# 默认路径：Testcontainers PostgreSQL 16，需要 Docker
scripts/java21.sh test

# 本次执行路径：本机真实 PostgreSQL 16，随机 schema 隔离，测试后清理
TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/vibestory_test \
TEST_DATABASE_USER="$USER" scripts/java21.sh test bootJar

# 导出环境中的 DATABASE_* 应指向开发库；脚本启动 / 停止自己的 API 进程
python3 scripts/verify-phase1.py --write-contracts
npm ci --prefix packages/contracts
npm run generate --prefix packages/contracts
```

19 项自动化测试通过：输入规范化 5 项，Mock 故障语义 2 项，PostgreSQL 集成 12 项。覆盖登录 / 登出与 CSRF 轮换、越权、生成前置校验、10 次并发提交与领取、跳过已锁队首、MP4 实际 3 秒与 Range、旧 owner / 旧 version 写入被拒绝、首帧快照、重试与未知确认、尝试上限、分集并发序号、OpenAPI 字段与 CSRF 声明。

HTTP 脚本实际验证：10 次并发提交只有 1 条 task / 1 条 attempt；慢调用持续时间超过缩短后的 4 秒租约而由续约保持同一尝试；对真实 JVM 发 SIGKILL，重启新 JVM 后恢复为第 2 次尝试并成功；跨用户项目 / 分集 / 镜头 / 任务 / 媒体均 404；登出后会话失效。

本机没有 Docker，因此本次没有执行 Compose 与 Testcontainers 路径。集成与 HTTP 测试使用相同 PostgreSQL 16 数据库语义，没有用 H2；崩溃验收缩短租约为 4 秒、续约为 1 秒，未等待默认 90 秒。测试数据留在开发库中供检查，测试日志与媒体在 `data/`，均被 Git 忽略。真实供应商、前端联调和完整的分镜图来源链均未验证。
