# MediFlow Agent 文档索引

## 当前基线

| 分类 | 文档 | 用途 | 状态 |
|---|---|---|---|
| 产品 | [项目计划 v2](product/project-plan-v2.md) | 项目范围、业务主线和验收标准 | 当前产品基线 |
| 产品 | [项目现状文档](product/project-status.md) | 初始规划和讨论记录 | 历史参考 |
| 架构 | [Agent Runtime v1](architecture/agent-runtime-v1.md) | Agent 执行循环、模块职责、状态和确认门禁 | 当前技术基线 |
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

项目处于后端工程骨架阶段，Agent 和预约业务尚未实现。技术基线已经确定：

- Java 17 + Spring Boot 3.4.5 + LangChain4j 0.35.0；
- MySQL 8.4 作为持久化事实来源；
- Redis 7.4 用于短期任务协调、限流和可重建缓存；
- Vue 3.5 + TypeScript + Vite 作为前端；
- DeepSeek 作为模型服务；
- Git 仅做本地管理，不配置远程仓库。

## 下一步推荐

下一步实现 AgentRuntime 的内存适配器和自动化测试：

1. 建立 `agent`、`appointment`、`audit`、`knowledge` 模块目录；
2. 实现任务状态、决策协议和只读工具执行器；
3. 用假的模型和模拟号源覆盖追问、工具循环、方案确认门禁和失败终态；
4. 验证通过后再接入 MySQL、Redis、DeepSeek 和 Vue 页面。

这样可以先验证 Agent 核心行为，再把基础设施接入，不会让数据库和前端细节掩盖运行时设计问题。
