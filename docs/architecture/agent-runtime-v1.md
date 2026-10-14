# MediFlow Agent 核心架构设计 v1

> 当前阶段：Agent 运行时设计与数据库逻辑模型设计，不包含建表脚本。
>
> 目标：明确 Agent 如何运行、哪些决策交给模型、哪些行为由后端强制控制，以及后续代码应该从哪里开始。

## 1. 设计结论

MediFlow 采用**单 Agent、受限工具调用、显式任务状态、人工确认门禁**的架构。

模型可以根据用户目标和工具结果选择下一步，但模型不能直接访问数据库、修改任务状态或执行预约写操作。所有业务写入都经过后端确认命令和业务服务。

核心运行过程：

```text
用户消息
  ↓
AgentRuntime 加载任务上下文
  ↓
模型返回一个结构化决策
  ├─ ASK_USER       → 询问用户并暂停
  ├─ CALL_READ_TOOL → 后端校验并执行只读工具，再继续循环
  ├─ PROPOSE_PLAN   → 保存候选方案并等待确认
  └─ FINISH         → 结束任务

用户确认命令
  ↓
后端校验方案版本、身份、号源和幂等键
  ↓
预约应用服务执行写入
  ↓
重新查询核验
  ↓
完成或失败
```

## 2. 三种架构方案

### 方案 A：固定工作流

流程由代码写死：收集日期 → 查科室 → 查医生 → 查号源 → 确认 → 预约。

优点：实现简单，行为稳定，测试容易。

缺点：模型只负责抽取字段，不能根据工具结果灵活调整步骤；Agent 特征较弱。

适用场景：普通表单式预约，不适合作为本项目的主要展示方式。

### 方案 B：完全开放式 Agent

模型可以自由选择所有工具、改变任务状态并尝试执行写操作。

优点：模型自主性强，演示效果直观。

缺点：难以保证权限、确认、并发和业务规则；出错时很难定位和复现。

适用场景：低风险的信息查询，不适合预约写操作。

### 方案 C：受限 Agent，推荐

模型只在允许的任务状态下选择只读工具或提出下一步问题；后端控制状态迁移，预约方案由后端保存，写操作通过独立确认命令执行。

优点：同时保留 Agent 的动态决策能力和业务系统的确定性；工具调用可审计，任务可恢复，失败可测试。

缺点：需要设计状态机、决策协议和确认门禁。

MediFlow 采用方案 C，因为它最能体现业务 Agent 的价值，也能控制个人项目的实现风险。

## 3. 模块与职责

### 3.1 `AgentRuntime`：外部唯一入口

这是 Agent 模块对外提供的深接口。Controller、SSE 适配器和测试只依赖它，不直接操作模型或工具。

```java
public interface AgentRuntime {
    AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message);

    ConfirmationResult confirmPlan(
            TaskId taskId,
            Principal principal,
            PlanId planId,
            int planVersion,
            String idempotencyKey);

    TaskSnapshot getTask(TaskId taskId, Principal principal);
}
```

这个接口的使用约束：

- `handleMessage` 只接收用户消息，不接受模型伪造的状态或身份；
- `confirmPlan` 是后端命令，不通过 LLM 解析“好的”“确认”等自然语言；
- 两个方法都必须验证任务归属和用户身份；
- 返回值包含用户消息、当前状态和可展示事件摘要；
- 内部模型、工具和状态存储可以替换，调用方不需要知道实现细节。

### 3.2 `DecisionModel`：模型适配器

`DeepSeekDecisionModel` 使用 LangChain4j OpenAI 兼容接口，把任务上下文和只读工具 JSON Schema 发送给 DeepSeek，并将 `tool_calls` 响应映射为 `CALL_READ_TOOL`。普通文本响应必须是受限 JSON，再映射为 `ASK_USER`、`PROPOSE_PLAN` 或 `FINISH`。患者身份与方案过期时间由后端注入，不由模型决定。未配置 `DEEPSEEK_API_KEY` 时，`DemoDecisionModel` 作为确定性本地演示适配器。

```java
public interface DecisionModel {
    AgentDecision decide(DecisionContext context, List<ReadToolSpec> tools);
}
```

`AgentDecision` 只能是以下几类：

```text
ASK_USER(question, missingFields)
CALL_READ_TOOL(toolName, typedArguments)
PROPOSE_PLAN(planDraft)
FINISH(message)
```

模型返回未知决策、非法参数或不符合当前状态时，AgentRuntime 必须拒绝并进入受控失败，而不是尝试猜测模型意图。

### 3.3 `ReadToolExecutor`：只读工具执行器

工具注册表维护工具名称、输入约束、允许状态和实现适配器。

```java
public interface ReadToolExecutor {
    ToolResult execute(ToolCall call, TaskSnapshot task);
    List<ReadToolSpec> tools();
}
```

执行器负责：

- 校验工具是否存在；
- 校验当前状态是否允许调用；
- 校验参数类型和业务范围；
- 注入当前用户身份；
- 调用预约领域服务；
- 返回稳定的结构化结果和错误码；
- 记录工具调用事件。

### 3.4 `AppointmentApplicationService`：业务写入模块

预约创建不作为模型可自由调用的工具，而由确认命令进入预约应用服务：

```java
public interface AppointmentApplicationService {
    AppointmentExecutionResult createConfirmedAppointment(
            ConfirmedPlan plan,
            Principal principal,
            String idempotencyKey);

    AppointmentVerificationResult verify(AppointmentId appointmentId, Principal principal);
}
```

这个模块内部负责方案版本、身份、预约规则、号源占用、幂等和事务。AgentRuntime 只负责组织调用，不复制这些业务规则。

### 3.5 `TaskStateStore`：任务状态适配器

正式运行使用 MySQL 实现，AgentRuntime 不依赖具体存储技术；核心行为测试仍可使用内存实现。

```java
public interface TaskStateStore {
    TaskContext load(TaskId taskId);
    void save(TaskContext context);
    void appendEvent(TaskEvent event);
}
```

Redis 用于同一任务的短租约、限流和可重建缓存，不作为任务状态的唯一存储。MySQL 事务、行锁或乐观锁负责最终一致性。

当前代码已提供 `MySqlTaskStateStore`：任务检查点使用 `agent_task.state_version` 做乐观锁，任务轮次、工具调用、方案和状态事件在同一事务中保存；加载任务时会恢复这些持久化记录。`InMemoryTaskStateStore` 仅用于行为测试。

### 3.6 `AgentEventPublisher`：前端轨迹适配器

负责把领域事件转换为 SSE 或其他前端传输格式。它不参与 Agent 决策。

可展示事件包括：

```text
TASK_STARTED
QUESTION_ASKED
READ_TOOL_STARTED
READ_TOOL_SUCCEEDED
READ_TOOL_FAILED
PLAN_PROPOSED
CONFIRMATION_REQUIRED
EXECUTION_STARTED
EXECUTION_VERIFIED
TASK_COMPLETED
TASK_FAILED
```

轨迹只展示状态、工具名称、参数摘要和结果摘要，不展示模型内部隐藏思考过程，也不把未经处理的异常堆栈发送给用户。

## 4. AgentRuntime 的执行规则

### 4.1 处理用户消息

```text
1. 加载任务和当前用户
2. 校验任务归属、状态和消息长度
3. 将用户消息加入当前 turn
4. 在最大步骤数内循环：
   a. 根据当前上下文调用 DecisionModel
   b. 校验模型决策是否符合当前状态
   c. ASK_USER：保存问题，进入 NEED_MORE_INFO，暂停
   d. CALL_READ_TOOL：执行只读工具，保存结果，继续循环
   e. PROPOSE_PLAN：生成有版本的方案，进入 AWAITING_CONFIRMATION，暂停
   f. FINISH：进入 COMPLETED 或明确失败终态
5. 超过最大步骤数：进入 FAILED，说明任务需要重新开始或人工处理
```

### 4.2 处理确认命令

```text
1. 加载任务、当前用户和候选方案
2. 必须处于 AWAITING_CONFIRMATION
3. 校验 planId、planVersion、expiresAt 和患者身份
4. 重新查询并锁定号源
5. 校验预约规则和幂等键
6. 执行预约创建
7. 进入 VERIFYING
8. 重新查询预约记录
9. 核验成功进入 COMPLETED，否则进入 FAILED 或待处理状态
```

用户输入“确认”不能直接作为数据库写操作条件，必须由前端携带具体方案标识和版本，由后端完成校验。

## 5. 状态与允许动作

| 状态 | 模型允许 | 后端允许 |
|---|---|---|
| `UNDERSTANDING` | 追问、调用基础只读工具 | 创建或更新任务上下文 |
| `SEARCHING` | 调用只读工具、继续追问 | 校验工具和参数 |
| `NEED_MORE_INFO` | 生成问题 | 保存用户补充信息 |
| `PROPOSAL_READY` | 调整查询或形成方案 | 保存候选方案 |
| `AWAITING_CONFIRMATION` | 解释方案、等待用户 | 接受具体确认命令 |
| `EXECUTING` | 不参与写入决策 | 执行确认后的预约 |
| `VERIFYING` | 不参与结果判定 | 查询并核验预约 |
| `COMPLETED` | 生成最终说明 | 只读任务结果 |
| `FAILED` | 生成失败说明 | 记录原因和有限重试 |

不允许模型自行把任务从 `AWAITING_CONFIRMATION` 改为 `EXECUTING`，也不允许模型返回“预约已成功”作为事实依据。

## 6. 首版只读工具

首版建议注册以下工具：

```text
searchDepartments
searchDoctors
findAvailableSlots
getAppointmentRules
verifyAppointment
```

`queryDoctorSchedule` 可以作为 `findAvailableSlots` 的内部查询步骤，不一定暴露给模型。工具越少，模型越容易选择正确，测试矩阵也越小。

首版不把 `createAppointment` 注册到模型工具列表。它是确认命令后由后端直接调用的业务能力。

## 7. 确认方案

`PROPOSE_PLAN` 产生的是后端可验证的 `AppointmentPlan`，至少包含：

```text
planId
planVersion
patientId
departmentId
doctorId
slotId
appointmentDate
timeRange
expiresAt
```

计划展示给用户前，后端生成版本标识或内容摘要。确认命令必须携带同一版本；如果号源、排班或规则发生变化，确认失败并重新查询，不能自动替换方案。

## 8. 无数据库阶段的实现方式

在数据库设计前，可以使用以下适配器完成 AgentRuntime 验证：

- `InMemoryTaskStateStore`：保存任务和事件；
- `FakeAppointmentCatalog`：提供固定科室、医生和号源；
- `FakeAppointmentApplicationService`：模拟号源占用、幂等和核验；
- `FakeDecisionModel`：测试中返回预先定义的决策序列；
- `DeepSeekDecisionModel`：配置 API Key 后调用 DeepSeek Function Calling。

这样可以先验证 Agent 的核心行为：追问、工具循环、方案暂停、确认门禁、号源失效和失败终态。后续引入数据库时，只需要替换状态存储和预约领域适配器，不需要重写 AgentRuntime。

## 9. 核心测试面

AgentRuntime 的测试不依赖真实模型，通过 `FakeDecisionModel` 控制决策序列：

- 缺少日期时返回 `ASK_USER`；
- 模型选择只读工具后能收到工具结果并继续；
- 非法工具名或非法参数会被拒绝；
- 达到最大步骤数会失败，不会无限循环；
- 形成方案后进入 `AWAITING_CONFIRMATION`；
- 用户拒绝或未确认时不会调用预约写入模块；
- 过期或版本不匹配的方案不能执行；
- 重复幂等键不会重复创建预约；
- 写入后必须执行核验；
- 工具失败、模型异常和核验失败都有明确终态；
- 用户只能访问自己的任务和预约。

真实 DeepSeek 调用只用于少量适配器测试和演示，不作为核心业务测试的唯一依据。

## 10. 已确定与暂不决定的事项

已确定：

- Java + Spring Boot + LangChain4j；
- MySQL 作为业务持久化数据源；
- Redis 用于任务短租约、限流及可重建缓存；
- Vue 前端；
- DeepSeek 模型服务。

暂不决定：

- 任务和事件的最终表结构；
- RAG 的切块、索引和知识库管理页面；
- 取消、改期和多任务并行。

逻辑数据模型已记录在[数据模型 v1](../database/data-model-v1.md)中，后续再评审并生成 DDL。
