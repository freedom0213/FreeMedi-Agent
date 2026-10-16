# MediFlow Agent 持久化实现 v1

## 当前实现

AgentRuntime 通过 `TaskStateStore` 隔离存储技术。当前有两种实现：

- `InMemoryTaskStateStore`：单元测试和无数据库演示使用，进程重启后数据丢失；
- `MySqlTaskStateStore`：`mysql` Spring Profile 下启用，使用 MyBatis 注解 Mapper 和 Flyway 管理表结构。

MySQL 适配器保存任务当前状态、事实 JSON、最新工具结果、对话轮次、工具调用、预约方案和状态迁移事件。一次检查点会在同一事务中写入这些记录；只有事务提交后，内存上下文才确认新的 `stateVersion`。

## 并发与恢复

`agent_task.state_version` 作为乐观锁版本。更新条件包含加载时的版本号，影响行数不是 1 时抛出 `OptimisticLockingFailureException`，调用方必须重新加载任务再处理。Redis 短租约可以在后续减少冲突，但不替代这个数据库保护。

任务重新加载时会恢复：

- 当前任务状态、步骤数和轮次数；
- 已收集事实和最新工具结果；
- 最新预约方案及其版本、状态、摘要校验值；
- 已完成的对话轮次。

工具调用的参数和结果只保存结构化摘要，不保存异常堆栈、密钥或模型隐藏思考过程。

## Flyway

- `V1__initial_schema.sql`：用户、医院目录、号源、预约、Agent 任务及审计表；
- `V2__task_last_tool_result.sql`：为任务增加最新工具结果字段；
- `V3__seed_demo_data.sql`：写入合成患者、管理员、科室、医生和固定日期排班号源，仅供本地演示；
- `V4__seed_near_term_demo_slots.sql`、`V5__seed_neurology_and_gastroenterology.sql`：按 `CURRENT_DATE()` 生成迁移当天的未来 1—14 天排班号源，并补充神经内科和消化内科医生；
- `V6__portal_appointment_source.sql`：允许 `appointment.plan_id` 为空并新增 `booked_via`，使普通挂号不必伪造 Agent 计划；
- `V7__knowledge_documents_and_chunks.sql`：医院资料的文档与片段表，供后续语义检索使用。

迁移只执行一次，因此 V4/V5 生成的近期待选时间会随时间自然耗尽。需要续期时运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/seed-demo-slots.ps1 -MySqlBin <mysql bin 目录>
```

该脚本执行 `database/seed-demo-slots.sql`，按 `CURRENT_DATE()` 重新生成未来 N 天（默认 14，上限 30）的排班和号源，可反复运行：既有的排班、已被预约或关闭的号源都不会被覆盖，新加入的启用中医生会自动补齐。

默认配置关闭 Flyway，避免开发者启动应用时意外连接本地数据库。使用 `mysql` Profile 时开启迁移，并要求指向专用的 `mediflow` 数据库，例如：

```powershell
java -jar target/mediflow-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

隔离验证命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/test-mysql.ps1
```

脚本会创建工作区 `target/` 下的临时 MySQL 数据目录，运行完自动关闭实例；它不会连接或清理开发者已有的 MySQL 数据库。当前环境实际验证使用 MySQL 8.0.34；项目目标版本是 MySQL 8.4，8.4 兼容性尚待该版本实例验证。

预约写服务在 `mysql` Profile 下以单个 MySQL 事务锁定号源、写入预约与审计记录，并以患者和幂等键识别重试。预约创建后由 AgentRuntime 在事务外核验结果。集成测试覆盖重复幂等请求和另一个方案竞争已占号源的场景。

`operation_audit_log` 的三种结果都会实际写入：成功记 `SUCCEEDED`，业务规则拒绝记 `REJECTED` 并带上拒绝原因和被拒号源，未预期异常记 `FAILED`。业务拒绝是返回值而不是异常，所以它的记账与业务结果在同一个事务里提交；异常路径上的事务已经回滚，失败记录改用独立事务尽力写入，且记录失败不会替换原始异常。幂等重放命中既有预约时不重复记账。

`MySqlReadToolExecutor` 在同一 Profile 下提供 `findAvailableSlots` 和 `getAppointmentRules` 两个只读工具。号源查询只读取 `AVAILABLE`、开放排班和启用中的科室/医生，使用参数化筛选和结果上限；工具结果可以直接作为下一轮模型决策上下文，但不会执行预约写入。

## API 与 SSE 演示边界

`mysql` Profile 下提供以下最小接口：

- `POST /api/v1/tasks`：使用 `X-Demo-User-Id` 创建本地演示任务；
- `POST /api/v1/tasks/{taskId}/messages`：处理一轮用户消息；
- `POST /api/v1/tasks/{taskId}/confirm`：提交方案版本和幂等键，执行确认预约；
- `GET /api/v1/tasks/{taskId}`：查询任务快照；
- `GET /api/v1/tasks/{taskId}/events`：以 `text/event-stream` 回放数据库中已有的状态迁移和工具调用事件。

演示身份只用于本地求职作品，不是生产认证方案。事件回放使用 SSE 传输格式逐条发送历史事件，因此具备流式传输形式，但当前 Agent 调用完成后事件才可读取；DeepSeek token 级实时流和多实例事件推送属于后续能力。

配置 `DEEPSEEK_API_KEY` 后，`DeepSeekDecisionModel` 使用 LangChain4j 0.35.0 和 OpenAI 兼容接口向模型声明只读工具，并把模型返回的工具调用交给 `AgentRuntime` 执行。未配置密钥时自动使用确定性 `DemoDecisionModel`，可离线走通演示闭环。真实 DeepSeek API 调用需要有效密钥和网络访问，本地测试通过 mock 模型验证适配器映射，不会产生模型 API 费用。

## 尚未纳入本适配器的内容

- 任务 API 的 request id 幂等入口；
- Redis 任务短租约和限流；
- DeepSeek 决策模型与 SSE 事件发布。

这些能力需要在 AgentRuntime 的持久化边界稳定后逐项接入。
