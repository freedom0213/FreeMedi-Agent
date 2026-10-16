# FreeMedi-Agent 文档索引

## 当前基线

| 分类 | 文档 | 用途 | 状态 |
|---|---|---|---|
| 设计 | [前端方向 v1](design/frontend-direction-v1.md) | 产品门户定位、页面信息架构和视觉原则 | 已确认 |
| 设计 | [前端布局方案 v1](design/frontend-layout-v1.md) | 患者端页面、Agent 流程和后续管理端布局 | 患者端首版已实现 |
| 产品 | [项目计划 v2](product/project-plan-v2.md) | 项目范围、业务主线和验收标准 | 当前产品基线 |
| 产品 | [整体交付计划 v1](product/delivery-roadmap-v1.md) | Agent、RAG、预约业务、前端和简历交付阶段 | 当前执行计划 |
| 产品 | [患者导诊与预约修复记录](product/patient-guide-acceptance.md) | 用户反馈、修复边界、验证证据与复测建议 | 本轮已验证 |
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

项目英文名称已确认为 **FreeMedi-Agent**。README 暂不修改。前端视觉方向已确认为 A+B：Codex 式克制视觉与 Linear 式状态组织；首页定位为智慧医院就诊服务门户，不是对话编辑器。

项目已完成 AgentRuntime 核心行为、MySQL 持久化、只读号源查询工具、预约写服务、最小 HTTP/SSE API 和 DeepSeek Function Calling 适配器的首版实现。任务恢复、方案/轮次/工具/状态事件保存、乐观锁、确认后原子占用号源、预约幂等与核验均已接入；MySQL 集成测试在 8.0.34 上验证，MySQL 8.4 尚未验证。患者导诊已调整为症状优先、口语日期、同科室备选时间和可继续调整的待确认方案；模型协议异常有一次有限纠正，临时故障可重试。V4 已追加近期合成排班和号源，V5 已补神经内科和消化内科演示数据。患者端已支持发起智能分诊和发送消息即时显示。真实模型 HTTP 场景及浏览器预约闭环已通过；**122 项自动化测试全部执行通过（0 失败 0 跳过，含 45 项隔离 MySQL 集成测试）**，注意裸 `mvn test` 会跳过这些集成测试，必须通过 `scripts/test-mysql.ps1` 运行才等价于全量通过，项数以该脚本的输出为准。最近号源请求经修复后重复实测通过。详情与限制见[患者导诊与预约修复记录](product/patient-guide-acceptance.md)。

M2 已提供不经过 Agent 的普通业务入口：科室目录、科室下医生、严格号源查询、普通挂号下单和我的预约，均只暴露启用中的目录数据，且只能读取本人预约；业务冲突返回 HTTP 200 加业务码。普通挂号与 Agent 确认共用同一个写入口，并发争抢同一号源只会成功一次；`operation_audit_log` 会记录成功、被拒和失败三种结果。内部设计见[M2 完整预约服务实施方案](product/m2-appointment-service-plan.md)，验收证据见[M2 完整预约服务验收记录](product/m2-acceptance.md)。

M3 进行中（第 2/6 块）：医院资料已有文档与片段两层存储，可用 `scripts/import-knowledge.ps1` 把 `knowledge/` 下的资料切块入库，内容未变的文件会被跳过，改过的资料升版本并下架旧版本；Embedding 与向量索引已抽象为端口，默认用**本地中文模型**（512 维，无需 Key），因此整条"入库 → 向量 → 检索"链路可在无外部服务的情况下跑通。**索引默认关闭**（`mediflow.knowledge.index=none`）：未配置向量库时检索会回答"暂时查不到资料"而不是"没有这条规定"，两者必须可区分。语义检索接入 Agent 与引用展示见[M3 实施方案](product/m3-rag-plan.md)。Redis 尚未接入。所有医院数据均为合成演示数据。技术基线已经确定：

- Java 17 + Spring Boot 3.4.5 + LangChain4j 0.35.0；
- MySQL 8.4 作为持久化事实来源；
- Redis 7.4 用于短期任务协调、限流和可重建缓存；
- Vue 3.5 + TypeScript + Vite 作为前端；
- DeepSeek 作为模型服务；
- Git 仅做本地管理，不配置远程仓库。

API key 保存在 Git 忽略的本地外置配置 `config/application-mysql.yml`，从项目根目录以 `mysql` profile 启动会自动加载，无需每次重新配置。演示 API 使用 `X-Demo-User-Id` 识别本地账号。SSE 现有两条不同路径：`POST /api/v1/tasks/{taskId}/messages/stream` 是真实模型增量流（未配置 Key 时使用本地确定性回退），`GET /api/v1/tasks/{taskId}/events` 是已持久化事件的回放。

## 下一步推荐

2026-10-15 已完成最近号源追问修复、头痛/腹痛演示数据、智能分诊入口和发送即时显示。整体开发按[整体交付计划 v1](product/delivery-roadmap-v1.md)推进，下一阶段固定为 M1：完成真实模型流式回复、结构化分诊推荐和恢复验证；随后依次完成完整预约服务、RAG、最小管理端、稳定性和简历交付。每次会话报告计划中的阶段和证据，不再单独追加脱离总计划的下一步。
