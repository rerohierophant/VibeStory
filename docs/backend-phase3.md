# 第三阶段后端

实现与验证日期：2026-11-02。范围为异步拆解、可编辑镜头清单、确认 / 取消确认、项目内资产名称解析，以及用户指定的 OpenAI-compatible 文本模型。前端仍使用本地状态；没有新增真实图片 / 视频供应商或资产版本业务。

## 接口

| 接口 | 行为 |
| --- | --- |
| `POST /api/episodes/{id}/breakdowns` | 首次可提交 `{}`；已有镜头须 `{ "replaceExisting": true }`；202 + taskId |
| `GET /api/episodes/{id}/breakdowns/latest` | 最近一次拆解任务，无历史时 204 |
| `GET /api/tasks/{id}` | 通用任务投影：type、targetId、state、ready、attempts、错误与诊断链接 |
| `GET /api/tasks/{id}/attempts/{n}/diagnostics` | 本次尝试的脱敏原始响应及返回 model，校验用户归属 |
| `GET /api/episodes/{id}/shots?filter=all\|todo\|draft&taskActive=true` | 镜头及已有产物、当前视频任务状态；todo 按页面规则为候选图数组为空 |
| `GET/PATCH /api/shots/{id}` | 读取 / 编辑描述、台词、场景、景别、运镜与时长 |
| `PUT /api/shots/{id}/status` | `{ "status": "draft" }` 或 `confirmed`，可双向切换 |
| `POST /api/episodes/{id}/shots/confirm-all` | 逐条走同一状态方法，事务内确认整集 |
| `GET /api/projects/{id}/characters` | 项目内人物名称卡，阶段三版本数组为空 |
| `GET /api/projects/{id}/scenes` | 项目内场景名称卡，阶段三版本数组为空 |

写操作沿用登录 cookie 与 CSRF 规则。所有路径校验用户 / 项目 / 集 / 镜头归属，越权返回 404。未确认镜头提交视频返回 409；已确认镜头仍能编辑，不增加冻结或审片状态。时长沿用编辑器范围 1500～12000 毫秒。

可选响应字段没有值时省略，和页面可选字段及生成类型一致；拆解任务不返回视频的 `basedOn/videoUrl`，历史图片没有来源时不补填 `inputSnapshot`。

## 拆解与替换

`V4__breakdown_shots_and_asset_names.sql` 在已有表上增加拆解任务、`story_revision`、`shot_revision`、当前拆解指针、输出快照与尝试诊断，同时建立人物 / 场景名称卡与有作用域外键的引用。没有修改 V1～V3。

提交时固定原样正文、场景目录、请求模型、接口地址、prompt 版本、生成参数及两种修订号，不保存密钥。正文中的空白与段落不再按普通 prompt 折叠；快照就是实际执行输入。同一集仅有一条活跃拆解，同一输入重复提交复用任务。新的正文提交可取消旧尝试，其晚到结果无法写回。

首次拆解直接提交。已有镜头时，没有 `replaceExisting=true` 返回 409，不改变任何镜头、产物或任务。已确认覆盖也先保留旧结果；只有完整 JSON 校验通过、任务仍持有租约、正文 / 镜头修订号未变化，才在同一事务中删除当前集旧镜头及产物、写入全新 DRAFT 镜头、解析名称并更新任务终态。其他集、正文与项目名称卡保留。

等待期间改正文或镜头，结果以 `BREAKDOWN_SOURCE_CHANGED` 结束并保留新修改，需要重新确认提交；单独改集名 / 梗概不影响正文修订号。旧视频任务在替换时取消，任务与 attempt 历史保留；其晚到文件由持久化清理记录处理，不会清空整个集目录而误删新产物。图片与视频仍没有陈旧状态，不按当前资产补填历史来源。

正常结束同步更新集状态。异常租约回收时，API 根据持久化 task 状态修正缓存的 RUNNING 投影；真实文本没有幂等与查询能力时进入 UNKNOWN，必须显式确认重试，同一输入再次普通提交也不能绕过该确认。Mock 可按租约安全重放。

## 文本模型

干净环境默认 `TEXT_PROVIDER=mock`。本机 `.env` 已按用户要求配置以下非秘密项，并私下保存 `TEXT_API_KEY`：

```dotenv
TEXT_PROVIDER=openai-compatible
TEXT_BASE_URL=https://api.deepseek.com
TEXT_MODEL=deepseek-flash
TEXT_TIMEOUT=120s
TEXT_MAX_TOKENS=8192
TEXT_DISABLE_THINKING=true
```

密钥不在示例、源码、任务快照或响应中。`scripts/dev-api.sh` 读取本地 `.env`，无需改变启动方式。请求使用 `/chat/completions`、`response_format: {type: json_object}`，并在 system prompt 中给出完整 JSON 示例；当前 DeepSeek 配置关闭 thinking。协议依据：[官方首次调用说明](https://api-docs.deepseek.com/)、[JSON Output](https://api-docs.deepseek.com/guides/json_mode/)、[Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。

适配器验证 JSON 结构、必填字段类型、未知字段、重复 key、输出是否完整、枚举、Unicode、时长与 1～300 个镜头范围；不把 Markdown、空响应或截断内容视为成功。真实接口的 401 等拒绝、429、5xx 与超时分别记录；未知执行结果不自动重试。不会跟随携带凭证的重定向，也不会把当前凭证发送到与任务冻结地址不同的接口。

尝试记录保存经过密钥脱敏的原始响应（最多约 1M 字符，超过则截断），通过带作用域校验的诊断接口读取，普通任务轮询只返回链接。该记录用于定位 JSON / 供应商问题，不表示已验证供应商的底层权重版本；分别保留请求 model 与响应 model。

Mock 直接移植前端 `splitter.ts`：按换行分段、句末切分、引出语合并、48 字符聚合、场景词表、景别 / 运镜 / 时长规则。`scripts/sync-splitter-fixtures.cjs` 执行实际前端代码生成 12 个对照样例，Java 测试逐字段核对。Mock 不虚构人物名称；真实输出中重复人物 / 场景按项目、去首尾空白、Unicode NFC 与 `lower(name)` 唯一键解析，别名或语义近似名称不会自动合并。

## 验证与边界

```sh
set -a; source .env; set +a
scripts/java21.sh test bootJar
node scripts/sync-splitter-fixtures.cjs --check
python3 scripts/verify-phase1.py --phase2 --phase3 --write-contracts
npm run generate --prefix packages/contracts

# 明确执行一次真实文本请求；使用合成故事，不提交个人正文
python3 scripts/verify-phase1.py --phase3 --real-text
```

自动化默认强制 Mock，使用 PostgreSQL 16 隔离 schema；兼容适配器使用本机 HTTP fixture，不会偷偷调用真实供应商。真实验证开关仅对合成正文发起一次文本请求，其他视频仍为 Mock。

当前 **76 项自动化测试全部通过**：原有回归与输入签名 37 项、前端 splitter 对照 12 项、兼容协议 HTTP fixture 8 项、阶段三 PostgreSQL 集成 19 项。覆盖并发幂等、覆盖确认、旧来源拒绝、事务回滚、名称复用、确认状态、旧视频取消与晚到文件清理、私有诊断、Mock 租约恢复、真实任务过期进入 UNKNOWN；协议测试包括 JSON 类型 / 字段错误、空与截断输出、401 / 429 / 5xx、超时、重定向拒绝及密钥脱敏。

已完成的真实验证：官方 `/models` 返回 200 并列出 `deepseek-flash`。随后经本地业务 API → 持久化任务 → Worker → DeepSeek → 字段校验 → PostgreSQL 写回，**850 字符剧本产出 36 镜，1 次 attempt，请求与响应 model 均为 `deepseek-flash`**；人物卡 3 张、场景名称卡 8 张。编辑、整集确认与单镜取消确认在两次 JVM 重启后保留，跨用户的镜头 / 名称卡 / 拆解 / 诊断均返回 404。

该真实样本的本地摘要在 `data/verification/fe699338e68a/phase3-summary.json`，与媒体、运行日志一起被 Git 忽略。Mock HTTP 样本为 20 镜，完成了覆盖替换；真实样本为避免重复调用费用，只验证覆盖保护、编辑与持久化，没有再次请求真实模型重新拆解。镜头叙事质量未进行独立评估，数量不表示准确率或一致性提升。

HTTP 验收继续使用缩短的 4 秒租约、1 秒续约、500 毫秒清理周期；默认配置为 90 秒租约、30 秒续约、30 秒清理。验证包含阶段一 SIGKILL 后 Mock 接手；真实文本进程中断的 UNKNOWN 分支由 PostgreSQL 集成测试验证，没有故意中断真实付费请求。

本机使用 Homebrew PostgreSQL 16；Docker / Compose / Testcontainers 路径仍未运行。前端联调、真实分镜图 / 视频、资产版本与 Agent 工具调用仍不在本阶段范围。
