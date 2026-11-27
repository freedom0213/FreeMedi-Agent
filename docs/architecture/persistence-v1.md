# FreeMedi-Agent 持久化与运行接口

> 核对日期：2026-11-17；S1 更新：2026-11-18。当前写时校验及隔离测试结果已同步，开发数据未改、未执行知识导入；完整实测与未测边界见[S0 记录](../product/s0-baseline-2026-11-17.md)。
> 本文说明当前事实来源、事务和恢复能力，不把旧计划中的后续功能视为已完成。

## 1. 任务检查点与恢复

[TaskStateStore](../../src/main/java/com/mediflow/agent/agent/persistence/TaskStateStore.java)隔离存储技术：

- `InMemoryTaskStateStore`供行为测试使用，进程重启后丢失数据。
- [MySqlTaskStateStore](../../src/main/java/com/mediflow/agent/agent/persistence/MySqlTaskStateStore.java)在 `mysql` profile 下启用，通过 MyBatis 注解 Mapper 保存任务。

一次 `save` 在事务中更新任务当前状态、facts JSON、最新工具结果，并保存轮次、工具调用、活动方案与待提交的状态迁移。`agent_task.state_version`参与条件更新；影响行数不是 1 时抛出乐观锁异常，调用方需要重新加载。事务提交后才更新内存版本和清空待保存记录。

加载时恢复当前状态、计数、facts、最新工具结果、已保存轮次和最新方案。S2 的共享需求及当前候选批次随 facts 一并恢复，见[S2 记录](../product/s2-appointment-options.md)。方案正文以快照保存并校验 SHA-256 摘要，同一版本的方案内容不可被任意改写。

当前保存的是结构化工具参数与结果 JSON，以及展示所需用户消息和助手消息；结果可包含号源明细，不能笼统称为只保存一行摘要。没有保存模型隐藏思考或完整原始提供方响应的通用路径。后续增加工具时仍需控制保存内容，避免密钥和无关敏感信息进入结果。

**能够重新加载检查点，不等于能自动恢复所有处理中断。** 当前没有后台接管 `EXECUTING` / `VERIFYING`、断线自动续跑、完整核验补偿或客户端 request id 幂等入口。Runtime 内部为每轮生成 request id，客户端重发消息不会自动复用。终态任务不能接收新消息，详见[Runtime 说明](agent-runtime-v1.md)。

## 2. 预约事务与审计

[AppointmentWriteService](../../src/main/java/com/mediflow/agent/service/appointment/lifecycle/AppointmentWriteService.java)是 Agent 与普通挂号共用的预约写入口：

1. Agent 路径锁定并校验所属方案、版本、确认状态与期限；普通挂号直接提供具体号源。
2. 按患者和幂等键查找原预约。Agent 路径比对方案，普通挂号路径比对号源；将键用于其他目标会返回冲突。
3. 锁定号源时联查开放排班、启用医生/科室并要求开始时间仍在未来；随后校验库存状态，再以 `status = 'AVAILABLE'`条件更新，仅影响一行时继续。
4. 写入预约、来源和确认版本，Agent 路径同时更新方案状态，写审计。

上述动作在写服务的事务内完成。任务检查点、预约写入和 Runtime 提交后的核验是分开的操作，不能说整个 Agent 执行都处于一个数据库事务。

- 普通挂号：`plan_id = NULL`、`booked_via = PORTAL`、`confirmed_version = 0`。
- Agent 预约：关联已确认方案，来源为 `AGENT`；Runtime 在写服务提交后查询本人 `BOOKED`预约完成核验。
- Agent 先在任务检查点保存原方案和请求标识，再调用预约写入。核验暂时失败时保留待核验状态和已知预约编号。恢复通过本人、原方案及请求标识查询，已提交时可补齐任务状态。普通挂号也可以按原请求核验，预约与占号仍由原事务原子提交。见[S5 首批验收](../product/s5-booking-recovery.md)。
- 写服务支持幂等重放；Runtime 确认接口还会先校验等待确认状态，因此不能把写服务幂等能力表述为任意重复 HTTP 确认都会返回原成功响应。
- S1 新增的有效性过滤不改变既有结果优先的幂等查找顺序；目录停用后重放已成功的相同请求仍返回原预约。可用库存的竞争拒绝与失效目录/排班拒绝仍沿用现有业务码，没有改接口或表结构。

`operation_audit_log`当前按结果记录：

| 结果 | 记账方式 |
|---|---|
| `SUCCEEDED` | 与预约写入同一事务提交 |
| `REJECTED` | 业务拒绝以返回值表达，拒绝审计随当前事务提交 |
| `FAILED` | 未预期异常回滚后，使用独立事务尽力写入；审计失败不替换原异常 |
| 幂等重放 | 返回既有结果，不重复记账 |

失败审计是尽力记录，不保证任何数据库错误下都能落库。审计表与预约共享的约束也可能导致两者同时写失败。历史证据见[M2 验收](../archive/product/m2-acceptance.md)。

## 3. 迁移和演示数据

当前已存在 [Flyway V1—V8 迁移目录](../../src/main/resources/db/migration/)：

| 迁移 | 用途 |
|---|---|
| V1 | 用户、目录、排班、号源、预约、Agent 与审计初始结构 |
| V2 | 任务最新工具结果 |
| V3 | 合成账号、目录与固定日期排班；含 2027 年 1 月的演示号源 |
| V4 / V5 | 按迁移当天的 `CURRENT_DATE()`生成未来 1—14 天号源；V5 增加神经内科与消化内科 |
| V6 | 预约 `plan_id`允许为空，新增 `booked_via`，支持普通挂号 |
| V7 | 知识文档、版本、片段与索引状态 |
| V8 | 在 `sys_user` 写入两位本地合成患者的 BCrypt 哈希，保留原患者 ID |

Flyway 迁移只执行一次，不会每天续期号源。过去的“未来 14 天”窗口会耗尽；固定远期号源可能被当成最近备选。演示前应按当前日期核对数据，需要时使用[续期脚本](../../scripts/seed-demo-slots.ps1)：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-demo-slots.ps1 -MySqlBin <mysql-bin目录>
```

脚本默认生成未来 14 天，可设为最多 30 天；使用既有唯一键保持幂等，不覆盖已有预约或关闭号源。本轮没有执行该脚本，也没有确认本机当前号源状态。

基础配置关闭 Flyway，`mysql` profile 开启迁移。这只控制迁移，不等于禁止数据源建立连接。启动前需自行准备专用数据库和凭据，不能把旧本机状态当新环境已就绪。启动已有构建产物的示例：

```powershell
java -jar target/mediflow-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

从项目根目录启动时，Spring Boot 可以加载 Git 忽略的本地 `config/application-mysql.yml`；仓库内 `src/main/resources/application-mysql.yml`是另一个文件，负责 profile 迁移配置。本文不记录任何本地密钥或密码。

## 4. 知识事实来源与向量索引

资料原文、版本和发布状态在 MySQL 的 `knowledge_document` / `knowledge_chunk`；Qdrant 只保存可重建向量及 `status`、`model`、`doc_type`、`document_id`、`version`、`department_id`、`doctor_id`过滤字段，不保存正文或实时号源。

- 检索先取候选片段 id，再回读 MySQL 并要求版本匹配、状态为 `PUBLISHED`。资料下架不依赖先清理旧向量。
- 导入与索引分开：`scripts/import-knowledge.ps1`读取 `knowledge/**/*.md`切块入库；`scripts/index-knowledge.ps1`生成向量并写索引，`-Rebuild`先清索引再从 MySQL 重建。
- 导入按内容哈希判断是否变化；内容未变跳过，更新升版本并归档旧版本。重复导入不会因旧版本已下架就自动重新发布。
- 导入和索引运行器是显式启用的批处理，完成后退出；索引片段有 `PENDING` / `INDEXED` / `FAILED`状态，可重试失败片段。
- 默认 Embedding 是本地中文模型（512 维，无需模型 API Key），但实际持久化索引与检索仍依赖可达的 MySQL 和 Qdrant。
- 集合名包含模型标识，维度取自 Embedder。更换模型必须重建索引，不能将不同模型的向量混用。读路径不自动创建缺失集合，索引故障不能当作无命中。
- 删除 Markdown 源文件不会自动让已发布数据库资料下架，不能靠清理文件替代资料生命周期操作。

[Compose](../../docker-compose.yml)目前只包含 Qdrant；不提供 MySQL、Redis或整套应用一键启动。首批资料咨询和患者引用已在真实 Qdrant 验证。证据快照随任务轮次保存在已有 facts 中，见[S3 验收](../product/s3-knowledge-consultation.md)。

## 5. HTTP 与 SSE 的实际边界

[AgentTaskController](../../src/main/java/com/mediflow/agent/controller/patient/guide/AgentTaskController.java)在 `mysql` profile 下提供：

| 路径 | 语义 |
|---|---|
| `POST /api/v1/tasks` | 创建演示任务 |
| `POST /api/v1/tasks/{taskId}/messages` | 同步处理一轮消息 |
| `POST /api/v1/tasks/{taskId}/messages/stream` | SSE 工具进度、已保存回复和最终结果 |
| `POST /api/v1/tasks/{taskId}/confirm` | 携带具体方案版本与幂等键确认预约 |
| `GET /api/v1/tasks/{taskId}` | 本人任务快照 |
| `GET /api/v1/tasks/{taskId}/events` | 本人已持久化事件回放，发送完后关闭 |
| `GET /api/v1/tasks/{taskId}/trace` | 本人任务追踪。执行摘要使用既有状态表的 `metadata` |

普通挂号另有科室、医生、严格号源查询、创建预约、我的预约与预约详情接口，分别由 `PortalCatalogController`、`PortalAppointmentController`提供。当前没有管理端或取消/改期接口。

POST SSE 执行本轮任务并发送已保存的回复。GET SSE 只回放既有记录，结构化执行细节可通过追踪接口读取。追踪摘要不包含用户原话、资料正文和请求密钥。

任务 REST/SSE 和预约接口使用 Spring Security 会话中的患者身份，应用层继续检查任务归属并按患者查询预约。写请求需要当前会话的 CSRF 令牌。登录入口及旧客户端迁移用法见[S1 验收](../product/s1-patient-session-acceptance.md)。

## 6. 测试证据与尚未实现内容

[MySQL 测试脚本](../../scripts/test-mysql.ps1)创建工作区 `target/`下的独立临时实例和空库，不使用现有业务库，结束时关闭实例；通过后尝试清理，`-KeepData`或失败保留诊断数据。核心集成测试会提交夹具，不能把测试 URL 指向已有业务数据。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-mysql.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-qdrant.ps1
```

MySQL 脚本排除需真实 Qdrant 的测试，并检查本轮测试报告及跳过项；Qdrant 脚本先检查可达性再执行适配器测试。裸 `mvn test`会跳过受条件保护的集成测试，不能等价于全量执行。

2026-10-16 历史记录为隔离 MySQL 8.0.34 上 122 项执行通过。2026-11-17 的 S0 日期修复后恢复 122 项全过；S1 新增 6 项集成场景先确认 5 项失败，再补写时过滤，最终 **128 项全过、0 失败/错误/跳过，含 51 项 MySQL 集成测试**。Qdrant 另有 16 项测试，本次预检不可达而未执行；MySQL 8.4 与真实模型生成在此批未验收。S1 收尾在 MySQL 8.0.34 上通过 129 项回归，独立前后端与 Chrome 验收见[S1 记录](../product/s1-patient-session-acceptance.md)。证据见[S0 记录](../product/s0-baseline-2026-11-17.md)。

仍未实现的内容包括：客户端消息 request id 幂等入口、Redis 租约/限流、完整处理中断与核验恢复、多实例事件推送，以及知识管理页面和真实认证。它们是代码边界与原计划遗留，不是本轮重新批准的功能清单。
