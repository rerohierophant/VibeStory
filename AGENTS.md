# VibeStory 开发约定

## 目标与现状

VibeStory 是 AIGC 视频创作工作台，用于展示后端、Agent 和全栈工程能力。前端为 React + TypeScript + Vite + Tailwind CSS，已接入后端；启动时 API 不可用则显式使用独立的离线演示数据。后端为 Java 21 + Spring Boot 3 + Gradle，数据库 PostgreSQL 16。阶段一至六已实现：项目 / 风格 / 分集 / 正文持久化、拆解与镜头编辑确认、资产版本与基准选择、分镜图来源快照、视频首帧与外部任务号恢复、任务指标及结构化日志。当前只接 DeepSeek 文本与 MiniMax 图片 / 视频，另保留 Mock；不扩展多模型适配。MiniMax 图片仅单张真实验收，视频仅一次真实生成，参考图输入仍以 fixture 验证。视频真实任务曾有一次本地配置拦截，共两条本地 attempt，只有一次供应商生成调用；不可称作一次成功 attempt。第六阶段 117 项后端、13 项组件、5 项浏览器测试与 HTTP 崩溃恢复通过；本机使用真实 PostgreSQL 16，Docker / Testcontainers 分支未运行。用户取消录屏，并要求后续验收全部 Mock，不再调用真实图片或视频生成；使用 scripts/verify-all-mock.sh。Agent 工具编排尚未实现，不把普通拆解任务称作 Agent。密钥只存本机 .env，不得写入源码、快照、浏览器或 API 响应。JDK 21 已安装，所有开发脚本显式使用 21。

阶段七已实现：整集图片 / 视频批次、事务 Outbox、RabbitMQ 4.4.0、独立 API / Worker、类型槽位、死信暂停 / 显式恢复、派发指标与批量页面。MQ 双 Worker / 崩溃 / 重启 / 页面链路使用真实 PostgreSQL 与本机 RabbitMQ、全部 Mock 验证；外部任务恢复只使用本机 HTTP fixture 查询 / 下载。默认 `DISPATCH_MODE=database` / `APP_ROLE=all`；MQ 用 `scripts/dev-mq.sh api|worker`，所有进程共享相同模式、数据库、namespace 和绝对媒体根目录，不混跑两种 Worker。Agent 尚未实现。数据库模式最新回归为 136 项后端、17 项组件、6 项 Chrome，真实 MQ 专项单列。详细证据与验收入口见 `docs/backend-phase7.md`。

阶段八已实现：可选 Redis 8.10.3 / Lua 共享生成提交令牌桶，MiniMax 图片 / 视频共用桶，文本独立；数据库延迟 Outbox、短许可在实际请求前校验、故障关闭与单一恢复代次从零补充。已有 external ID 恢复不占新生成额度。默认 `ADMISSION_ENABLED=false`，启用须用 rabbit 模式；`scripts/dev-redis.sh api|worker` 强制 Mock。所有进程须共享准入开关、namespace、凭证配置 ID 与 rate/burst。真实 Redis 专项 17 项，另有双 JVM / 实际 Redis 停机重启 / 桶丢失 / Chrome 验收；详见 `docs/backend-phase8.md`。Redis 不是任务事实源、任务锁或缓存；不承诺供应商端精确 QPS 或全局在途并发。

阶段十二已实现：主 / 子 Agent、Markdown Skill 文件库与受控业务工具三层执行。Skill 的唯一可编辑来源为根目录 skills/<name>/SKILL.md，动态扫描，不在 Java 中注册；个人覆盖在 skills/users/<userId>/，API 与 Agent 共用文件读写，运行冻结正文与哈希快照。主会话通过 function calling 按需加载 Skill、直接操作或派发剧本 / 分镜子 Agent；子会话独立读取冻结输入，通过工具提交草稿并按校验错误多轮修复。运行、模型请求与工具回执持久化，页面与对话共用业务执行与版本校验。上下文采用大工具结果持久化外置、旧工具正文裁剪、全量原始存档摘要三层渐进压缩，不做 History Snip；原始记录不被投影覆盖，按作用域分页回读，摘要检查点 / 调用 / usage 持久化，真实摘要请求丢失为 UNKNOWN。新增 V20 / V21 / V22，独立 PostgreSQL 全 Mock 验收为 181 项后端、17 项组件、16 项浏览器，含 HTTP 崩溃恢复；未调用真实供应商。压缩压力样本的字节统计不代表真实 token 或语义摘要质量。分集规划仍为服务端窗口任务，媒体仍由原 Worker 执行，尚无审图 / 审片 Agent。共享 Redis 文本准入启用时暂停新增 Agent 模型运行。旧开发库缺少原始 V18 迁移，沿用前需恢复原文件或改用独立库。当前实现和验收以 `docs/backend-phase12.md` 为准，上文早期阶段的“Agent 尚未实现”只描述历史状态。

阶段十三已实现：个人项目图片纠正与明确评价、素材 / 项目 / 个人范围的可编辑偏好、文字槽位与跨项目绑定的不可变配方版本。页面与 Agent 共用受控服务，普通、批次与方案出图冻结实际使用的偏好和配方；保存偏好与配方需用户明确满意，任务完成不等同满意。新增 V23，最新独立 PostgreSQL 全 Mock 验收为 189 项后端、17 项组件、17 项浏览器，类型、契约、构建与 HTTP 崩溃恢复通过，失败 / 跳过 / 浏览器重试均为零。未调用真实供应商，未验证视觉质量或返工减少。当前原型范围与验收见 `docs/image-learning.md`，此前各阶段数量保留为历史记录。

**页面是规格来源**：`apps/web` 的已实现交互与 `apps/web/src/lib/types.ts` 的字段形状优先于 `docs/` 中的任何旧表述；两者冲突时以页面为准，并同步修订文档。

## 原型定位

本仓库交付的是**证明能力的原型**，不是可运营的产品。判断需求时先问：它是否服务于「任务状态机、幂等、作用域、版本与来源追溯」这四件事。不服务于这四件事的产品功能（多租户、计费、内容审核、多供应商矩阵、高可用、移动端、成片剪辑）一律不做，除非用户明确要求。边界与表述纪律见 `docs/tech-design.md §16`。

## 工作边界

- 在 `/Users/wangtianle/Desktop/VibeStory` 直接工作；不要创建、切换或推荐 Git worktree。
- 设计系统以 `design/theme.js` 与 `design/tokens.css` 为准，素材在 `design/assets/`；`apps/web` 实现新页面。
- 不声称已读取或同步 Figma；只有用户提供链接且实际读取后才能作此声明。

## 实现原则

- `apps/api` 承担业务规则，`apps/agent` 通过受控工具操作业务对象，`apps/web` 展示与编辑状态。
- 前端统一使用 React + TypeScript + Vite + Tailwind CSS；主题令牌从 `design/theme.js` 引入，不重复定义颜色。
- 后端统一使用 Java 21 + Spring Boot 3 + Gradle，不使用 JDK 11 写法（`javax.*`、无 record、无虚拟线程）。
- 后端按 package-by-feature 组织：一个业务聚合一个包，不在包内再拆 `controller/service/repository/impl` 四层。简单 CRUD 用 Spring Data JPA，复杂查询用 jOOQ。
- 数据库迁移用 Flyway，不使用 `ddl-auto: update`；所有状态变更必须有显式事务边界。
- 长任务用数据库表 + 乐观锁 + 租约轮询驱动，第一阶段不引入 Redis、Kafka、RabbitMQ 等中间件。
- `packages/contracts` 保存任务、事件、API 与工具的共享契约。目录边界不强制跨进程部署；API 契约以 springdoc 生成的 OpenAPI 为准，前端类型由它生成。
- 先完成创建项目、提交 Mock 视频生成任务、Worker 执行、查询结果的最小纵向链路。
- 用户、项目、任务、尝试与资产版本应有明确身份；任何读写均校验作用域。
- 可复用资产仅人物与场景；分镜图与视频是镜头产物，只记录生成来源。每张分镜图保留提交时的人物 / 场景版本，每段视频保留提交时的首帧；换基准版本或切图不改写历史、不自动使已有产物失效。缺失来源不按当前版本补填；本期不引入分镜图或视频的陈旧概念。
- 长任务状态以持久化记录为准；浏览器连接和进程内变量不能作为业务完成依据。
- 区分重复提交、主动重新生成、可重试失败和外部执行结果未知。未经供应商能力支持，不承诺外部调用 exactly-once。
- 模型与供应商由适配器隔离；测试和日常开发优先使用 Mock。
- 不提交凭证、用户上传、数据库、生成媒体或运行日志。
- 字体与第三方素材保留原有许可与署名，不将其描述为用户自研。
- 将计划、已实现、已测试和真实供应商验证分开表述；简历指标必须有实际证据。
- 不为凑技术栈增加无业务用途的中间件。按变更风险执行必要验证。
