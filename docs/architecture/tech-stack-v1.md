# MediFlow Agent 技术栈决策 v1

> 适用范围：首版可演示项目。
>
> 版本策略：以下是可复现的基线版本。允许升级同一大版本内的安全补丁和兼容补丁；跨大版本升级必须重新验证 Spring Boot、LangChain4j、数据库迁移和前端构建链路。

## 1. 已确定的完整技术栈

### 后端运行时

| 层 | 选择 | 基线版本 | 决策 |
|---|---|---:|---|
| JDK | Java | 17 LTS | 当前机器已有，Spring Boot 3.4 支持；首版不依赖虚拟线程特性 |
| 构建 | Maven | 3.9.4 | 当前机器已有；后续加入 Maven Wrapper 固定构建版本 |
| Web | Spring Boot + Spring MVC | 3.4.5 | 采用同步 MVC，事务和数据库调用模型更直观 |
| Agent | LangChain4j | 0.35.0 | 与 `zhixu` 已有实现衔接，减少学习和兼容成本 |
| HTTP 模型接入 | LangChain4j OpenAI 模块 | 与 LangChain4j 同版本 | 调用 DeepSeek 的 OpenAI 兼容接口 |
| ORM | MyBatis-Plus Spring Boot 3 Starter | 3.5.9 | 延续 `zhixu` 经验，SQL 和事务边界可控 |
| 数据库迁移 | Flyway | 10.x，由 Spring Boot BOM 管理 | 所有表结构变更可追踪、可回滚审查 |
| 参数校验 | `spring-boot-starter-validation` | 由 Spring Boot 管理 | 工具参数和 REST 请求统一校验 |
| 序列化 | Jackson | 由 Spring Boot 管理 | 统一 JSON 和结构化工具参数 |

### 安全与基础设施

| 层 | 选择 | 基线版本 | 决策 |
|---|---|---:|---|
| 认证授权 | Spring Security | 6.4.x，由 Spring Boot 管理 | 处理登录、角色和接口访问控制 |
| Token | JJWT | 0.12.6 | 首版使用短期 Access Token；不引入 OAuth 服务端 |
| 密码 | BCrypt | Spring Security 内置 | 密码只保存哈希 |
| 关系数据库 | MySQL | 8.4 LTS | 预约、号源、任务和审计的事实来源 |
| Redis | Redis | 7.4 | 任务短租约、限流和可重建缓存 |
| Redis 客户端 | Spring Data Redis + Lettuce | 由 Spring Boot 管理 | 不额外引入 Redisson；正确性由 MySQL 保证 |
| 本地编排 | Docker Compose | Compose v2 | 统一启动 MySQL、Redis 和应用依赖 |
| API 文档 | springdoc-openapi | 2.8.6 | 提供接口和工具入口的可审阅文档 |

### 前端

| 层 | 选择 | 基线版本 | 决策 |
|---|---|---:|---|
| Node.js | Node.js LTS | 24.x | 当前机器已有，使用 `.nvmrc` 固定主版本 |
| 包管理 | pnpm | 11.x | 当前机器已有，安装速度和 lockfile 管理较好 |
| 框架 | Vue | 3.5.13 | 与现有前端经验一致 |
| 构建 | Vite | 6.2.0 | 开发启动快，配置少 |
| 语言 | TypeScript | 5.7.3 | 约束 Agent 事件、任务状态和工具结果结构 |
| 状态 | Pinia | 3.0.1 | 保存当前会话、任务和轨迹状态 |
| 路由 | Vue Router | 4.5.0 | 患者端和管理端路由守卫 |
| UI | Element Plus | 2.9.5 | 快速完成表单、表格、确认弹窗和状态标签 |
| HTTP | Axios | 1.8.4 | REST 请求；SSE 使用浏览器原生 `EventSource` |

### 测试

| 层 | 选择 | 基线版本 | 用途 |
|---|---|---:|---|
| 单元测试 | JUnit 5 + Mockito | 由 Spring Boot 管理 | AgentRuntime、状态迁移和工具校验 |
| 集成测试 | Testcontainers | 1.20.6 | 使用真实 MySQL/Redis 容器验证事务和并发 |
| 等待断言 | Awaitility | 4.2.2 | 验证异步核验和 Redis 租约行为 |
| 接口测试 | Spring Boot Test + MockMvc | 由 Spring Boot 管理 | REST、鉴权、SSE 入口 |

## 2. Agent 和模型配置

| 配置 | 选择 |
|---|---|
| 对话模型 | DeepSeek API，使用 OpenAI 兼容协议 |
| 默认模型 | 通过环境变量配置，开发环境不把 Key 写入仓库 |
| API Key | `.env` 或本机环境变量，禁止写入 Git |
| 工具调用 | LangChain4j 结构化工具调用，后端白名单校验 |
| 响应传输 | REST 提交用户消息，SSE 推送执行事件和最终回复 |
| Prompt | 代码管理的版本化模板，首版不做后台任意编辑 |
| 最大步骤数 | 应用配置限制，默认 8 步 |
| 工具超时 | 应用配置限制，默认单工具 5 秒 |

## 3. RAG 的后置技术栈

RAG 不阻塞 P0/P1 预约闭环。进入 P2 后采用：

| 层 | 选择 | 说明 |
|---|---|---|
| 向量数据库 | Qdrant 1.13.x（实际 1.13.6） | 与 MySQL 业务库职责分离，支持向量检索和 metadata 过滤 |
| Embedding（默认） | 本地 ONNX `bge-small-zh-v1.5-q`，512 维 | **实际实现的默认**：模型随依赖打包，不需要 Key、不依赖外部服务，测试可离线复跑 |
| Embedding（可选） | SiliconFlow BGE-M3，1024 维 | 按配置切换；需要**独立的 Embedding Key**，不能复用对话模型的 Key |
| Rerank | BGE-reranker-v2-m3 | 只在检索质量需要时启用，且必须先有测量 |
| 文档内容 | 医院预约规则、就诊须知、服务说明 | 不放实时号源和患者隐私 |

Qdrant 只有在 P2 需要 RAG 时才加入 Docker Compose。首版不额外部署 Elasticsearch、Milvus 或 PostgreSQL，避免基础设施重复。

**换 Embedding 模型等于换索引**：向量维度由配置声明，两种模型不得混入同一个集合；切换后必须整体重建索引。这一点由 `TextEmbedder` 端口的 `name()` / `dimensions()` 承载，不是约定而是代码约束。

## 4. 关键取舍

### Spring MVC，不选 WebFlux

Agent 的模型调用可以异步，但预约写入必须遵守清晰的数据库事务边界。Spring MVC + `SseEmitter` 足够支持首版流式轨迹，代码和调试成本低于全链路响应式模型。

### MyBatis-Plus，不选 JPA

`zhixu` 已经使用 MyBatis 体系；预约号源锁定、幂等查询和状态更新需要明确 SQL。MyBatis-Plus 能减少样板代码，同时保留关键事务 SQL 的可见性。

### Lettuce，不选 Redisson

Redis 只做短租约、限流和缓存，首版不需要复杂分布式数据结构。使用 Spring Data Redis 的原子命令可以减少运行时依赖；预约正确性不依赖 Redis 锁。

### 模块化单体，不选微服务

第二个项目的主题是 Agent。模块化单体能保留清晰的领域边界和事务一致性，把时间用于工具编排、确认门禁和运行轨迹。

### Qdrant 后置，不选 MySQL 向量化

MySQL 负责结构化业务事实；向量检索需要独立能力。RAG 不是首版主线，因此把 Qdrant 推迟到 P2，避免一开始增加无关基础设施。

## 5. 不引入的技术

- Spring Cloud、Nacos、OpenFeign；
- RabbitMQ、Kafka 等消息队列；
- Elasticsearch；
- Redisson；
- 独立认证中心；
- 多 Agent 框架；
- 线上 Prompt 管理平台；
- Kubernetes。

这些技术并非不能使用，而是当前业务规模和 2 到 4 周首版周期不需要。后续只有在出现明确的扩展需求时才重新评估。

## 6. 版本验证要求

开始编码前必须验证：

1. Java 17、Spring Boot 3.4.5 和 MyBatis-Plus 3.5.9 能正常启动；
2. LangChain4j 0.35.0 能调用 DeepSeek OpenAI 兼容接口；
3. MySQL 8.4 的 Flyway 迁移和 JSON 字段可用；
4. Redis 7.4 的租约、过期和限流命令可用；
5. Node.js 24、pnpm 11、Vue 3.5.13、Vite 6.2.0 和 TypeScript 5.7.3 能构建；
6. Testcontainers 能启动 MySQL 和 Redis 并完成一条预约并发测试。

如果某个依赖的补丁版本与本机 Maven/npm 镜像不可用，只允许在同一大版本内调整补丁版本，并在 `docs/architecture/tech-stack-v1.md` 记录实际版本。
