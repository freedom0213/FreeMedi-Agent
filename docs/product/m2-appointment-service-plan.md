# M2 完整预约服务实施方案

> 更新：2026-10-15。范围、阶段顺序和最终验收以[整体交付计划 v1](delivery-roadmap-v1.md)的 M2 为准，本文只展开 M2 内部设计。
>
> 状态：两处关键决策已由项目负责人确认（见第 2 节），编码按第 8 节分块进行，每块完成后独立提交。
>
> 进度：第 1 块已完成——V6 迁移与 `AppointmentWriteService` 已落地，Agent 确认流程已改为经它写入，现有全量自动化测试保持全部通过（含新增的普通挂号写入用例）。

## 1. 目标

M2 把 Agent 侧已经跑通的预约能力，开放成**不经过 AI 的普通业务入口**，让系统先成为"能办成事的医院服务系统"，再在上面叠 Agent：

- 科室与医生目录可浏览；
- 普通挂号：科室 → 医生 → 日期/时段 → 号源 → 确认下单；
- 我的预约：仅本人可见的记录与状态；
- 演示号源可续期（**已完成**，见 `scripts/seed-demo-slots.ps1`，提交 27496cd）。

完成标准（源自 roadmap §6 M2 行）：Agent 和普通挂号**复用同一预约服务**；**仅本人可查**；过期、竞争、重复确认都有明确结果。

## 2. 已确认的决策

| # | 决策点 | 结论 | 依据 |
|---|---|---|---|
| D1 | 普通挂号的预约写入路径 | **方案 A**：`appointment.plan_id` 改为可空，并新增 `booked_via` 标识来源 | `operation_audit_log.plan_id` 本来就是 `BIGINT NULL`，说明原始设计已预留非 Agent 写路径；方案 B 会让 Agent 表被非 AI 请求污染，破坏"业务正确性由 Java 保证、Agent 只是其中一个入口"的叙事 |
| D2 | 首页科室快捷入口 | **改跳 `/booking`** | 现状 `web/src/views/HomeView.vue:72` 四个科室按钮全部进 `/guide` 聊天页，与 `docs/design/frontend-layout-v1.md:22-23`「点击进入普通挂号路径」不符 |

## 3. 结构约束：为什么必须动 schema

现状外键链：

```text
appointment.plan_id  BIGINT NOT NULL
  └─ FK (plan_id, patient_id) → agent_plan(id, patient_id)
       └─ agent_plan.task_id NOT NULL → FK (task_id, patient_id) → agent_task(id, patient_id)
            └─ agent_task.session_id NOT NULL → agent_session(id)
```

即：**一次不经过 AI 的普通挂号，按现有结构要凭空创建 `agent_session` + `agent_task` + `agent_plan` 三张纯 Agent 表**，只为满足外键。这是 M2 唯一的结构性障碍。

## 4. 数据库变更：V6

```sql
-- Portal bookings have no agent plan. operation_audit_log.plan_id is already nullable,
-- so this aligns appointment with the existing design instead of inventing an agent task.
ALTER TABLE appointment MODIFY COLUMN plan_id BIGINT NULL;
ALTER TABLE appointment
    ADD COLUMN booked_via VARCHAR(24) NOT NULL DEFAULT 'AGENT' COMMENT 'AGENT / PORTAL' AFTER plan_id;
```

要点：

- `fk_appointment_plan_patient` 保持不变。MySQL 对含 NULL 的外键列不做校验，因此普通挂号写 `plan_id = NULL` 不违反约束。
- 既有数据由 `DEFAULT 'AGENT'` 回填，语义正确（它们确实都来自 Agent 确认流程）。
- `confirmed_version` 维持 `NOT NULL`；普通挂号写入 `0`，表示"没有方案版本"。
- 不加 `CHECK (booked_via IN (...))`：M4 可能新增来源（例如管理端代约），避免过早收紧。

## 5. 后端设计

### 5.1 抽出唯一写入口

现状 `MySqlAppointmentApplicationService.createConfirmedAppointment(AppointmentPlan, ...)` 与 `agent_plan` 强耦合：它先 `findPlanForUpdate` 校验方案的 `CONFIRMED` 状态和 `expires_at`，再占号。普通挂号没有方案，无法复用。

拆分方式：

| 组件 | 职责 | 归属 |
|---|---|---|
| `AppointmentWriteService`（新增） | 号源 `SELECT ... FOR UPDATE`、条件占用、幂等判定、写 `appointment`、写 `operation_audit_log` | 纯业务，**不含 Agent 概念** |
| `MySqlAppointmentApplicationService`（改造） | 方案状态/版本/过期校验 + 调用 `AppointmentWriteService` | Agent 适配器 |

关键约束：**`AppointmentWriteService` 是唯一的预约写入口**，任何代码路径都不得绕过它直接写 `appointment` 或占用号源。这一条同时是 M4 管理端的基础。

### 5.2 只读查询：严格版与放宽版必须分开

Agent 的 `findAvailableSlots` 带"精确条件无号时先放宽日期、再放宽时段"的兜底，并标记 `exactMatch=false`。**普通挂号不能复用这个行为**：用户选了 10 月 20 日上午，系统不能说"当天没号，给你换成 10 月 22 日下午"。

设计：

```text
SlotQueryService
├── searchStrict(criteria)        → 普通挂号；条件不满足就返回空
└── searchWithFallback(criteria)  → Agent 工具；沿用现有放宽策略
```

两者共用底层 SQL 构造与行映射，差异只在"是否放宽"，避免出现两套查询逻辑各自演化。

### 5.3 接口清单

| 方法 | 路径 | 用途 | 备注 |
|---|---|---|---|
| GET | `/api/v1/departments` | 启用中科室目录 | 公开只读 |
| GET | `/api/v1/departments/{id}/doctors` | 该科室启用中医生 | 公开只读 |
| GET | `/api/v1/slots` | 号源查询（严格） | 参数 `departmentId`/`doctorId`/`date`/`period` |
| POST | `/api/v1/appointments` | 普通挂号下单 | body：`slotId`、`idempotencyKey` |
| GET | `/api/v1/appointments/mine` | 我的预约 | 仅本人 |
| GET | `/api/v1/appointments/{appointmentNo}` | 预约详情 | 仅本人，非本人返回 404 |

沿用现有风格：`/api/v1` 前缀、`X-Demo-User-Id` 识别演示账号、业务冲突走 HTTP 200 + 业务码。

## 6. 幂等、并发与错误码

- 幂等键由前端生成（`crypto.randomUUID()`）并随请求提交；`UNIQUE(patient_id, idempotency_key)` 兜底，重复提交返回**已存在的那笔预约**而不是新建。
- 号源竞争：事务内 `SELECT ... FOR UPDATE` + 条件 `UPDATE ... WHERE status = 'AVAILABLE'`，检查影响行数必须为 1。
- 号源被抢时不静默替换，返回明确业务码，前端提示重新选择。

| 业务码 | 场景 | 前端行为 |
|---|---|---|
| `SLOT_UNAVAILABLE` | 号源已被占用 | 提示并回到号源列表重新查询 |
| `SLOT_NOT_FOUND` | 号源不存在或已关闭 | 提示并返回科室选择 |
| `IDEMPOTENCY_CONFLICT` | 幂等键已用于其他号源 | 提示刷新后重试 |
| `BOOKING_NOT_FOUND` | 查询他人或不存在的预约 | 按 404 处理，不泄露存在性 |

## 7. 身份与安全边界

M2 继续使用 `X-Demo-User-Id` 做**演示级隔离**，真实登录与角色保护属于 M4。因此：

- 代码里要真正校验归属（越权必须被拒绝），不能只靠前端隐藏；
- 文档与对外表述**不得声称已完成完整鉴权**。

## 8. 分块实施顺序

| 块 | 内容 | 完成标准 |
|---|---|---|
| 1 | V6 迁移 + 抽出 `AppointmentWriteService` | **零行为变更**：现有全量测试（含隔离 MySQL）必须仍然全绿 |
| 2 | `SlotQueryService` 严格版 + 目录/号源只读接口 | 严格查询不放宽；新增接口测试 |
| 3 | 普通挂号下单 + 我的预约接口 | 幂等、竞争、越权三类测试通过 |
| 4 | 前端：首页科室入口改跳、`/booking`、`/appointments` | 类型检查与生产构建通过 |
| 5 | 端到端手工验收 + 文档更新 | 见第 9 节 |

第 1 块风险最高（改动已通过测试的写路径），因此单独提交并单独验证。

## 9. 验收标准

对应 roadmap §8「Agent 与业务正确性」：

- Agent 确认流程与普通挂号走同一个 `AppointmentWriteService`；
- 用户无法查询或占用他人预约/任务；
- 两个请求竞争最后一个号时最多一个成功；
- 同一幂等键不会创建重复预约；
- 未确认时数据库中不出现预约记录；
- 号源被占时明确提示，不静默替换；
- 普通挂号查询严格：指定日期无号就返回空，不给替代时间；
- 后端全量测试（`scripts/test-mysql.ps1`，含 19 项隔离 MySQL 集成测试）全部执行通过；
- 前端 `vue-tsc` 类型检查与生产构建通过。

## 10. 明确不做

取消/改期预约、真实登录与角色保护（M4）、管理端排班编辑（M4）、支付与医保、医生的诊疗工作台。取消/改期在核心闭环稳定后按时间选择，不阻塞 M2。
