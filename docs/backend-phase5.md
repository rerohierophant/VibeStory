# 第五阶段后端与前后端联调

实现与验证日期：2026-11-02。第五阶段完成，前端核心创作链路已接入后端。文本仅维护 DeepSeek，图片与视频仅维护 MiniMax，另保留 Mock 用于开发测试。没有扩展通用供应商管理。

## 视频接入

同一把本机 MiniMax 普通 API Key 已验证可生成图片与视频。视频使用原生 V1 图生视频协议，只接入 `MiniMax-Hailuo-2.3-Fast` 的 768P / 6 秒规格。首帧读取提交时选定的分镜图，用 Data URL 发送，不要求把私有文件公开到外网。

官方依据：[图生视频任务](https://platform.minimax.cn/docs/api-reference/video-generation-i2v)、[查询状态](https://platform.minimax.cn/docs/api-reference/video-generation-query)、[获取文件](https://platform.minimax.cn/docs/api-reference/file-management-retrieve)、[模型列表](https://platform.minimax.cn/docs/faq/models)。当前不接入 H3 或其他视频模型。

```dotenv
VIDEO_PROVIDER=minimax
VIDEO_BASE_URL=https://api.minimax.cn/v1
VIDEO_MODEL=MiniMax-Hailuo-2.3-Fast
VIDEO_TIMEOUT=60s
VIDEO_POLL_INTERVAL=5s
VIDEO_GENERATION_TIMEOUT=20m
# VIDEO_API_KEY 缺省复用 IMAGE_API_KEY；密钥仅在本机 .env
```

干净环境默认 Mock，本机 `.env` 已启用 MiniMax。图片 / 文本配置独立；前端不能覆盖模型或上传供应商凭证。`GET /api/runtime` 经登录校验，只返回实际 provider、model 和输出规格，不含密钥与接口地址。

## 状态、幂等与恢复

`POST /api/shots/{id}/video-tasks` 现在接受 `{}`：同输入重复提交复用活跃任务；明确重新生成提交新 `regenerateNonce`。未确认或无选定 READY 图返回 409。提交冻结首帧 ID / 版本 / 存储引用、提示词、模型与接口地址。MiniMax 首帧须满足短边大于 300 像素等输入限制，提示词超过 2000 字符在付费调用前拒绝。

真实流程：

1. 租约 Worker 提交一次 `/video_generation`。
2. 获得 `task_id` 后先写入 `task_attempt.external_task_id`，再轮询 `/query/video_generation`。
3. 成功后通过 `/files/retrieve` 取得临时地址；不带供应商 Authorization 下载文件，校验重定向、公网地址、大小与 MP4 视频轨道。
4. 本地私有存储写入后，在同一数据库事务内保存视频结果和任务 / attempt 终态。媒体的 `durationMs` 来自 MP4 实测，计划时长和请求规格仍保存在镜头与输入快照中。

没有 external ID 时，提交超时 / 进程中断进入 UNKNOWN，不自动重放付费 POST。已有 external ID 时，租约恢复继续查询同一个供应商任务，复用同一个 attempt；查询 / 下载中断后，用户可点“继续查询”，不产生新的生成请求。只有已知生成失败的手动重试才新建 attempt。UNKNOWN 且没有任务号时要求明确确认可能重复计费。

Worker 只领取自身已配置供应商的真实任务，同时允许本地 Mock，防止不同配置的进程抢走对方任务。凭证不会被发送到与冻结地址不同的接口。没有外部幂等保证，不承诺 exactly-once。

完成产物始终保留自身首帧。生成期间切图、完成后切图或修改镜头，都不改写已有视频或取消其可用状态。镜头返回当前任务 ID、可重试 / 可继续查询状态、当前提交的首帧和已完成视频的首帧，前端可同时显示旧片与新任务。

`V7__minimax_video.sql` 扩展既有任务约束、外部任务号与视频时长；`V8__measured_video_duration.sql` 允许记录实际 MP4 时长。未修改已执行的 V1～V6。重新拆解、删除集与项目均清理视频文件 / 目录，旧租约不能写回。

## 前端联调

React 页面已接到实际 API，使用生成的 OpenAPI TypeScript 契约和 TanStack Query，缓存按用户分开。登录发送真实密码，维护会话 cookie 与 CSRF；退出、会话失效和切换账号清理旧用户缓存。联机数据不写入浏览器 localStorage。

正文、集信息与镜头字段做 500ms 合并保存；拆解、确认和生成之前等待未完成保存。失败时保留草稿、显示可读错误并阻止依赖动作，不把保存失败当成功。活动任务按集三秒轮询，页面隐藏时停止；资产任务刷新资产卡，不逐镜轮询。

已连通项目创建 / 重命名 / 删除、分集与正文、拆解覆盖确认、镜头编辑与确认、资产上传 / 生成 / 选版、分镜图生成 / 选择、视频生成 / 重试 / 播放。图片与视频读取私有鉴权地址；预览同步最新任务数据，始终使用视频记录的首帧。自定义风格上传到后端，删除选择器条目不影响已有项目的风格名称。

设置页只读展示后端实际模型，移除了无效的通用供应商配置与浏览器 Token 输入。启动时 API 不可用会显式进入“离线演示”，沿用独立本地数据；联机期间网络失败显示错误，不切换到本地写入。

## 验证与费用边界

本轮 **111 项后端测试通过**：原有 97 项回归，新增 6 项 MiniMax 本机 HTTP 协议测试和 8 项 PostgreSQL 视频集成测试。覆盖外部 ID 先持久化后查询、已知任务恢复且不重复提交、无 ID 进入 UNKNOWN、已知失败重试、首帧冻结、scope、供应商领取隔离、私有下载不带密钥与 MP4 校验。协议测试没有调用真实供应商。

Chrome → Vite → Spring → PostgreSQL 的 **5 项浏览器验收通过**，覆盖完整创作流程、失败草稿保留与恢复、退出 / 跨账号隔离、显式离线演示及自定义风格上传 / 移除。所有浏览器生成调用强制 Mock，真实 HTTP 回归也强制 Mock 视频。最终截图、trace 和服务日志在忽略的 `data/verification/web-0f901c5ed7/`；阶段一至四 HTTP 回归记录在 `data/verification/cdd8c865c614/`。

真实验证只使用 **1 次 MiniMax 视频生成请求**，复用第四阶段已生成的图片，没有再付费生图。输出 MP4 为 **1364×768、实测 5.875 秒、297,955 字节**；供应商请求规格为 6 秒。API 在拿到持久化 external ID 后被 SIGKILL，重启后只查询原任务，最终经私有媒体接口下载，并由 Chrome 读取元数据、验证可播放。出片后补充的 MP4 校验 / 时长读取使用同一份已下载文件离线复验，并将该样本的实测时长写回；没有再次调用供应商。完整样片与摘要位于：

- `data/verification/video-4f0320af05/phase5-real-video.mp4`
- `data/verification/video-4f0320af05/phase5-summary.json`

验收过程中曾因两种配置的进程共用数据库，发生一次本地 `VIDEO_CONFIGURATION_CHANGED` 拦截；它位于供应商调用前。修复领取过滤后继续原业务任务，因此历史为 **2 个本地 attempt，其中 1 个为本地拒绝，只有 1 次真实生成请求**。保留这段历史，没有删改成一次成功尝试。脚本的一次性标记与生成边界日志限制付费请求；再次运行默认拒绝，不应删除标记再测。

本机 PostgreSQL 16 验收通过；Docker / Testcontainers 路径尚未在本机运行。未评估视频质量或声称人物视觉一致性。阶段六剩余的观测指标、正式录屏等收尾仍可继续推进，本轮完成的是核心 API 联调与可复现浏览器验收。

## 本机运行

```sh
# 读取本机 .env，使用 JDK 21；其中已配置 DeepSeek + MiniMax
scripts/dev-api.sh
npm run dev --prefix apps/web

# 免费开发：强制三个供应商均为 Mock
scripts/dev-api.sh --args='--vibestory.text.provider=mock --vibestory.image.provider=mock --vibestory.video.provider=mock'

# 免费回归与契约更新
set -a; source .env; set +a
scripts/java21.sh test bootJar
python3 scripts/export-openapi.py
npm run generate --prefix packages/contracts
python3 scripts/verify-web.py
```

登录使用本机 `.env` 中的种子账号；不在源码或文档复制密码。前端开发服务器通过 `/api` 代理到 `127.0.0.1:8080`，可用 `API_PROXY_TARGET` 指定别的本机 API。API 测试脚本创建合成账号与数据，不删除现有项目。
