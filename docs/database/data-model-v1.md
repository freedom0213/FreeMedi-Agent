# FreeMedi-Agent 数据模型与当前存储边界

> 核对日期：2026-11-17；S1 写入边界与登录更新：2026-11-18。依据 Flyway V1—V8、任务持久化 Mapper、预约写服务与知识仓储的当前源码。
>
> 本文说明已有结构及实现限制，字段清单省略部分 VARCHAR 长度、默认值和索引名；完整定义以 `src/main/resources/db/migration/` 为准。`database/schema-v1.sql` 只是初始结构副本，完整安装需应用 Flyway V1—V8。
>
> S0/S1 已在隔离 MySQL 8.0.34 上复测 V1—V8 与预约写入边界，S1 收尾全部 129 项回归通过。V8 补合成账户数据。证据见[S1 验收](../product/s1-patient-session-acceptance.md)。

## 1. 设计目标与范围

数据库首先支撑一条预约 Agent 闭环：

```text
患者发起任务 → Agent 多轮收集信息 → 查询号源 → 生成方案
→ 用户确认 → 后端创建预约 → 核验 → 查询任务和预约结果
```

已有表覆盖：演示账号、科室、医生、排班、具体号源、预约、Agent 会话、任务、对话轮次、工具调用记录、预约方案、任务事件、业务审计，以及文档与知识片段。

当前没有取消/改期、真实登录、医生工作台、知识发布管理界面、多租户、跨医院、多 Agent 和运营数仓实现。表中为未来保留的状态或字段不证明这些能力已完成。

## 2. 数据所有权与存储职责

### MySQL：持久化事实来源

- 用户及角色；
- 医院目录数据：科室、医生、排班、号源；
- 预约及幂等结果；
- Agent 会话、任务、对话轮次、工具调用、方案和状态事件；
- 预约操作审计。
- 知识资料的版本、发布状态、片段原文和索引处理状态。

### Qdrant：已实现的可重建知识索引

向量与文档类型、版本、科室/医生等过滤字段放在 Qdrant；原文、发布状态和预约库存仍在 MySQL。检索候选回读 MySQL 后才作为资料返回，下架不依赖旧向量立即删除。索引可由已发布资料重建；当前默认本地中文 Embedding 不需要外部模型 API，但持久化索引仍需要可达的 Qdrant。

### Redis：原规划用途，当前未接入业务

- Agent 单任务短租约，避免同一任务并发推进；
- 热门只读目录与号源查询缓存，设置短 TTL 并主动失效；
- API 限流、短期验证码等后续需要时再使用；
- SSE 多实例转发仅在部署多个应用实例时考虑。

以上租约、缓存、限流和转发都是原规划，当前不能作为已实现能力。已有预约正确性由 MySQL 事务与唯一约束保证，任务推进使用 `state_version` 乐观锁；不能把 Redis 依赖存在解释为已具备 Redis 锁。

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

`agent_session` 是会话容器，`agent_task` 是有状态的预约任务；表结构允许一个会话关联多个任务，但当前创建任务 API 每次会先创建一个新会话，没有供患者在同一会话管理多个任务的完整入口。任务归属通过组合外键及服务端用户校验保护。

`knowledge_document` 与 `knowledge_chunk` 为一对多。相同 `doc_no` 的不同版本拥有不同文档行和主键。医生与科室可选关联到文档；其声明是否符合资料类型由解析器校验，是否仍为启用医生属于查询时目录校验，不由外键独自保证。

## 4. 表清单

| 表 | 目的 | 迁移来源 |
|---|---|---|
| `sys_user` | 患者账号、角色、状态与密码哈希 | V1 / V8 |
| `department` | 科室目录 | V1 |
| `doctor` | 医生业务资料 | V1 |
| `doctor_schedule` | 医生某日门诊计划 | V1 |
| `appointment_slot` | 可独立预约的具体时段 | V1 |
| `appointment` | 患者预约结果、来源与幂等 | V1、V6 |
| `agent_session` | 对话会话容器 | V1 |
| `agent_task` | 一次预约任务及当前状态 | V1、V2 |
| `agent_turn` | 用户输入与 Agent 可见回复 | V1 |
| `agent_tool_call` | 工具调用输入、结果和执行状态 | V1 |
| `agent_plan` | 候选方案及确认版本 | V1 |
| `agent_state_event` | 任务状态变更历史 | V1 |
| `operation_audit_log` | 预约写操作审计 | V1 |
| `knowledge_document` | 医院资料的版本化元信息 | V7 |
| `knowledge_chunk` | 可检索的资料片段与索引状态 | V7 |

15 张表均使用 `BIGINT AUTO_INCREMENT` 主键，业务编号如 taskNo/planNo/appointmentNo 另设唯一键。迁移已为核心关系建立真实外键，采用 `ON UPDATE RESTRICT ON DELETE RESTRICT`。表使用 InnoDB、`utf8mb4_0900_ai_ci`；时间列为 `DATETIME(3)`，应用连接配置要求数据库会话使用 UTC。门诊日期与时段按医院时区 Asia/Shanghai 解释。

## 5. 当前核心字段

以下是已存在字段的阅读摘要，不是新的 DDL。枚举文字主要是迁移注释与应用约定，当前没有通过数据库 `CHECK` 约束穷举状态值。是否存在实际生产数据、本机是否已执行全部迁移，本轮未查询。

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

患者登录复用 `sys_user`，任务与预约持有该表的患者 ID。V8 为原占位患者和新合成患者补充 BCrypt 密码哈希，认证仅接受启用的患者账号。演示数据只使用合成资料。

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
plan_id            BIGINT NULL            -- V6：普通挂号没有 Agent 方案
booked_via         VARCHAR(24) NOT NULL DEFAULT 'AGENT'  -- AGENT / PORTAL
status             VARCHAR NOT NULL      -- BOOKED / CANCELLED（当前只创建 BOOKED）
idempotency_key    VARCHAR NOT NULL
confirmed_version  INT NOT NULL          -- 普通挂号写 0；Agent 写实际方案版本
booked_at          DATETIME(3) NOT NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

约束和索引：

- `UNIQUE(patient_id, idempotency_key)`，防止同一患者重复请求；
- `(patient_id, created_at)`，查询我的预约；
- `(doctor_id, created_at)`，管理端按医生查询。

一个 slot 表示一个可预约名额。`appointment.slot_id` 没有唯一约束；当前防止重复占号依靠写事务锁定号源并检查状态，不是依靠预约表的 slot 唯一键。保留非唯一的原设计理由是为将来取消后重约保留历史，**取消与释放号源尚无实现**。数据库也没有“同一患者同一时间只能一笔预约”的独立约束，不能仅凭资料中的政策描述宣称该规则已强制执行。

V6 保留 `(plan_id, patient_id)` 组合外键；普通挂号写 `plan_id = NULL` 时 MySQL 不校验含空值的该组合键，仍会校验患者、科室、医生和号源等其他外键。`booked_via` 记录 `AGENT` 或 `PORTAL`，不以伪造 Agent 会话/方案满足普通挂号。

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
last_tool_result   JSON NULL             -- V2：任务恢复时保留最新工具观察
last_error_code    VARCHAR NULL
last_error_summary VARCHAR NULL
started_at         DATETIME(3) NOT NULL
finished_at        DATETIME(3) NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

索引：`(patient_id, updated_at)`、`(session_id, created_at)`、`(status, updated_at)`。

`collected_facts` 保存任务事实，当前 Runtime 主要写入分诊推荐及成功后的 `appointmentId`，不能将它描述成已完整维护科室/日期/医生偏好的结构化条件模型。完整可见对话存在 `agent_turn`，最新工具结果另存 `last_tool_result`。`last_error_code` / `last_error_summary` 已有列，但当前任务 Mapper 没有持续写入这些字段。

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

唯一约束：`(task_id, turn_no)`、`(task_id, client_request_id)`，另有 `(id, task_id)` 供工具调用组合外键引用。**消息入口尚未实现客户端 request id 幂等**：当前消息请求只有文本，`TaskContext.beginTurn` 每轮生成随机 UUID。数据库唯一键的存在不等于浏览器重试能避免再次调用模型或工具。

`model_name`、`prompt_version`、输入/输出 token 数及轮次耗时是已建但当前 Mapper 未填充的列；不能据此声称已有完整模型成本监控。

当前任务存储保存用户消息、用户可见回复、结构化工具参数和结果，不保存完整模型原始请求/响应、API Key、系统提示词或模型隐藏思考。用户输入与资料仍可能带有敏感内容，现有存储不能等同于已完成自动脱敏与保留期限管理。

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
plan_digest        CHAR(64) NOT NULL      -- ASCII 二进制校对，SHA-256
status             VARCHAR NOT NULL      -- PROPOSED / CONFIRMED / EXPIRED / REJECTED / INVALIDATED / EXECUTED
expires_at         DATETIME(3) NOT NULL
confirmed_at       DATETIME(3) NULL
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
```

唯一约束：`(task_id, version)`。活动方案查询索引：`(task_id, status, expires_at)`。

确认命令携带对外 `plan_no` 和版本。状态存储加载方案时校验快照的 SHA-256 摘要，并在同一版本再次保存时校验内容不可变；确认入口另外检查用户、任务状态、版本和过期。任务恢复当前读取最新版本的一个方案，不是一次返回多个可比较候选。

#### `agent_state_event`

```text
id                 BIGINT PK
task_id            BIGINT NOT NULL
event_no           BIGINT NOT NULL
from_status       VARCHAR NULL
to_status         VARCHAR NOT NULL
event_type        VARCHAR NOT NULL
reason_code       VARCHAR NULL
actor_type        VARCHAR NOT NULL      -- SYSTEM / USER / MODEL_DECISION
actor_user_id     BIGINT NULL            -- 外键 sys_user
correlation_id    VARCHAR NULL
metadata          JSON NULL
created_at        DATETIME(3) NOT NULL
```

唯一约束：`(task_id, event_no)`。当前 Mapper 主要写入 `TASK_CREATED` 和 `STATE_CHANGED`，`actor_type = SYSTEM`；原因、关联 ID、元信息等列并非每次都填充。事件记录不代替 `agent_task.status`；工具历史单独在 `agent_tool_call`，SSE 回放会组合这些记录。

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
request_digest     CHAR(64) NULL          -- ASCII 二进制校对
correlation_id     VARCHAR NULL
created_at         DATETIME(3) NOT NULL
```

审计日志记录预约业务操作的结果和主体；`agent_tool_call` 记录 Agent 工具执行，两者目的不同，不能互相替代。

当前服务包含三种结果的写入路径，规则如下。失败审计是尽力而为，不能保证每次异常都留下行：

| 情况 | `result_status` | `result_code` | `target_type` / `target_id` |
|---|---|---|---|
| 预约创建成功 | `SUCCEEDED` | `NULL` | `APPOINTMENT` / 新建预约 id |
| 业务规则拒绝请求 | `REJECTED` | 拒绝原因，如 `SLOT_UNAVAILABLE`、`IDEMPOTENCY_CONFLICT`、`PLAN_EXPIRED` | `APPOINTMENT_SLOT` / 被拒的号源 id（号源未知时 `APPOINTMENT` / `NULL`） |
| 发生意外异常 | `FAILED` | `INTERNAL_ERROR` | 同上 |

两条边界：

- **幂等重放不重复记账**。命中既有预约时返回原结果，不产生新的审计行——本次操作没有改变任何状态，重复记账会让"操作次数"无法统计。
- **成功和业务拒绝均在各自的预约写事务内记账**。业务拒绝是返回值而不是异常，对应事务正常提交；未预期异常导致写事务回滚时，失败记录使用独立的 `REQUIRES_NEW` 事务尽力写入，且记录失败不能覆盖原始错误。审计约束本身失败时可能只剩应用日志。`request_digest` / `correlation_id` 已有列，但当前写服务没有填充。

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
created_at         DATETIME(3) NOT NULL
updated_at         DATETIME(3) NOT NULL
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
created_at         DATETIME(3) NOT NULL
```

唯一约束 `(document_id, version, chunk_no)`。

当前导入与检索行为：

- **MySQL 是资料的事实来源，向量库只是可重建的索引。** 片段原文、版本和发布状态都在这里；索引整库丢失应当能从这里重建，不丢任何业务数据。
- **同一资料每个版本一行**。唯一键是 `(doc_no, version)`，不是 `doc_no` 单列唯一；变更导入在同一 MySQL 事务中归档旧版本、写入新文档与片段。当前受控导入会直接写 `PUBLISHED`，没有已经完成的“草稿→人工审核→发布”后台流程。
- **内容未变则跳过，且不因已归档而重新发布**。哈希同时包含元信息与正文；更改文件会新增版本，删除源文件不会自动归档数据库中原有资料。
- **只读回发布态且版本匹配的片段**。仓储按 `c.document_id = d.id AND c.version = d.version` 回读，检索层要求 `PUBLISHED`。因此即使旧索引仍有向量，下架后也不再输出其正文；索引删除和正文发布不是跨 MySQL/Qdrant 的一个事务。
- **旧向量不会在变更导入时自动清除**。当前导入器和索引器未调用 `removeVersion`；该端口与适配器存在不等于资料更新已接入清理。Qdrant 点 ID 使用数据库 `chunkId`，不是由文档/版本/片段号哈希生成；重复索引同一片段会覆盖同一 ID，整体重建清空集合后只处理当前已发布资料。
- **`heading` 是元信息，不掺进 `content`。** 片段正文保持为原文的忠实片段，引用要把它展示给患者；嵌入时使用哪些文本由索引层决定。
- **`embedding_status` 承载重试。** 索引选择已发布且非 `INDEXED` 的片段；失败记 `FAILED` 和截断到列宽的原因，成功后清除原因并写索引时间。整体重建会先清索引，再将当前发布片段重置为 `PENDING`。

资料类型对科室/医生 ID 的必填要求由 Markdown 解析器执行，外键只保证关联行存在，不保证医生启用或属于资料声明的科室。当前工作区的知识工具已开始补充实时目录校验，但模型工具声明及引用闭环尚未接完，不能将表结构或检索已有实现视作 M3 完成。

## 6. 事务和并发规则

### 6.1 任务检查点：独立事务

`MySqlTaskStateStore.save` 使用 `REQUIRES_NEW`，一次检查点在同一事务中更新任务、方案、最新轮次、待写工具记录和状态事件。任务更新包含 `state_version = 旧版本` 条件，影响行数不是 1 则抛乐观锁异常；只有提交成功后才确认内存版本。加载也在一个事务中读取任务、最新方案、轮次和当前轮工具计数。

### 6.2 Agent 确认：先保存执行权，再调用预约服务

Runtime 在业务写入前校验任务归属、等待确认状态、方案编号/版本及过期时间，然后将任务置为 `EXECUTING`、方案置为 `CONFIRMED` 并保存检查点。这个提交用于竞争处理保护，**不是与后面的预约写入共享一个大事务**。

### 6.3 唯一预约写入口：业务事务

普通挂号与 Agent 均调用 `AppointmentWriteService.book`，其中：

1. Agent 路径先锁定本人方案行，并按患者与幂等键查询既有预约；再校验 `CONFIRMED`、过期时间和方案所对应的号源。普通挂号路径直接按患者/幂等键查询，再读取所选号源。
2. 对目标号源 `SELECT ... FOR UPDATE`，核实 `AVAILABLE`，以 `UPDATE ... WHERE status = 'AVAILABLE'` 占用，并要求影响行数为 1。
3. 从加锁数据取得医生与科室，写入 `appointment`；Agent 路径同时把方案改为 `EXECUTED`；成功审计在本事务内写入。
4. 业务拒绝以返回值表达，事务正常提交拒绝审计；预约写入中的未预期异常回滚，失败审计另用独立事务尽力记录。

幂等唯一键为 `(patient_id, idempotency_key)`。Agent 路径检查既有预约是否属于同一方案，普通路径检查是否属于同一号源，目标不一致则拒绝；唯一键竞争会在新事务中查询既有结果。它保证写服务的重复请求识别，不等于整个 HTTP 确认入口在任意任务状态下都能重放成功。

S1 首批已在加锁查询中联查科室并要求排班 `OPEN`、医生/科室 `ACTIVE`、`s.start_at > UTC_TIMESTAMP(3)`，不再仅依赖只读查询过滤。库存 `AVAILABLE` 检查仍保留在占号步骤，保留原冲突码；幂等既有结果在有效性查询前返回，因此停用目录后重放原单不会重复创建或误报新失败。新增测试覆盖四种失效、方案后停诊及目录停用后的幂等重放；不据此宣称真实登录、完整恢复或全部预约规则已完成。

### 6.4 提交后核验：不会回滚已提交预约

预约服务提交成功后，Runtime 再保存 `VERIFYING` 与预约编号，然后在事务外查询本人预约是否为 `BOOKED`，成功则保存 `COMPLETED`。普通挂号直接使用已提交的业务事务结果，没有同样的独立核验阶段。

核验返回失败时当前任务会保存为 `FAILED`，预约写入不会因此回滚；尚未实现完整的待核验恢复入口、处理中断补偿或自动恢复。若进程在检查点和业务提交之间中断，也不能仅靠已有状态表推断系统会自动恢复。后续设计应避免将“核验失败”直接当作“预约未创建”，更不能盲目重复创建。

Redis 租约尚未实现；现有并发控制由任务检查点的乐观锁、预约事务的行锁/条件更新及唯一约束承担。

## 7. 时间、状态与 JSON 约定

- MySQL 时间字段统一写 UTC；前端显示按 `Asia/Shanghai` 转换；
- 预约日期使用 `DATE`，具体号源时间使用 `DATETIME(3)`，避免只保存“上午”而无法识别具体时段；
- 状态使用稳定的大写代码值，中文文案由应用层映射；
- JSON 仅用于任务事实、方案快照和结构化事件元数据，不用 JSON 取代高频过滤字段；
- 表结构使用 `utf8mb4`；
- 目录使用状态列表示启用/停用；当前没有完整管理端。正常业务入口保留预约、任务和审计历史，没有对患者开放物理删除功能；这不是数据库层禁止 DELETE 的约束。

## 8. 已知限制与后续讨论边界

1. **确认意图的来源**：前端确认按钮生成明确命令，不靠 LLM 判断自然语言“好”。后续如支持自然语言确认，也要解析成同一服务端确认命令。
2. **预约方案过期**：过期时将方案置为 `EXPIRED`，重新查号并生成新版本，不能复用旧版本。
3. **状态事件与当前状态一致性**：更新任务状态和插入 `agent_state_event` 必须在同一事务提交。
4. **审计和隐私**：演示数据保持合成；日志保留必要字段，避免存储不必要的医疗描述。
5. **数据外键策略已经落地**：核心关系使用真实外键；目录启用、患者角色、资料医生所属科室等仍需要应用校验，外键不覆盖这些语义。
6. **会话、任务与预约后咨询**：表结构允许会话拥有多个任务，但 API 和当前终态限制尚未形成预约后继续咨询闭环。首版已确认从预约详情进入关联预约的新只读咨询，保留原预约任务终态；具体关联、接口或结构变更须实施前评审，本轮不添加新表。
7. **保留列不等于能力完成**：真实登录、消息 request id 幂等、模型成本统计、Redis 租约、取消改期和完整知识发布管理均不能仅凭字段或依赖认定完成。
8. **知识政策与软件能力**：合成资料中的取消期限及可办理动作需与软件能力核对。文档整理不改变知识输入、资料发布状态或数据库内容。

## 9. 结构维护与验证依据

V1 建立 13 张业务/任务/审计表；V2 增加最新工具结果；V3—V5 为合成目录与排班数据；V6 支持普通挂号来源及可空方案关联；V7 建立知识文档和片段表。V4/V5 仅在迁移执行时按数据库会话当天生成未来号源，并不会每天自动续期。

结构变更应新增 Flyway 迁移并先评审影响，不能修改已执行迁移来掩盖差异。本轮没有结构变更。已有历史集成测试口径与隔离环境要求见[文档索引](../README.md)；当前数据库迁移状态和业务正确性仍需在后续独立复测中确认。
