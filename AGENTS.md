# VibeStory 开发约定

## 目标与现状

VibeStory 是全新 AIGC 视频创作工作台，用于展示后端、Agent 和全栈工程能力。前端定为 React + TypeScript + Vite + Tailwind CSS，**页面已实现并跑在本地状态上**；业务后端定为 Java 21 + Spring Boot 3 + Gradle，数据库定为 PostgreSQL 16，后端尚未实现，本机暂未安装 JDK 21。

**页面是规格来源**：`apps/web` 的已实现交互与 `apps/web/src/lib/types.ts` 的字段形状优先于 `docs/` 中的任何旧表述；两者冲突时以页面为准，并同步修订文档。

## 原型定位

本仓库交付的是**证明能力的原型**，不是可运营的产品。判断需求时先问：它是否服务于「任务状态机、幂等、作用域、版本与陈旧判定」这四件事。不服务于这四件事的产品功能（多租户、计费、内容审核、多供应商矩阵、高可用、移动端、成片剪辑）一律不做，除非用户明确要求。边界与表述纪律见 `docs/tech-design.md §16`。

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
- 长任务状态以持久化记录为准；浏览器连接和进程内变量不能作为业务完成依据。
- 区分重复提交、主动重新生成、可重试失败和外部执行结果未知。未经供应商能力支持，不承诺外部调用 exactly-once。
- 模型与供应商由适配器隔离；测试和日常开发优先使用 Mock。
- 不提交凭证、用户上传、数据库、生成媒体或运行日志。
- 字体与第三方素材保留原有许可与署名，不将其描述为用户自研。
- 将计划、已实现、已测试和真实供应商验证分开表述；简历指标必须有实际证据。
- 不为凑技术栈增加无业务用途的中间件。按变更风险执行必要验证。
