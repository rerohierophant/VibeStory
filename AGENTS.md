# VibeStory 开发约定

## 文档入口

- [README](README.md)：项目介绍、快速开始和本地 MCP。
- [本地开发与验收](docs/development.md)：运行模式、迁移、全 Mock 回归与证据范围。
- [创作流程](docs/workflow.md)与[系统架构](docs/architecture.md)：当前交互和实现说明。
- [阶段归档](docs/archive/README.md)：历史计划、实现和验收；不作为当前操作指南。

**页面是规格来源**：`apps/web` 的已实现交互与 `apps/web/src/lib/types.ts` 的字段形状优先于 `docs/` 中的任何旧表述；两者冲突时以页面为准，并同步修订文档。

新增功能优先更新对应的现行指南。阶段计划和一次性验收记录放入归档，注明日期和实际验证范围。

## 原型定位

本仓库交付的是**证明能力的原型**，不是可运营的产品。判断需求时先问：它是否服务于「任务状态机、幂等、作用域、版本与来源追溯」这四件事。不服务于这四件事的产品功能（多租户、计费、内容审核、多供应商矩阵、高可用、移动端、成片剪辑）一律不做，除非用户明确要求。边界与表述纪律见[系统架构](docs/architecture.md#原型边界)。

## 工作边界

- 在 `/Users/wangtianle/Desktop/VibeStory` 直接工作；不要创建、切换或推荐 Git worktree。
- 设计系统以 `design/theme.js` 与 `design/tokens.css` 为准。品牌源文件、字体和素材署名在 `design/assets/`。`apps/web` 实现新页面，工作台静态图片放在 `apps/web/public/`。
- 不声称已读取或同步 Figma；只有用户提供链接且实际读取后才能作此声明。

## 实现原则

- `apps/api` 承担业务规则，`apps/agent` 通过受控工具操作业务对象，`apps/web` 展示与编辑状态。
- 前端统一使用 React + TypeScript + Vite + Tailwind CSS。界面通过 `design/tokens.css` 使用共享令牌，JavaScript 场景复用 `design/theme.js` 导出，不重复定义颜色。
- 后端统一使用 Java 21 + Spring Boot 3 + Gradle，不使用 JDK 11 写法（`javax.*`、无 record、无虚拟线程）。
- 后端按 package-by-feature 组织：一个业务聚合一个包，不在包内再拆 `controller/service/repository/impl` 四层。简单 CRUD 用 Spring Data JPA，复杂查询用 jOOQ。
- 数据库迁移用 Flyway，不使用 `ddl-auto: update`；所有状态变更必须有显式事务边界。
- 长任务以数据库状态与租约为准。默认数据库派发；RabbitMQ 与 Redis 的可选部署按[本地开发说明](docs/development.md#本地运行模式)配置，不混跑两种派发模式。
- `packages/contracts` 保存任务、事件、API 与工具的共享契约。目录边界不强制跨进程部署；API 契约以 springdoc 生成的 OpenAPI 为准，前端类型由它生成。
- 用户、项目、任务、尝试与资产版本应有明确身份；任何读写均校验作用域。
- 可复用资产仅人物与场景；分镜图与视频是镜头产物，只记录生成来源。每张分镜图保留提交时的人物 / 场景版本，每段视频保留提交时的首帧；换基准版本或切图不改写历史、不自动使已有产物失效。缺失来源不按当前版本补填；本期不引入分镜图或视频的陈旧概念。
- 长任务状态以持久化记录为准；浏览器连接和进程内变量不能作为业务完成依据。
- 区分重复提交、主动重新生成、可重试失败和外部执行结果未知。未经供应商能力支持，不承诺外部调用 exactly-once。
- 模型与供应商由适配器隔离，只维护 DeepSeek 文本、MiniMax 图片／视频与 Mock。日常开发优先用 Mock，验收只用 Mock；全量入口为 `scripts/verify-all-mock.sh`，不调用真实生成或录屏。开发脚本显式使用 JDK 21。
- Skill 唯一可编辑来源为 `skills/<name>/SKILL.md` 及个人覆盖目录 `skills/users/<userId>/`。运行冻结正文与哈希，不在 Java 中注册 Skill。图片纠正默认只针对当前结果；偏好和配方必须经用户明确满意后才可保存。
- 密钥只存本机 `.env`，不得进入源码、快照、浏览器或 API 响应。不提交凭证、用户上传、数据库、生成媒体或运行日志。
- 字体与第三方素材保留原有许可与署名，不将其描述为用户自研。
- 将计划、已实现、已测试和真实供应商验证分开表述；简历指标必须有实际证据。
- 不为凑技术栈增加无业务用途的中间件。按变更风险执行必要验证。
