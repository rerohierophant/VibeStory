# 第二阶段后端

实现与验证日期：2026-11-02。当前包含项目、画面风格、分集与故事的后端持久化；页面仍是本地演示模式，完整前端联调留在阶段六。阶段一 Mock 视频任务继续保留。

## 行为与接口

| 接口 | 行为 |
| --- | --- |
| `GET /api/projects?q=...` | 当前用户项目列表；项目名大小写不敏感、按字面包含搜索；按更新时间与 ID 倒序 |
| `POST /api/projects` | 名称、稳定 `visualType`、可选 `style`，201；支持内置风格及当前用户的自定义条目 |
| `GET /api/projects/{id}` | 项目字段、按序分集（含正文与统计）、解析后的 `visualPreset`、项目统计 |
| `PATCH /api/projects/{id}` | 修改 `name/visualType/style`；省略或 null 不修改，空 style 可清空 |
| `DELETE /api/projects/{id}` | 204；事务内删除现存关联行并登记媒体清理 |
| `GET /api/projects/{id}/overview` | 分集、镜头、已确认、有选定分镜图、有视频的镜头数 |
| `GET /api/visual-styles` | 页面内置目录及当前用户未移除的自定义条目 |
| `POST /api/visual-styles` | `multipart/form-data` 的 `label/image` 字段，上传 JPEG / PNG，201 |
| `GET /api/visual-styles/{id}` | 解析风格；私有条目校验归属 |
| `DELETE /api/visual-styles/{id}` | 204；移出选择器，保留已有项目引用 |
| `GET /api/visual-styles/{id}/image` | 私有图片，经 cookie 会话校验；不接受任意本地路径 |
| `GET/POST /api/projects/{id}/episodes` | 分集列表 / 新建；省略 title 时自动命名“第 N 集”，201 |
| `GET/PATCH /api/episodes/{id}` | 读取分集 / 编辑 title 与 summary；含镜头、确认、有图、有视频统计 |
| `PUT /api/episodes/{id}/story` | `{ "story": "..." }` 整体替换这一集正文 |
| `DELETE /api/episodes/{id}` | 为阶段验收提供删除能力；不重排其他集号，当前页面没有新增删除入口 |

写接口延续 CSRF 规则：先取 `/api/auth/csrf`，登录后重新取 token，再以相同 cookie 和 `X-CSRF-TOKEN` 提交。跨用户资源返回 404；错误使用 ProblemDetail。OpenAPI 与 TypeScript 类型在 `packages/contracts`，以实际生成结果为准。

## 关键规则

- 内置 22 个预设直接从已实现的 `apps/web/src/lib/visualPresets.ts` 生成后端目录，不把分类或展示名当作 `visualType`，也不固定成数据库枚举。更新目录后运行 `node scripts/sync-visual-presets.cjs`，`--check` 可核对是否一致。图片来源保留在原有素材署名文件中。
- 自定义参考图最大 5 MiB，只接收 JPEG / PNG；类型必须与真实内容一致，并能解码。最长边最多 4096 像素，总像素不超过 1600 万；重新编码到最长边不超过 480 像素。服务器生成文件 key，不使用上传文件名落盘。实际 Servlet 请求超过上限返回 413，服务内校验拒绝无效图；图片响应为 `private, no-store`。
- 删除自定义条目后，既有项目仍能恢复 ID、名称、参考图，并可继续改名；新项目不可选择已移除条目。最后一个项目切换风格或删除后，图片才进入清理。该行为承载页面移除自定义条目的交互，同时避免破坏既有项目。
- 分集索引在项目锁内按当前最大值 + 1 分配。连续创建产生 1、2、3；删除 2 后保留 1、3，下一集为 4。分集身份使用数据库 ID，与显示序号分开。
- 正文最大 1 MiB UTF-8，拒绝 NUL、无效 UTF-8 JSON 和未配对 Unicode 代理字符。空正文合法，空格、CRLF、换行与 Unicode 原样保存。后端接收前端读取后的 JSON 文本，不解析 `.txt/.md` 文件，也不能反推出前端转换前的原文件编码。
- 正文内容实际改变时仅将 `DONE` 回到 `IDLE`，不删除镜头、图、视频、任务历史；同一内容重复保存不重置 DONE。`RUNNING` 保留；拆解输入版本与运行中修改的协调属于阶段三。
- 统计按镜头去重，多张候选、多段视频与多次 attempt 不重复累计；已有视频在切图后仍计入已生成。集的元信息、正文与分集创建 / 删除会更新项目时间。

## 删除、事务与媒体

新增 Flyway `V3__project_episode_and_styles.sql`，没有修改阶段一 `V1/V2`。包含 `custom_style`、`storage_cleanup`，以及当前项目 → 集 → 镜头 / 图 / 视频 / 任务 / attempt 的删除级联。人物与场景资产表尚未实现，后续加入时须继续接入清理。

数据库行删除与清理记录同一事务提交。事务回滚时不会删除媒体；提交后资源鉴权立即返回 404，物理文件由默认每 30 秒运行的清理器最终删除，IO 失败下轮重试。记录保留，以处理旧 Worker 晚到的文件；这台原型不增加外部队列。204 不表示磁盘同步清空。

任务提交、重试和删除按项目 → 子对象加锁；Worker 写终态先锁项目，再检查 owner、version 与租约。项目或集已被删除时，旧 Worker 不能写入结果。自定义条目的并发切换按稳定 ID 排序加锁，避免两个项目互换风格时锁顺序相反。

## 运行与验证

本机沿用已经配置的忽略文件 `.env` 与 Homebrew PostgreSQL 16，直接运行：

```sh
scripts/dev-api.sh
```

在另一个终端导出环境后执行自动化和完整 HTTP 验收：

```sh
set -a; source .env; set +a
scripts/java21.sh test bootJar
python3 scripts/verify-phase1.py --phase2 --write-contracts
npm run generate --prefix packages/contracts
node scripts/sync-visual-presets.cjs --check
```

`.env` 的 `TEST_DATABASE_*` 指向本机 PostgreSQL 16；测试使用随机隔离 schema，清理后关闭上下文。未指定该变量时默认使用 Testcontainers PostgreSQL 16，需要 Docker，不回退到 H2。

验证结果：阶段一 19 项与阶段二新增 17 项，共 **36 项自动化测试通过**。覆盖 CRUD / 字面搜索、三集独立持久化、正文精确保留与校验、DONE 回退但保留产物、分集序号、用户隔离、私有 PNG / JPEG、伪造图与大小限制、自定义条目移除与引用保留、统计去重、并发互换风格、项目 / 集级联删除、旧 Worker 写入被拒、事务回滚与清理、迟到文件重扫、OpenAPI。

真实 HTTP 验收验证了：上传与实际 413、搜索及改名、三集正文与自定义参考图在两次 JVM 重启后恢复、跨用户正文 / 风格 / 项目操作 404、删除中间集后序号保持、项目与已移除风格的文件最终删除。阶段一并发提交、续约、SIGKILL / 租约接手也回归通过。HTTP 中只有镜头 / 逻辑首帧使用 SQL fixture，项目、分集、故事与风格均走真实接口。

HTTP 验收使用 4 秒租约、1 秒续约、500 毫秒清理周期；未等待默认 90 秒租约与 30 秒清理周期。默认配置与测试配置使用同一实现。

本次使用 Homebrew PostgreSQL 16；Docker / Compose / Testcontainers 路径仍未在本机执行。没有做前端联调、真实模型、拆解、人物 / 场景资产业务与对象存储，验证结论不包含这些范围。
