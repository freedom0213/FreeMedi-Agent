# 历史文档归档

> 归档日期：2026-11-17。这里保存已被取代的规划、已完成阶段的设计/验收，以及更新前快照。
> **归档中的“当前”“下一步”“必须”等语句只代表原记录时点，不是当前执行指令。** 当前入口见[文档首页](../README.md)。

## 已移入的历史文档

| 文件 | 原位置（相对 docs/） | 保留价值与停止活跃维护的原因 |
|---|---|---|
| [初始项目草案](product/project-status.md) | `product/project-status.md` | 最早需求讨论；尚未编码/技术未定等表述已经过时 |
| [项目计划 v2](product/project-plan-v2.md) | `product/project-plan-v2.md` | 早期收敛与确认边界；阶段排期已被后续路线取代，固定分诊规则等未成为当前实现 |
| [M2 实施方案](product/m2-appointment-service-plan.md) | `product/m2-appointment-service-plan.md` | 共用写入口、V6、普通挂号的设计理由；阶段已有历史交付，不能当新待办 |
| [M2 验收](product/m2-acceptance.md) | `product/m2-acceptance.md` | 2026-10-16 验证环境、65 项测试及接口/浏览器证据；保留原数字，不改成后续 122 项 |
| [导诊修复记录](product/patient-guide-acceptance.md) | `product/patient-guide-acceptance.md` | 2026-10-15 缺陷、修复及当时验证；旧“下一步”部分后来已完成 |
| [风格候选图](design/mediflow-style-options.svg) | `design/mediflow-style-options.svg` | 早期视觉备选，已选方向后不再作为现行设计规范 |

这些文件从原目录移入，未删除历史内容；Markdown 仅增加归档标识并调整相对链接。旧路径已不再作为文件入口。

## 活跃文档整理前快照

| 文件 | 快照来源 | 为什么保留 |
|---|---|---|
| [原交付路线快照](product/delivery-roadmap-before-cleanup-2026-11-17.md) | `product/delivery-roadmap-v1.md` 在本轮重整前的内容 | 保存旧范围、M1—M6 完成意图、工时估计与过程；当前范围待重新讨论，不把旧估算当承诺 |
| [M3 文档快照](product/m3-rag-plan-before-cleanup-2026-11-17.md) | `product/m3-rag-plan.md` 在本轮重整前的内容 | 保存 2026-10-16 选型过程及随后补记；其中包含后来纠正的环境/下载/默认值/协议估计 |

快照文件名表示**复制留档日期**，正文包含更早的历史记录和之前的核查补注。后续不继续在快照中维护状态。

## 活跃文档承接位置

- 旧计划中的模型/工具/确认/权限边界：由[Agent 架构](../architecture/agent-runtime-v1.md)与[数据模型](../database/data-model-v1.md)承接。
- M2 的实际写入/事务/普通挂号能力：由[持久化说明](../architecture/persistence-v1.md)及当前源码说明；原取舍留在历史方案中。
- M3 当前实现、配置和未完项：见[活跃 M3 文档](../product/m3-rag-plan.md)。
- 总体进度及范围复核：见[原路线与进度基线](../product/delivery-roadmap-v1.md)。

## 使用边界

历史验收不等于本轮复测；旧目标不等于当前已实现；曾被考虑的技术不等于已经采用。归档不修改业务代码、数据库迁移或开发数据。

本地 `.workbuddy/memory/` 继续由原工具管理，本轮没有移动；其中可能包含敏感环境信息，不纳入此归档。`knowledge/` 属于运行输入，也不归入历史说明文档。
