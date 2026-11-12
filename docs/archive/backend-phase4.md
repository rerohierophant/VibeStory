# 第四阶段后端

> 阶段归档。当前操作见[本地开发与验收](../development.md)；历史真实供应商命令不用于当前验收。

实现与验收日期：2026-11-02。资产版本、项目 / 全局资产库、分镜图异步任务、候选选择与生成来源已经实现。页面仍使用本地状态；阶段六才替换数据层。来源组件已支持展示适配器限制说明，尚未通过浏览器联调后端。

## 接口

| 接口 | 行为 |
| --- | --- |
| `GET /api/projects/{id}/characters` / `scenes` | 名称卡、版本、选中版本、按名称解析后的镜头 / 集使用统计、最新生成状态 |
| `GET /api/assets/characters` / `scenes` | 当前用户跨项目资产，附项目名；不会合并不同项目的同名卡 |
| `POST /api/assets/{kind}/{id}/versions` | multipart `file`（JPEG / PNG，最大 5 MiB）与可选 `label`；201 |
| `POST /api/assets/{kind}/{id}/image-tasks` | `{}` 或 `{ "regenerateNonce": "唯一标识" }`；202 + taskId |
| `PUT /api/assets/{kind}/{id}/selected-version` | `{ "versionId": 123 }`；仅能选择该资产的版本，204 |
| `GET /api/asset-versions/{id}/image` | 私有资产图片，需会话鉴权 |
| `POST /api/shots/{id}/image-tasks` | 已确认镜头的生成任务；普通重复提交复用活跃任务，nonce 产生新候选，202 |
| `PUT /api/shots/{id}/selected-image` | `{ "imageId": 123 }`；仅该镜头的 READY 图，204 |
| `GET /api/shot-images/{id}/image` | 私有候选图片，需会话鉴权 |
| `GET /api/episodes/{id}/shots?taskActive=true` | 全集镜头与图片任务状态的轮询收敛点；保留 `all/todo/draft` 筛选 |
| `GET /api/tasks/{id}` / `POST /api/tasks/{id}/retry` | 沿用任务与 attempt 投影，图片任务增加 `imageGuidance` |

`kind` 为 `characters` 或 `scenes`。写入仍须同一会话的 CSRF token。资源不属于当前用户时返回 404；未确认镜头提交图片返回 409。选图不改变镜头确认状态，不改写任何已有视频来源。

资产新版本自动成为当前基准，与现有页面一致；用户可再选回任意历史版本。分镜图第一张 READY 自动选定，后续候选保留当前选择。一次图片任务只请求一张；“再生成一版”显式提交新 nonce。

## 版本与来源

新增 `V5__asset_versions_and_image_tasks.sql`，未修改 V1～V4；随后以 V6 收敛图片供应商约束，没有改写已执行的 V5。`asset_version` 以复合外键关联已有的人物 / 场景卡与项目，用两个部分唯一索引保证同一资产最多一个选中版本。图片任务扩展既有 `task/task_attempt`，候选图扩展既有 `shot_image`。

资产生成是项目级任务，`episode_id` 为空，由独立项目外键保证作用域；删除一集不会删除资产版本或它的生成任务。分镜图生成任务仍属于单集与镜头。

提交时在项目锁内冻结：提示词、风格文字、模型、接口地址、人物与场景的资产 ID / 名称 / 版本 ID / 版本展示名、参考图存储引用、实际使用参考图的能力说明，以及 nonce。签名对应实际执行快照，密钥不进入快照。Worker 不读取完成时的当前基准版本。

候选图返回页面已有的 `inputSnapshot: { characters, scene }`，缺少版本时只保留名称，不补填后来新增的版本。`guidance` 与 task 的 `imageGuidance` 另行区分“记录的来源”与“实际送到模型的图片”：`mode`、`usedVersionIds`、`notice`。历史图片没有来源时继续省略 `inputSnapshot`，不设 stale 字段。

项目锁与数据库索引串行化选择和完成写入；文件使用任务 / attempt 独立目录与原子发布。数据库事务回滚通过同步回调清理本次文件；突然崩溃或文件系统清理失败仍可能留下无业务引用的文件，项目删除时清理整个目录，不宣称数据库与文件系统原子提交。重新拆解在同一事务内取消旧镜头的活跃图片 / 视频任务，保留任务历史；持久化清理记录删除旧媒体。删除集补充清理该集图片任务目录，删除项目清理整个项目目录。失去租约的结果不会发布文件或写回业务表。

## 图片适配器与费用边界

统一入口为 `ImageProvider`，业务任务由 `ImageProviders` 调度。干净环境默认 Mock；本机忽略的 `.env` 配置 MiniMax。文本适配器继续独立使用已有 OpenAI-compatible 配置。

```dotenv
IMAGE_PROVIDER=minimax
IMAGE_BASE_URL=https://api.minimax.cn/v1
IMAGE_MODEL=image-01
IMAGE_TIMEOUT=120s
# IMAGE_API_KEY 仅保存在本机 .env，不复制到文档或契约
```

MiniMax 官方图像协议是 `/image_generation`，请求 `n=1`、`response_format=base64`、`prompt_optimizer=false`；成功解析 `data.image_base64` 和 `base_resp.status_code`，不能将 HTTP 200 直接当成功。当前适配器不依赖临时结果 URL，而将返回图片存入本地私有存储。官方资料：[图片生成指南](https://platform.minimax.cn/docs/guides/image-generation)、[文生图](https://platform.minimax.cn/docs/api-reference/image-generation-t2i)、[图生图](https://platform.minimax.cn/docs/api-reference/image-generation-i2i)。

MiniMax 仅支持一个人物参考图，使用已冻结版本的 Data URL。多人时按稳定顺序发送第一个有基准图的人物，场景与其他人物保留来源并仅作文本描述，返回 `partial` 提示；仅有场景图时为 `text-only`。无参考图时正常文生图。提示词含风格与引用信息超过 1500 字符会在提交前拒绝，不自动截断、不付费重试。该来源机制证明输入可追溯，不证明视觉一致性。

当前仅维护 DeepSeek 文本与 MiniMax 图片，以及日常开发使用的 Mock。已移除 OpenAI-compatible 文生图分支，`IMAGE_PROVIDER` 和数据库图片任务约束仅允许 `mock/minimax`；文本继续使用现有 OpenAI-compatible 协议调用 DeepSeek。

Mock 按 input_signature 生成确定性 PNG，不联网。真实图片超时、5xx、连接中断、格式损坏或租约丢失进入 UNKNOWN，普通重复提交不能绕过原任务的确认；显式 `acknowledgeUnknown=true` 才能重试。401 / 余额不足为已知拒绝，429 为可手动重试失败。没有自动付费重试，不承诺外部 exactly-once。

凭证只在请求 Authorization header 使用；接口地址改变后旧任务拒绝发送当前凭证，不跟随重定向。响应有大小 / 完整读取超时限制，图片验证 JPEG / PNG 与像素范围。尝试诊断仅保留 HTTP / 供应商状态码和响应 model，不保留原始 base64、参考图、临时 URL 或回显正文；普通轮询也不返回密钥或私有存储 key。

## 验证

```sh
set -a; source .env; set +a
scripts/java21.sh test bootJar
python3 scripts/verify-phase1.py --phase2 --phase3 --phase4 --write-contracts
npm run generate --prefix packages/contracts
npm run build --prefix apps/web

# 显式开启一次付费图片调用；图片失败后不会自动重试
python3 scripts/verify-phase1.py --phase4 --real-image
```

自动化和默认 HTTP 验收强制使用 Mock，`.env` 的真实配置不会触发付费请求。真实图片开关仅提交一张分镜图，资产只上传参考图，不运行真实资产生成、重生成、重复提交或重试探测；文本仍为 Mock。

前三阶段验收在本轮重新执行：76 项自动化、真实 HTTP 并发幂等 / 续约 / SIGKILL 重启恢复 / 作用域 / 项目分集正文与镜头编辑持久化通过，未重调真实 DeepSeek。新增 14 项 PostgreSQL 集成与 7 项本机 HTTP 图片协议测试，共 **97 项自动化通过**。新增覆盖资产版本与唯一选中、私有媒体、快照冻结、历史缺失、单镜重生成、首帧不变、图片状态投影、UNKNOWN 确认、租约恢复、重拆解取消、项目 / 分集删除与清理、文件发布后数据库失败回滚，以及 MiniMax 文生图 / 参考图请求形状、错误码、无效图片、超时、重定向与配置变化。

Mock HTTP 验收在两次 JVM 重启后保留图片、来源快照和基准选择，跨用户图片 / 版本 / 任务操作均为 404；样本摘要位于忽略的 `data/verification/f9241e51a150/phase4-summary.json`。

真实 MiniMax 验收通过业务 API → 数据库任务 → Worker → `image-01` → 本地私有存储完成：**1 个图片任务、1 次 attempt、1 张 1280×720 JPEG（201,121 字节）**，未重试、未发起真实资产生成或第二张候选。该任务记录提交时场景版本；场景参考图未传给仅支持人物参考的接口，`guidance.mode=text-only`，不宣称已验证真实参考图或视觉一致性。图片、快照与基准选择在两次 JVM 重启后保持，跨用户访问返回 404。摘要与样图在忽略的 `data/verification/097d5392e5e4/phase4-summary.json` 和 `phase4-real-image.jpg`。

本机使用 Homebrew PostgreSQL 16，Docker / Compose / Testcontainers 路径仍未运行。真实视频、对象存储与前端 API 数据层联调尚未完成。

按当前范围移除通用文生图兼容分支后，97 项测试再次通过；V6 在本机已有 V5 的数据库上升级成功，Mock HTTP 图片链路、重启恢复与越权验收通过，日志位于忽略的 `data/verification/242ff10a1e3a/`。本次范围收敛没有新增真实模型调用。
