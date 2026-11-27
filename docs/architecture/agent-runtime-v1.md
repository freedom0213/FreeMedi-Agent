# FreeMedi-Agent Agent Runtime 架构与实现

> 核对日期：2026-11-17，依据当前工作区源码静态核对；本轮未启动服务或重跑测试。
> 本文维护已实现的运行边界和约束。用户已确认首版范围与完成标准，统一见[首版交付计划](../product/delivery-roadmap-v1.md)；具体接口、状态和数据模型变更仍须实施前评审，本轮不修改实现。

## 1. 当前架构

系统采用单 Agent、受限只读工具循环、显式任务状态和独立预约确认命令。Java 后端控制状态、数据归属和业务写入，LangChain4j 负责模型调用封装。

```text
患者消息
  → 加载任务、校验归属、开启本轮
  → 模型决策
      ASK_USER       → 保存问题、等待用户
      CALL_READ_TOOL → 执行只读工具。查号后保存候选等待选择，其他工具继续决策
      PROPOSE_PLAN   → 保存有版本的预约方案、等待确认
      FINISH         → 结束本次任务

具体方案的确认命令
  → 校验任务归属、方案标识/版本/期限
  → 保存执行状态
  → 共用预约写服务在事务内校验并占号、写预约和审计
  → 提交后查询核验
  → 保存完成或失败状态
```

源码入口：[AgentRuntimeService](../../src/main/java/com/mediflow/agent/agent/runtime/AgentRuntimeService.java)、[TaskContext](../../src/main/java/com/mediflow/agent/agent/task/TaskContext.java)。数据库职责见[持久化说明](persistence-v1.md)。

## 2. 必须保留的边界

以下是从原产品方案保留、与现有实现相符的约束，不因归档旧计划而失效：

- 模型不能直接访问数据库、修改任务状态或自由调用预约写操作；`createAppointment` 不向模型注册为工具。
- “好的”“确认”等聊天内容不能代替具体方案确认。确认请求绑定 `taskId`、`planId`、`planVersion` 和幂等键；患者身份来自服务端解析。
- 方案过期或版本变化不能静默继续提交；号源不可用不能换成另一个号源后冒充原方案成功。
- 预约方案引用真实查询结果。当前 DeepSeek 适配器从本轮返回号源查找 `slotId`，医生、科室、日期和时段采用该记录，不能接受模型编造的号源元数据。
- Agent 和普通挂号共用预约写入口。幂等、占号和审计不能只放在聊天入口。
- 模型回复不是预约成功证据，Agent 在创建后查询核验；用户只能读取本人任务和预约。REST 和 SSE 从 Spring Security 会话取得患者身份，Runtime 用该身份检查任务归属。
- 资料检索不能替代号源查询、权限检查或强制预约规则。资料只作为回答依据，不能获得执行指令权限。
- 项目使用合成医院数据；导诊用于就诊方向参考，不提供诊断、处方或个体化用药。当前急症提示主要依赖模型提示词，尚无经医学审核的代码规则系统，不能声称临床安全能力已验证。
- 工具与状态轨迹面向追溯，不展示模型隐藏思考、密钥或未经处理的异常堆栈。

## 3. 模块职责与实际接口

### 3.1 AgentRuntime

[AgentRuntime](../../src/main/java/com/mediflow/agent/agent/runtime/AgentRuntime.java)提供消息处理、确认和查询任务快照入口；另有接收进度回调的消息重载。

```java
AgentTurnResult handleMessage(TaskId taskId, Principal principal, String message);
ConfirmationResult confirmPlan(TaskId taskId, Principal principal, PlanId planId,
                               int planVersion, String idempotencyKey);
TaskSnapshot getTask(TaskId taskId, Principal principal);
```

任务创建由 API 应用服务调用状态存储完成。Controller 不自行执行模型工具或直接写预约。

### 3.2 模型适配器

[DeepSeekDecisionModel](../../src/main/java/com/mediflow/agent/agent/model/DeepSeekDecisionModel.java)将上下文和工具规格交给 LangChain4j `ChatLanguageModel`。模型的 `tool_calls` 映射为只读调用，普通响应解析为受限 JSON 决策。模型适配器对协议错误最多进行一次纠正；提供方网络错误不走该纠正循环。

未配置对话 Key 时，[DemoDecisionModel](../../src/main/java/com/mediflow/agent/agent/model/DemoDecisionModel.java)提供确定性演示决策。这不代表整个 Spring Boot 应用在无数据库环境下已有完整可用的门户；当前业务 API 使用 `mysql` profile。

`DecisionModel` 主要决策为 `AskUser`、`CallReadTool`、`ProposePlan` 和 `Finish`。科室推荐可以作为结构化建议保存；未知工具、无法解析的响应和无依据方案不能直接产生预约写入。

### 3.3 只读工具

[ReadToolSpec](../../src/main/java/com/mediflow/agent/agent/tool/ReadToolSpec.java)当前仅保存名称与说明；工具 JSON 参数规格由 DeepSeek 适配器构建，参数校验由具体执行器负责，调用状态由 Runtime/TaskContext 约束。当前没有独立的“每工具允许状态注册表”。工具开始、结果和耗时由 Runtime 记录。

| 能力 | 当前代码状态 |
|---|---|
| `findAvailableSlots` | 已向 DeepSeek 声明；支持科室、医生和可选日期/时段，查询结果含替代条件提示 |
| `getAppointmentRules` | 已向 DeepSeek 声明；返回搜索期、确认要求和取消改约能力等技术限制。患者资料咨询走知识工具 |
| `searchHospitalKnowledge` | 已进入 DeepSeek 工具规格。直接产出只读资料回复及引用，区分无依据和服务故障，保留预约状态 |
| 科室/医生目录、我的预约 | 有门户接口；当前不是模型独立工具 |
| 预约创建与核验 | 后端确认流程内部调用，不是模型自由选择的工具 |

相关代码：[MySqlReadToolExecutor](../../src/main/java/com/mediflow/agent/agent/tool/MySqlReadToolExecutor.java)、[SlotQueryService](../../src/main/java/com/mediflow/agent/service/appointment/query/SlotQueryService.java)、[AgentRuntimeConfiguration](../../src/main/java/com/mediflow/agent/config/AgentRuntimeConfiguration.java)。

普通挂号调用严格查询；Agent 查询可以放宽日期后再放宽时段，保留原科室和医生条件，并返回 `exactMatch=false` 和说明。自动寻找其他医生并不是这条放宽逻辑已具备的能力。

### 3.4 预约业务服务

[AppointmentApplicationService](../../src/main/java/com/mediflow/agent/agent/booking/AppointmentApplicationService.java)的实际接口为：

```java
AppointmentExecutionResult createConfirmedAppointment(
        AppointmentPlan plan, Principal principal, String idempotencyKey);
AppointmentVerificationResult verify(String appointmentId, Principal principal);
```

`MySqlAppointmentApplicationService`将 Agent 参数转换为预约命令，委托[AppointmentWriteService](../../src/main/java/com/mediflow/agent/service/appointment/lifecycle/AppointmentWriteService.java)。后者也承接普通挂号，负责事务内方案校验、号源行锁/条件更新、幂等判定及审计。不能将所有 Runtime 状态检查点与预约写入描述成一个跨层总事务。

### 3.5 任务存储

[TaskStateStore](../../src/main/java/com/mediflow/agent/agent/persistence/TaskStateStore.java)当前接口是 `create(sessionId, patientId)`、`load(taskId)`、`save(context)`，没有单独的 `appendEvent` 方法。

MySQL 实现将任务、轮次、工具记录、方案和状态迁移作为检查点保存，使用 `state_version` 乐观锁；内存实现供行为测试使用。Redis 租约、限流与缓存尚未接入。

## 4. 执行限制与当前状态行为

- `mediflow.agent.max-steps` 默认 8，限制**单次消息处理的决策循环**。任务的 `stepCount` 累计计数，不是当前代码执行的整任务总上限。
- 模型决策每次最多等待 15 秒，只读工具每次最多等 5 秒。同一消息的模型和工具共享 45 秒预算，页面查号也使用只读工具限时。超时尝试取消并中断工作线程。短暂故障最多自动重试一次，DeepSeek SDK 的自动重试已关闭。预约写入单独由显式确认驱动。
- 消息接口检查非空；当前没有声明消息最大长度约束。
- `ASK_USER` 在未产生候选时进入 `NEED_MORE_INFO`。待选择或待确认阶段的只读回复可继续保留现状。
- 查号合并本轮提交的条件，保存真实候选后进入 `PROPOSAL_READY`。患者从该批候选选择号源，后端重检并生成确认单，进入 `AWAITING_CONFIRMATION`。实际修改条件会使旧方案失效。只读回复保留方案。
- `FINISH`、预约核验成功会进入 `COMPLETED`。旧终态继续保留。S4 从本人预约开启单独的 `VISIT_CONSULTATION`，走预约事实和资料工具的只读路径。
- 模型异常、暂时工具故障和非法工具参数有保留可重试状态的路径。预约冲突后保留需求，作废旧确认单与候选并回到 `SEARCHING`。提交结果不确定时保留 `EXECUTING`，已知原预约但尚未核验时保留 `VERIFYING`。核验入口按原方案查询并更新任务状态，显式重试仅适用于原结果暂未查到且仍可提交的原请求。见[S5 验收](../product/s5-booking-recovery.md)。
- `CANCELLED`存在于状态定义和允许迁移中，但当前没有面向患者的取消任务/取消预约接口，不能以枚举存在推断功能已完成。

共享条件、候选与确认的新协议见[S2 验收](../product/s2-appointment-options.md)。

## 5. SSE 与患者回复

实现入口是[AgentTaskController](../../src/main/java/com/mediflow/agent/controller/patient/guide/AgentTaskController.java)，没有单独实现名为 `AgentEventPublisher` 的组件。

| 路径 | 当前语义 |
|---|---|
| `POST /api/v1/tasks/{taskId}/messages/stream` | 先启动 SSE，再发送工具进度、已保存的回复文本及本轮结果 |
| `GET /api/v1/tasks/{taskId}/events` | 查询已持久化的状态与工具记录，逐条回放后关闭；不是持续订阅 |
| `GET /api/v1/tasks/{taskId}/trace` | 查询本人任务的轮次、动作、状态、尝试次数、耗时及简要原因 |

SSE 进度随任务执行发送，最终文本以单条 `MESSAGE_DELTA` 发送，与数据库保存的回复及 `RESULT.message` 一致。

前端流式 POST 由 `fetch` 读取，历史 GET 回放用 `EventSource`。断线重发沿用原消息标识，读取已保存的回复或提示处理中。已收到临时失败结果后，患者可以发起新一轮处理。多实例事件分发后置。

## 6. 架构取舍与验证边界

| 原方案 | 原理与优点 | 代价/适用性 |
|---|---|---|
| 固定流程 | 代码固定步骤，行为稳定、容易测试 | 适合普通挂号；对变化需求的自主处理有限 |
| 完全开放 Agent | 模型自由选择全部工具与动作 | 权限、写入确认和一致性难控制，未采用 |
| 当前受限 Agent | 模型选择只读行动，后端掌握状态与写入 | 需要协议和状态设计；符合当前预约约束，保留 |

[核心行为测试](../../src/test/java/com/mediflow/agent/agent/runtime/AgentRuntimeServiceTest.java)通过可控模型验证工具循环、确认、版本/期限、幂等与失败路径；MySQL 验证通过专用脚本运行。既往测试项数与真实模型体验属于历史验收，本轮没有重新验证，不能把文档更新当作功能通过证明。

患者登录、候选选择、结构化需求调整与首批资料来源已经本地验证。资料回复及来源按轮次持久化，历史资料正文不进入后续模型提示。见[S3 验收](../product/s3-knowledge-consultation.md)。S4 的关联咨询保持原预约任务终态，见[S4 验收](../product/s4-visit-preparation.md)。
