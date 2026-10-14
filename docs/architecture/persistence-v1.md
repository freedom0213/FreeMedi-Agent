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
- `V3__seed_demo_data.sql`：写入合成患者、管理员、科室、医生、排班和号源，仅供本地演示。

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

## 尚未纳入本适配器的内容

- 任务 API 的 request id 幂等入口；
- Redis 任务短租约和限流；
- DeepSeek 决策模型与 SSE 事件发布。

这些能力需要在 AgentRuntime 的持久化边界稳定后逐项接入。
