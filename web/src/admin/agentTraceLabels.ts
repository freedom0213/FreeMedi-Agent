export const actionNames: Record<string, string> = {
  TURN_STARTED: '开始处理请求', MODEL_DECISION: '模型判断', READ_TOOL_ATTEMPT: '只读工具调用',
  TURN_REPLY_SAVED: '保存回复', TURN_LIMIT: '任务步数已达上限', CANDIDATE_SELECTED: '选择号源',
  CONFIRMATION_CLAIMED: '接收预约确认', CONFIRMATION_REJECTED: '预约确认被拒绝',
  APPOINTMENT_WRITE: '创建预约', APPOINTMENT_VERIFY: '核验预约', APPOINTMENT_LOOKUP: '查询原预约',
}
export const outcomes: Record<string, string> = {
  SUCCEEDED: '成功', FAILED: '失败', STARTED: '已开始', PENDING: '待核验', UNKNOWN: '结果待确认',
}
const statuses: Record<string, string> = {
  UNDERSTANDING: '了解需求', NEED_MORE_INFO: '待补充需求', SEARCHING: '查询中', PROPOSAL_READY: '待选号',
  AWAITING_CONFIRMATION: '待确认', EXECUTING: '办理中', VERIFYING: '核验中',
  COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}
export const reasons: Record<string, string> = {
  MODEL_TRANSIENT: '模型服务暂时不可用', MODEL_TIMEOUT: '模型调用超时',
  MODEL_AUTH_REJECTED: '模型鉴权失败', MODEL_QUOTA_REJECTED: '模型额度或限流拒绝',
  MODEL_PROVIDER_REJECTED: '模型服务拒绝请求', MODEL_ERROR: '模型调用异常',
  MODEL_EMPTY_RESULT: '模型判断无结果', EMPTY_RESPONSE: '模型回复为空',
  INVALID_JSON: '模型输出格式错误', INVALID_DECISION: '模型判断结构无效',
  INVALID_TOOL_REQUEST: '模型工具指令无效', UNSOURCED_SLOT: '模型引用了无来源的号源',
  OPTIONAL_PREFERENCE_QUESTION: '模型重复询问可选条件', AGENT_DEADLINE: '本轮 Agent 请求超时',
  AGENT_BUSY: 'Agent 执行队列繁忙', AGENT_INTERRUPTED: 'Agent 请求中断',
  TOOL_TRANSIENT: '工具服务暂时不可用', TOOL_TIMEOUT: '工具调用超时',
  TOOL_DISABLED: '工具已停用',
  KNOWLEDGE_INDEX_UNAVAILABLE: '资料索引暂不可用', PLAN_EXPIRED: '确认单已过期',
  SLOT_UNAVAILABLE: '号源已不可用', OUTCOME_UNKNOWN: '提交结果待核验', VERIFY_PENDING: '预约核验未完成',
}
export const clock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
const taskNames: Record<string, string> = { APPOINTMENT_BOOKING: '智能导诊预约', VISIT_CONSULTATION: '就诊准备咨询' }
export const taskName = (type: string) => taskNames[type] ?? type
export const taskStatus = (status: string) => statuses[status] ?? status
import type { ObservedModel } from '../api/admin'


export function observedModelLabel(info: ObservedModel) {
  const mode = info.mode === 'demo' ? '本地演示规则' : info.mode === 'external' ? '外部模型' : '运行模式未识别'
  const version = info.version === 'environment' ? '启动配置' : info.version === 'built-in' ? '内置配置'
    : info.version === 'unrecorded' ? '版本未记录' : info.version.slice(info.version.lastIndexOf('/') + 1)
  return [info.label ?? mode, version, info.modelName].filter(Boolean).join(' · ')
}
