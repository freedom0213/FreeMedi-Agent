# MediFlow Agent 数据库设计 v1

> 当前阶段：逻辑数据模型与关键约束评审。
>
> 本文不包含建库脚本，也不连接或修改任何数据库。首版采用 MySQL；Redis 只承载可重建的短期数据和协调能力，MySQL 是业务事实来源。

## 1. 设计目标与范围

数据库首先支撑一条预约 Agent 闭环：

```text
患者发起任务 → Agent 多轮收集信息 → 查询号源 → 生成方案
→ 用户确认 → 后端创建预约 → 核验 → 查询任务和预约结果
```

首版纳入：患者账号、科室、医生、排班、具体号源、预约、Agent 会话、任务、对话轮次、工具调用记录、预约方案、任务事件和业务审计。

首版不纳入：取消/改期、医生工作台、复杂知识库管理、多租户、跨医院、多 Agent 和运营数仓。

## 2. 数据所有权与存储职责

### MySQL：持久化事实来源

- 用户及角色；
- 医院目录数据：科室、医生、排班、号源；
- 预约及幂等结果；
- Agent 会话、任务、对话轮次、工具调用、方案和状态事件；
- 预约操作审计。

### Redis：短期协调和可重建数据

- Agent 单任务短租约，避免同一任务并发推进；
- 热门只读目录与号源查询缓存，设置短 TTL 并主动失效；
- API 限流、短期验证码等后续需要时再使用；
- SSE 多实例转发仅在部署多个应用实例时考虑。

Redis 不保存唯一的任务状态、预约结果或确认依据。Redis 不可用时，预约正确性仍由 MySQL 事务和唯一约束保证；可用性下降可以接受，重复预约不可接受。

## 3. 核心实体关系

```text
sys_user 1 ── N agent_session 1 ── N agent_task
                                  ├── N agent_turn
                                  ├── N agent_tool_call
                                  ├── N agent_plan
                                  └── N agent_state_event

department 1 ── N doctor 1 ── N doctor_schedule 1 ── N appointment_slot
                                                         │
sys_user 1 ── N appointment N ───────────────────────────┘
                     │
                     └── operation_audit_log
```

`agent_session` 表示患者的一段对话容器；`agent_task` 表示一次有明确目标和终态的预约任务。一个会话可包含多个任务，任务不可跨患者共享。

## 4. 表清单

| 表 | 目的 | 首版 |
|---|---|---|
| `sys_user` | 登录身份和角色 | 必须 |
| `department` | 科室目录 | 必须 |
| `doctor` | 医生业务资料 | 必须 |
| `doctor_schedule` | 医生某日门诊计划 | 必须 |
| `appointment_slot` | 可独立预约的具体时段 | 必须 |
| `appointment` | 患者预约结果与幂等 | 必须 |
| `agent_session` | 对话会话 | 必须 |
| `agent_task` | 一次预约任务及当前状态 | 必须 |
| `agent_turn` | 用户输入与 Agent 可见回复 | 必须 |
| `agent_tool_call` | 工具调用输入、结果和执行状态 | 必须 |
| `agent_plan` | 候选方案及确认版本 | 必须 |
| `agent_state_event` | 任务状态变更历史 | 必须 |
| `operation_audit_log` | 预约写操作审计 | 必须 |
| `knowledge_document` | 医院资料的版本化元信息 | M3 |
| `knowledge_chunk` | 可检索的资料片段与索引状态 | M3 |

表统一使用 `BIGINT` 主键（应用生成或数据库自增二选一，DDL 阶段统一），`created_at`、`updated_at` 使用 UTC 时间。API 层按 `Asia/Shanghai` 展示。所有外键关系至少由索引和业务校验保障；若采用数据库外键，需统一项目约定。

## 5. 核心字段草案

字段为逻辑设计，长度、精度和是否拆表待 DDL 阶段按技术栈确认。

### 5.1 用户和医院目录

#### `sys_user`

```text
id                 BIGINT PK
username           VARCHAR UNIQUE NOT NULL
password_hash      VARCHAR NOT NULL
display_name       VARCHAR NOT NULL
role               VARCHAR NOT NULL      -- PATIENT / ADMIN
status             VARCHAR NOT NULL      -- ACTIVE / DISABLED
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

首版患者资料可先与登录用户合并，避免为了患者档案过早增加身份模型。不要存储真实身份证号、病历和真实医疗数据；演示账号使用合成数据。

#### `department`

```text
id                 BIGINT PK
code               VARCHAR UNIQUE NOT NULL
name               VARCHAR NOT NULL
description        TEXT NULL
status             VARCHAR NOT NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

#### `doctor`

```text
id                 BIGINT PK
department_id      BIGINT NOT NULL
name               VARCHAR NOT NULL
title              VARCHAR NULL
specialty           VARCHAR NULL
introduction        TEXT NULL
status             VARCHAR NOT NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

索引：`(department_id, status)`。

### 5.2 排班、号源和预约

#### `doctor_schedule`

```text
id                 BIGINT PK
doctor_id          BIGINT NOT NULL
schedule_date      DATE NOT NULL
clinic_period      VARCHAR NOT NULL      -- MORNING / AFTERNOON / EVENING
status             VARCHAR NOT NULL      -- OPEN / CLOSED
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

唯一约束：`(doctor_id, schedule_date, clinic_period)`。

#### `appointment_slot`

```text
id                 BIGINT PK
schedule_id        BIGINT NOT NULL
slot_no            INT NOT NULL
start_at           DATETIME(3) NOT NULL
end_at             DATETIME(3) NOT NULL
status             VARCHAR NOT NULL      -- AVAILABLE / RESERVED / CLOSED
version            INT NOT NULL DEFAULT 0
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

唯一约束：`(schedule_id, slot_no)`。查询索引：`(schedule_id, status, start_at)`。

每个具体号源是一行，预约成功时使用条件更新或行锁原子占用。不能仅依赖 Agent 查询时看到的 `AVAILABLE` 状态。

#### `appointment`

```text
id                 BIGINT PK
appointment_no     VARCHAR UNIQUE NOT NULL
patient_id         BIGINT NOT NULL
department_id      BIGINT NOT NULL
doctor_id          BIGINT NOT NULL
slot_id            BIGINT NOT NULL
plan_id            BIGINT NOT NULL
status             VARCHAR NOT NULL      -- BOOKED / CANCELLED（首版只创建 BOOKED）
idempotency_key    VARCHAR NOT NULL
confirmed_version  VARCHAR NOT NULL
booked_at          DATETIME(3) NOT NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

约束和索引：

- `UNIQUE(patient_id, idempotency_key)`，防止同一患者重复请求；
- `(patient_id, created_at)`，查询我的预约；
- `(doctor_id, created_at)`，管理端按医生查询。

一个 slot 表示一个具体可预约名额。`appointment.slot_id` 不做唯一约束，以保留同一号源取消后重新预约的历史；并发保护由事务中对 `appointment_slot` 行加锁并校验状态实现。取消预约时在同一事务中释放号源。首版 UI 不开放取消，但数据约束不应提前堵死后续能力。

### 5.3 Agent 会话与任务

#### `agent_session`

```text
id                 BIGINT PK
patient_id         BIGINT NOT NULL
title              VARCHAR NULL
status             VARCHAR NOT NULL      -- ACTIVE / CLOSED
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

索引：`(patient_id, updated_at)`。

#### `agent_task`

```text
id                 BIGINT PK
task_no            VARCHAR UNIQUE NOT NULL
session_id         BIGINT NOT NULL
patient_id         BIGINT NOT NULL
task_type          VARCHAR NOT NULL      -- APPOINTMENT_BOOKING
status             VARCHAR NOT NULL
current_turn_no    INT NOT NULL DEFAULT 0
step_count         INT NOT NULL DEFAULT 0
state_version      BIGINT NOT NULL DEFAULT 0
collected_facts    JSON NOT NULL
last_error_code    VARCHAR NULL
last_error_summary VARCHAR NULL
started_at         DATETIME(3) NOT NULL
finished_at        DATETIME(3) NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

索引：`(patient_id, updated_at)`、`(session_id, created_at)`、`(status, updated_at)`。

`collected_facts` 只存本任务预约所需的结构化条件，例如科室、日期、时段和用户偏好；不把全部聊天历史反复塞入该 JSON。

#### `agent_turn`

```text
id                 BIGINT PK
task_id            BIGINT NOT NULL
turn_no            INT NOT NULL
client_request_id  VARCHAR NOT NULL
user_message       TEXT NOT NULL
assistant_message  TEXT NULL
status             VARCHAR NOT NULL      -- PROCESSING / WAITING_USER / COMPLETED / FAILED
model_name         VARCHAR NULL
prompt_version     VARCHAR NULL
input_tokens       INT NULL
output_tokens      INT NULL
duration_ms        BIGINT NULL
created_at         DATETIME(3) NOT NULL
completed_at       DATETIME(3) NULL
```

唯一约束：`(task_id, turn_no)`、`(task_id, client_request_id)`。客户端重试同一条消息时复用 request id，避免重复创建 Agent turn 和重复触发工具循环。

首版是否保存完整模型原始请求/响应需设置脱敏和保留策略。默认只保留展示所需消息与摘要，不记录 API Key、完整系统提示词或不必要的敏感信息。

#### `agent_tool_call`

```text
id                 BIGINT PK
task_id            BIGINT NOT NULL
turn_id            BIGINT NOT NULL
call_no            INT NOT NULL
tool_name          VARCHAR NOT NULL
arguments          JSON NOT NULL
result_summary     JSON NULL
status             VARCHAR NOT NULL      -- STARTED / SUCCEEDED / FAILED / REJECTED
error_code         VARCHAR NULL
duration_ms        BIGINT NULL
created_at         DATETIME(3) NOT NULL
completed_at       DATETIME(3) NULL
```

唯一约束：`(turn_id, call_no)`。日志参数中不得包含令牌、密码或不必要的患者资料。

#### `agent_plan`

```text
id                 BIGINT PK
plan_no            VARCHAR UNIQUE NOT NULL
task_id            BIGINT NOT NULL
patient_id         BIGINT NOT NULL
version            INT NOT NULL
department_id      BIGINT NOT NULL
doctor_id          BIGINT NOT NULL
slot_id            BIGINT NOT NULL
plan_snapshot      JSON NOT NULL
plan_digest        VARCHAR NOT NULL
status             VARCHAR NOT NULL      -- PROPOSED / CONFIRMED / EXPIRED / REJECTED / INVALIDATED / EXECUTED
expires_at         DATETIME(3) NOT NULL
confirmed_at       DATETIME(3) NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

唯一约束：`(task_id, version)`。活动方案查询索引：`(task_id, status, expires_at)`。

确认命令需要携带 plan id 和 version。`plan_digest` 用于检测方案内容是否被意外修改；实际信任仍来自数据库中的方案和服务端校验，不依赖客户端回传摘要。

#### `agent_state_event`

```text
id                 BIGINT PK
task_id            BIGINT NOT NULL
event_no           BIGINT NOT NULL
from_status       VARCHAR NULL
to_status         VARCHAR NOT NULL
event_type        VARCHAR NOT NULL
reason_code       VARCHAR NULL
actor_type        VARCHAR NOT NULL      -- SYSTEM / USER / MODEL
actor_id          BIGINT NULL
correlation_id    VARCHAR NULL
metadata          JSON NULL
created_at        DATETIME(3) NOT NULL
```

唯一约束：`(task_id, event_no)`。事件记录用于解释状态变化，不以事件表替代 `agent_task.status` 当前状态列。

### 5.4 业务审计

#### `operation_audit_log`

```text
id                 BIGINT PK
operation_no       VARCHAR UNIQUE NOT NULL
patient_id         BIGINT NOT NULL
operator_type      VARCHAR NOT NULL      -- PATIENT / ADMIN / SYSTEM
operator_id        BIGINT NULL
operation_type     VARCHAR NOT NULL      -- APPOINTMENT_CREATE
target_type        VARCHAR NOT NULL
target_id          BIGINT NULL
plan_id            BIGINT NULL
idempotency_key    VARCHAR NULL
result_status      VARCHAR NOT NULL      -- SUCCEEDED / REJECTED / FAILED
result_code        VARCHAR NULL
request_digest     VARCHAR NULL
correlation_id     VARCHAR NULL
created_at         DATETIME(3) NOT NULL
```

审计日志记录预约业务操作的结果和主体；`agent_tool_call` 记录 Agent 工具执行，两者目的不同，不能互相替代。

`result_status` 的三个值都会实际写入，规则如下：

| 情况 | `result_status` | `result_code` | `target_type` / `target_id` |
|---|---|---|---|
| 预约创建成功 | `SUCCEEDED` | `NULL` | `APPOINTMENT` / 新建预约 id |
| 业务规则拒绝请求 | `REJECTED` | 拒绝原因，如 `SLOT_UNAVAILABLE`、`IDEMPOTENCY_CONFLICT`、`PLAN_EXPIRED` | `APPOINTMENT_SLOT` / 被拒的号源 id（号源未知时 `APPOINTMENT` / `NULL`） |
| 发生意外异常 | `FAILED` | `INTERNAL_ERROR` | 同上 |

两条边界：

- **幂等重放不重复记账**。命中既有预约时返回原结果，不产生新的审计行——本次操作没有改变任何状态，重复记账会让"操作次数"无法统计。
- **被拒与成功的记账事务不同**。业务拒绝是返回值而不是异常，写它的那条事务会正常提交，因此拒绝记录和业务结果天然一致；只有异常路径上的事务必然回滚，需要单独的事务尽力记录，且失败时不得覆盖原始错误（此时应用日志是唯一记录）。

### 5.5 医院资料

#### `knowledge_document`

```text
id                 BIGINT PK
doc_no             VARCHAR NOT NULL      -- 资料编号，同一编号可以有多个版本
title              VARCHAR NOT NULL
doc_type           VARCHAR NOT NULL      -- APPOINTMENT_RULE / VISIT_NOTICE / DEPARTMENT_INTRO / DOCTOR_PROFILE / PREPARATION
department_id      BIGINT NULL           -- 科室资料必填，外键 department
doctor_id          BIGINT NULL           -- 医生资料必填，外键 doctor
source             VARCHAR NOT NULL      -- 资料来源说明
version            INT NOT NULL
status             VARCHAR NOT NULL      -- DRAFT / PUBLISHED / ARCHIVED
content_hash       CHAR(64) NOT NULL     -- 覆盖元信息与正文，用于判断是否需要升版本
published_at       DATETIME(3) NULL
```

唯一约束 `(doc_no, version)`。

#### `knowledge_chunk`

```text
id                 BIGINT PK
document_id        BIGINT NOT NULL       -- 外键 knowledge_document
version            INT NOT NULL
chunk_no           INT NOT NULL          -- 从 1 开始
heading            VARCHAR NULL          -- 所属标题路径，如「预约规则 > 取消与改约」
content            TEXT NOT NULL         -- 原文片段，患者看到的引用正文
char_count         INT NOT NULL
embedding_status   VARCHAR NOT NULL      -- PENDING / INDEXED / FAILED
index_error        VARCHAR NULL
indexed_at         DATETIME(3) NULL
```

唯一约束 `(document_id, version, chunk_no)`。

四条设计约束：

- **MySQL 是资料的事实来源，向量库只是可重建的索引。** 片段原文、版本和发布状态都在这里；索引整库丢失应当能从这里重建，不丢任何业务数据。
- **版本进唯一键**，因为更新或下架后不能继续返回旧版本：检索按 `documentId + version + status = PUBLISHED` 过滤，重新索引时删除被取代的向量点。
- **`heading` 是元信息，不掺进 `content`。** 片段正文保持为原文的忠实片段，引用要把它展示给患者；嵌入时使用哪些文本由索引层决定。
- **`embedding_status` 承载重试。** 单个片段索引失败可以重试，不需要重建已经成功的片段。

## 6. 事务和并发规则

预约确认必须在一个数据库事务内完成以下动作：

1. 读取幂等键对应的既有结果；如果已有预约则返回原结果；
2. 校验任务归属、任务状态、方案状态、方案版本和过期时间；
3. 对号源行执行 `SELECT ... FOR UPDATE`，确认仍为 `AVAILABLE`，再更新为 `RESERVED`；
4. 插入预约记录；
5. 将方案标记为 `EXECUTED`，任务进入 `VERIFYING`；
6. 写入业务审计记录。

任一动作失败则回滚。若采用条件更新而非显式行锁，必须检查受影响行数恰好为 1；为 0 表示号源已不可用，需要重新搜索并请求用户确认。所有预约写操作必须经过同一个应用服务，不允许其他代码路径绕过号源锁和状态检查。

上面的顺序描述的是成功路径。业务规则拒绝（号源被占、幂等键挪用、方案过期等）以返回值而非异常表达，事务因此正常提交，拒绝记录就在同一事务内写入；步骤 1—5 没有产生任何变更，所以提交一条审计行不会留下半成品数据。只有未预期的异常会让事务回滚，那条路径上的失败记录必须使用独立事务，并且不得因为记录失败而替换原始异常。

执行后核验可在事务提交后查询预约记录。若核验服务短暂失败，不要回滚已提交预约并谎称失败；任务应进入可恢复的核验失败/待处理状态，重试核验时使用预约 id，不再重复创建。

同一任务并发处理可先使用 Redis 短租约降低冲突，同时使用 MySQL 的 `state_version` 乐观锁或事务行锁作为最终保护。Redis 锁丢失不能导致业务数据错误。

## 7. 时间、状态与 JSON 约定

- MySQL 时间字段统一写 UTC；前端显示按 `Asia/Shanghai` 转换；
- 预约日期使用 `DATE`，具体号源时间使用 `DATETIME(3)`，避免只保存“上午”而无法识别具体时段；
- 状态使用稳定的大写代码值，中文文案由应用层映射；
- JSON 仅用于任务事实、方案快照和结构化事件元数据，不用 JSON 取代高频过滤字段；
- 表结构使用 `utf8mb4`；
- 软删除只用于需要恢复的目录数据，预约、任务和审计记录不物理删除。

## 8. 风险与待定事项

1. **确认意图的来源**：前端确认按钮生成明确命令，不靠 LLM 判断自然语言“好”。后续如支持自然语言确认，也要解析成同一服务端确认命令。
2. **预约方案过期**：过期时将方案置为 `EXPIRED`，重新查号并生成新版本，不能复用旧版本。
3. **状态事件与当前状态一致性**：更新任务状态和插入 `agent_state_event` 必须在同一事务提交。
4. **审计和隐私**：演示数据保持合成；日志保留必要字段，避免存储不必要的医疗描述。
5. **数据外键策略**：可选真实外键或仅应用层约束。个人项目推荐对核心关系使用外键，避免孤儿预约和孤儿 Agent 记录。
6. **患者档案拆分**：首版和登录用户合并；若以后加入未注册患者或多个就诊人，再增加 `patient_profile` 并明确预约主体模型。

## 9. 下一步

数据库逻辑设计完成后，建议先审阅确认实体关系、唯一约束、事务边界和 Redis 职责，再生成 MySQL DDL 与迁移脚本。生成 DDL 前仍不需要连接真实医院系统，也不需要先实现前端。
