# MediFlow Agent 文档索引

## 当前基线

| 分类 | 文档 | 用途 | 状态 |
|---|---|---|---|
| 产品 | [项目计划 v2](product/project-plan-v2.md) | 项目范围、业务主线和验收标准 | 当前产品基线 |
| 产品 | [项目现状文档](product/project-status.md) | 初始规划和讨论记录 | 历史参考 |
| 架构 | [Agent Runtime v1](architecture/agent-runtime-v1.md) | Agent 执行循环、模块职责、状态和确认门禁 | 当前技术基线 |
| 架构 | [持久化实现 v1](architecture/persistence-v1.md) | MySQL 任务恢复、乐观锁和 Flyway 迁移 | 当前实现基线 |
| 架构 | [技术栈决策 v1](architecture/tech-stack-v1.md) | 语言、框架、基础设施和版本基线 | 当前技术基线 |
| 数据库 | [数据模型 v1](database/data-model-v1.md) | MySQL 实体、约束、事务和 Redis 职责 | 当前逻辑设计 |

## 目录约定

- `docs/product/`：产品目标、范围、流程和验收标准；
- `docs/architecture/`：Agent 运行时、模块接口和系统架构；
- `docs/database/`：数据模型、索引、事务和迁移设计；
- `database/`：可执行但需要人工审阅的数据库脚本；
- `src/`：后续 Spring Boot 后端代码；
- `web/`：后续 Vue 前端代码。

## 当前状态

项目已完成 AgentRuntime 核心行为、MySQL 持久化、只读号源查询工具、预约写服务和最小 HTTP/SSE API 的首版实现。Flyway 管理任务与医院演示数据，任务恢复、方案/轮次/工具/状态事件保存、乐观锁、号源查询、确认后原子占用号源、预约幂等、核验、任务接口和事件回放均已接入；MySQL 集成测试在 8.0.34 上验证，MySQL 8.4 尚未验证。当前 API 使用 `X-Demo-User-Id` 解析本地演示账号，SSE 回放已持久化的状态与工具事件，不等同于模型 token 实时流。DeepSeek、Redis 和 Vue 前端仍未接入。V3 提供的账号、科室、医生、排班和号源均为合成演示数据，不代表真实医院或医疗信息。技术基线已经确定：

- Java 17 + Spring Boot 3.4.5 + LangChain4j 0.35.0；
- MySQL 8.4 作为持久化事实来源；
- Redis 7.4 用于短期任务协调、限流和可重建缓存；
- Vue 3.5 + TypeScript + Vite 作为前端；
- DeepSeek 作为模型服务；
- Git 仅做本地管理，不配置远程仓库。

## 下一步推荐

下一步接入 DeepSeek 的结构化工具调用，再将 Vue 页面连接到这些 API；之后加入 Redis 短租约，降低同一任务并发处理冲突。
