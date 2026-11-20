# FreeMedi-Agent 技术栈与选型依据

> 核对日期：2026-11-17。版本来自仓库构建文件、配置和源码；本轮未联网核对新版本、安装依赖或重新构建。
> 本文说明现有技术与历史选型理由，不代表最终功能范围已重新确定。依赖存在、配置存在、代码实现、环境验证是不同状态。

## 1. 当前构建基线

后端依据 [pom.xml](../../pom.xml)，前端依据 [package.json](../../web/package.json) 和 [pnpm-lock.yaml](../../web/pnpm-lock.yaml)。由 Spring Boot 管理的传递版本应以实际依赖解析为准，本文不把历史估计当锁定版本。

### 后端

| 技术 | 仓库声明 | 实际用途与边界 |
|---|---|---|
| Java | 17 | 编译目标；未使用 Java 21 虚拟线程特性 |
| Spring Boot / MVC | 3.4.5 | HTTP、参数校验、同步业务服务和 `SseEmitter` |
| LangChain4j core / OpenAI 模块 | 0.35.0 | DeepSeek 决策与流式回复适配，不替代状态机、事务或权限检查 |
| MyBatis-Plus Starter | 3.5.9 | 依赖已引入；任务存储实际使用 MyBatis 注解 Mapper，预约/知识访问大量使用 `JdbcTemplate` |
| Spring JDBC / Jackson / Validation | Spring Boot 管理 | SQL 与事务、JSON、请求校验；工具参数还有执行器手工校验 |
| Flyway / Flyway MySQL | Spring Boot 管理 | V1—V8 迁移；基础配置默认关闭，`mysql` profile 开启 |
| MySQL Connector/J | Spring Boot 管理 | MySQL JDBC 连接；数据库服务版本另见第 2 节 |
| springdoc-openapi | 2.8.6 | WebMVC OpenAPI/UI 依赖已引入；文档路由本轮未运行验证 |
| 本地 BGE 中文 ONNX Embedding | LangChain4j 0.35.0 对应模块 | 默认本地向量化，模型 `bge-small-zh-v1.5-q`、512 维 |

Maven 3.9.4 是旧开发环境记录，不是仓库 Wrapper 固定的版本；当前未提供 `mvnw` / `mvnw.cmd`，本轮也没有确认本机 Maven 版本。

### 前端

| 技术 | 声明版本 | 实际用途 |
|---|---:|---|
| Vue | 3.5.13 | 患者端组件 |
| TypeScript | 5.7.3 | 任务、方案、事件和门户数据类型 |
| Vite | 6.2.0 | 开发服务与构建；开发配置代理 `/api`到后端 |
| Pinia | 3.0.1 | 当前 Agent 任务、事件、流式临时文本 |
| Vue Router | 4.5.0 | 四条患者业务路由和登录入口，受保护页面在导航时核对患者会话 |
| Element Plus | 2.9.5 | 消息提示及基础组件依赖 |
| Axios | 1.8.4 | REST 调用；POST SSE 使用 `fetch`，GET 历史回放使用 `EventSource` |
| lucide-vue-next | 0.468.0 | 图标 |

Node.js 24.x、pnpm 11.x是旧环境/选型记录，当前没有 `.nvmrc`，`package.json`也没有固定 Node/pnpm 的 `engines` / `packageManager`字段，不能宣称工具链已完整锁定。已有 lockfile 仍应保留。

## 2. 数据、安全与部署：区分实现和原规划

| 技术或能力 | 当前状态 |
|---|---|
| MySQL | 业务、任务与知识正文的事实来源。8.4 LTS 是原目标版本，历史集成验收使用 8.0.34；本轮未验证任何实例 |
| Qdrant | 已实现 REST 适配器，[Compose](../../docker-compose.yml)固定 `qdrant/qdrant:v1.13.6`，提供 6333/6334 端口和持久卷；本轮未确认容器运行状态 |
| Docker Compose | 当前只包含 Qdrant，未提供 MySQL、Redis、前后端统一启动 |
| Spring Security | 在 `mysql` profile 启用 BCrypt 会话登录与 CSRF 校验。任务与预约由服务端身份检查归属 |
| JJWT 0.12.6 | 依赖存在，未实现 JWT 登录、签发、验证与刷新流程 |
| BCrypt 密码登录 | 原方案意图，当前未接入完整密码认证；不能声称演示账号均按生产方式存储密码 |
| Redis 7.4 | 原目标版本；Starter 与连接配置存在，未接入任务租约、限流或缓存代码；当前业务正确性不依赖 Redis |
| Lettuce | Spring Data Redis 默认客户端路线；未据此完成任何 Redis 业务功能 |

默认配置和组件装配见 [application.yml](../../src/main/resources/application.yml)、[AgentRuntimeConfiguration](../../src/main/java/com/mediflow/agent/config/AgentRuntimeConfiguration.java)、[KnowledgeConfiguration](../../src/main/java/com/mediflow/agent/config/KnowledgeConfiguration.java)。不要从旧机器曾安装服务推断新环境已经就绪。

## 3. 模型与执行配置

| 配置 | 当前行为 |
|---|---|
| 对话模型 | DeepSeek 的 OpenAI 兼容接口，默认 `deepseek-chat`，可用环境变量或外置配置覆盖 |
| 缺少对话 Key | 使用确定性 `DemoDecisionModel`，用于复现本地业务场景 |
| 工具 | 向模型声明 `findAvailableSlots`、`getAppointmentRules` 和 `searchHospitalKnowledge` |
| Prompt | 当前是适配器中的代码字符串，没有在线 Prompt 管理，也不能据此声称完整版本评估系统已实现 |
| 最大步骤 | `max-steps`默认 8，限制每轮消息处理；任务累计计数不是整任务执行上限 |
| 限时与重试 | Runner 每次模型/只读工具等待默认 15 秒/5 秒，一轮共享 45 秒，两者的短暂异常最多自动再试一次 |
| 响应传输 | POST SSE 发送执行进度、已保存文本和本轮结果。GET SSE 回放已存事件 |
| Key | 通过环境或 Git 忽略的本地外置配置提供；没有实现通用自动加载 `.env`的机制，不在仓库或公开文档记录敏感值 |

流式回复和最终结果来自同一次已保存的执行。任务追踪记录各次尝试及其结果，详见[Runtime 架构](agent-runtime-v1.md)。

## 4. RAG 的当前技术路线

RAG 已推进到索引与检索底层，不再适用“尚未引入、推迟到 P2”的旧描述。Agent 调用、引用和体验验收仍未接完，见 [M3 实施说明](../product/m3-rag-plan.md)。

| 层 | 当前实现 |
|---|---|
| 资料事实来源 | MySQL 保存版本、发布状态、片段原文与索引状态 |
| 默认 Embedding | 本地 ONNX `bge-small-zh-v1.5-q`，512 维；模型随依赖打包，运行时不需要调用外部模型服务 |
| 可选 Embedding | OpenAI 兼容 API 适配器；默认配置指向 SiliconFlow `BAAI/bge-m3`、1024 维，需要独立 Embedding Key；本轮没有验证该服务或模型可用性 |
| 索引 | 默认 `qdrant`；`memory`供封闭测试/本地核对，`none`用于明确报告不可用 |
| 检索 | 向量召回候选 id，回读 MySQL 检查当前版本与发布状态，不能把故障当无命中 |
| 重排/混合检索 | 尚未实现；BGE reranker 是旧候选，不是当前依赖或已选定必须交付项 |

本地 Embedding 不需要模型 API Key，但持久化索引/检索仍需要 MySQL 和 Qdrant。实时号源、患者权限与预约规则不从向量索引取得。

`QdrantKnowledgeIndex`直接使用 Java HTTP 客户端与 REST API，没有引入 `langchain4j-qdrant`。旧选型记录通过核对 0.35.0 模块 API，认为其文本段抽象、集合生命周期等能力与本项目“索引不存正文、按版本删除、可重建”的约束不匹配；该依据只对应当时版本，不能推广成所有版本的结论。

集合名包含 Embedder 模型标识，维度来自 Embedder；换模型需重建索引。重建入口为 `scripts/index-knowledge.ps1 -Rebuild`，不新增另一份模型与维度真相。

## 5. 保留的选型理由

- **Spring MVC**：现有数据库和事务调用是同步模型，`SseEmitter`可承载事件和回复增量，不必为演示项目引入全链路响应式编程。
- **MyBatis / JdbcTemplate**：号源行锁、条件更新、幂等和审计需要明确 SQL。实际代码同时使用两者，不把所有访问笼统称为 MyBatis-Plus CRUD。
- **模块化单体**：Agent、预约、知识和接口保留职责边界，可共享事务基础；目前没有微服务拆分。
- **MySQL + 可重建向量索引**：业务事实、文档版本与原文保留在关系库，语义索引可独立重建；向量检索不承担库存正确性。
- **原 Redis 路线选 Lettuce**：现有 Starter 是历史技术基础；已确认将 Redis 租约、缓存与多实例扩展后置，是否启用须由实际需求与验证支撑，预约正确性仍由 MySQL 保障。当前首版范围见[交付计划](../product/delivery-roadmap-v1.md)。

旧方案没有引入 Spring Cloud、消息队列、Elasticsearch、Redisson、多 Agent 框架、独立认证中心或 Kubernetes。当前代码也没有这些组件；本次整理不增加它们，也不把旧“2—4 周”工期当作新的承诺。

## 6. 验证方法与证据限制

| 层次 | 当前证据与入口 |
|---|---|
| 核心行为 | JUnit 5/Mockito 与可控模型测试，不依赖真实模型调用 |
| MySQL 集成 | `scripts/test-mysql.ps1`启动本机 MySQL 可执行程序的隔离实例，并验证本轮报告；不是当前脚本启动 Testcontainers |
| Qdrant 适配器 | `scripts/test-qdrant.ps1`依赖可达服务，条件启用单独测试 |
| HTTP | Spring Boot Test / MockMvc；包含会话登录/退出和流式入口归属验证 |
| 前端 | `vue-tsc`、Vite 构建及真实 Chrome 中的预约流程通过 |
| Testcontainers 1.20.6 / Awaitility 4.2.2 | 测试依赖已声明，不能据此声称 Redis 容器或租约测试已实现 |

2026-10-16 的历史记录报告隔离 MySQL 8.0.34 下 122 项测试执行通过，Qdrant 另有 16 项适配器测试。裸 `mvn test`会跳过条件集成测试，不能以“构建成功”代替全量通过。S1 最新的隔离 MySQL、前端及浏览器证据见[S1 验收](../product/s1-patient-session-acceptance.md)。

后续若升级依赖或准备复现，应记录实际 JDK/Maven/Node/pnpm 版本，复核数据库迁移、模型适配器、索引维度与前端类型检查。跨大版本升级需要重新验证；不确定的环境与性能数据不得提前写成已通过结论。
