<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, CircleCheck, CircleHelp, HeartPulse, LoaderCircle, RefreshCw, Send, ShieldCheck, Sparkles, Stethoscope } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import AppShell from '../components/AppShell.vue'
import { errorMessage } from '../api/http'
import { useAgentStore } from '../stores/agent'
import type { TaskStatus } from '../types/agent'

const route = useRoute()
const router = useRouter()
const agent = useAgentStore()
const draft = ref('')
const optimisticMessage = ref('')
const conversation = ref<HTMLElement | null>(null)
const currentDate = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())

const turns = computed(() => agent.task?.turns ?? [])
const latestTurn = computed(() => turns.value.at(-1))
const currentPlanId = computed(() => {
  const id = agent.plan?.id
  return typeof id === 'string' ? id : id?.value
})
const taskStatus = computed(() => agent.task?.status)
const plan = computed(() => agent.plan?.status === 'INVALIDATED' ? undefined : agent.plan)
const slot = computed(() => agent.recommendedSlot)
const recommendation = computed(() => {
  const value = agent.task?.facts?.triageRecommendation
  if (!value || typeof value !== 'object') return undefined
  const candidate = value as { departmentName?: unknown; reason?: unknown; departmentId?: unknown }
  if (typeof candidate.departmentName !== 'string' || typeof candidate.reason !== 'string') return undefined
  return candidate
})
const confirming = ref(false)
const factLabels: Record<string, string> = { departmentName: '科室', doctorName: '医生', appointmentDate: '日期', clinicPeriod: '时段' }
const summaryFacts = computed(() => Object.entries(agent.task?.facts ?? {})
  .filter(([key]) => key in factLabels)
  .map(([key, value]) => [factLabels[key], key === 'appointmentDate' ? formatDate(String(value))
    : ({ MORNING: '上午', AFTERNOON: '下午', EVENING: '晚上' } as Record<string, string>)[String(value)] ?? String(value)]))
const canReply = computed(() => !agent.busy && (!taskStatus.value || ['UNDERSTANDING', 'SEARCHING', 'NEED_MORE_INFO', 'AWAITING_CONFIRMATION'].includes(taskStatus.value)))
const canConfirm = computed(() => !agent.busy && taskStatus.value === 'AWAITING_CONFIRMATION' && !!plan.value)
const isComplete = computed(() => taskStatus.value === 'COMPLETED' && !!agent.task?.facts.appointmentId)
const isFailed = computed(() => taskStatus.value === 'FAILED')

const steps = computed(() => {
  const status = confirming.value && taskStatus.value === 'AWAITING_CONFIRMATION'
    ? 'EXECUTING' as TaskStatus
    : taskStatus.value
  const stage = status === 'NEED_MORE_INFO' || status === 'UNDERSTANDING' ? 0
    : status === 'SEARCHING' ? 1
      : status === 'PROPOSAL_READY' || status === 'AWAITING_CONFIRMATION' ? 2
        : status === 'EXECUTING' || status === 'VERIFYING' ? 3
          : status === 'COMPLETED' ? 4 : status === 'FAILED' ? -1 : -1
  return [
    { title: '了解就诊需求', detail: '了解不适与时间偏好', active: stage === 0, done: stage > 0 },
    { title: '查找可用号源', detail: '为你查找可预约的时间', active: stage === 1, done: stage > 1 },
    { title: '核对预约方案', detail: '请核对医生、日期和时间', active: stage === 2, done: stage > 2 },
    { title: '确认并提交预约', detail: '点击确认后才会提交', active: stage === 3, done: stage > 3 },
    { title: '核实预约结果', detail: '确认预约已成功登记', active: stage === 4, done: isComplete.value },
  ].map((step, index) => ({
    ...step,
    active: stage === index && status !== 'COMPLETED',
    done: isComplete.value || (status !== 'COMPLETED' && stage > index),
  }))
})

const visibleEvents = computed(() => agent.events.filter(event => [
  'READ_TOOL_STARTED', 'READ_TOOL_SUCCEEDED', 'READ_TOOL_FAILED', 'PLAN_PROPOSED',
  'CONFIRMATION_REQUIRED', 'EXECUTION_STARTED', 'EXECUTION_VERIFIED', 'MODEL_RETRY_REQUIRED', 'TASK_FAILED',
].includes(event.type) && (!['PLAN_PROPOSED', 'CONFIRMATION_REQUIRED'].includes(event.type)
  || event.summary === currentPlanId.value)).slice(-8).reverse())

onMounted(async () => {
  await agent.restore()
  const intent = typeof route.query.intent === 'string' ? route.query.intent : ''
  if (intent || route.query.new === '1') {
    agent.startNewTask()
    await router.replace({ name: 'guide' })
    if (intent) await submit(intent)
  }
})

onUnmounted(() => agent.stopWatching())

watch(turns, async () => {
  await nextTick()
  if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
}, { deep: true })

async function submit(value = draft.value) {
  const message = value.trim()
  if (!message || !canReply.value) return
  draft.value = ''
  optimisticMessage.value = message
  try {
    await agent.submitMessage(message)
    optimisticMessage.value = ''
    await nextTick()
    if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
  } catch (error) {
    optimisticMessage.value = ''
    const messageText = errorMessage(error)
    agent.error = messageText
    ElMessage.error(messageText)
  }
}

function startTriage() {
  if (!turns.value.length || !canReply.value) return
  submit('我现在比较着急，请根据目前描述的情况先给我一个就诊方向建议和原因。')
}

async function confirmAppointment() {
  if (!canConfirm.value) return
  confirming.value = true
  try {
    const result = await agent.confirmPlan()
    if (result.success) ElMessage.success('预约已创建并完成核验')
    else ElMessage.warning(result.message)
  } catch (error) {
    const messageText = errorMessage(error)
    agent.error = messageText
    ElMessage.error(messageText)
  } finally {
    confirming.value = false
  }
}

function startNewTask() {
  agent.startNewTask()
  draft.value = ''
  conversation.value?.scrollTo({ top: 0 })
}

function statusText(status: TaskStatus | undefined) {
  if (!status) return '尚未开始'
  return ({
    UNDERSTANDING: '正在理解需求', SEARCHING: '正在查询号源', NEED_MORE_INFO: '需要补充信息',
    PROPOSAL_READY: '方案已生成', AWAITING_CONFIRMATION: '等待你的确认', EXECUTING: '正在创建预约',
    VERIFYING: '正在核验结果', COMPLETED: isComplete.value ? '预约已完成' : '本次咨询已结束', FAILED: '处理未完成', CANCELLED: '已取消',
  } as Record<TaskStatus, string>)[status]
}

function eventLabel(type: string, summary = '') {
  if (summary === 'getAppointmentRules' && type.startsWith('READ_TOOL')) {
    return type === 'READ_TOOL_STARTED' ? '正在了解预约规则' : type === 'READ_TOOL_SUCCEEDED' ? '预约规则已核对' : '暂时无法查询预约规则'
  }
  if (type === 'STATE_CHANGED') return statusText(summary as TaskStatus)
  return ({
    TASK_CREATED: '预约任务已创建', TASK_STARTED: '开始处理', UNDERSTANDING: '理解需求', SEARCHING: '查询号源',
    NEED_MORE_INFO: '等待补充信息', PROPOSAL_READY: '生成预约方案', AWAITING_CONFIRMATION: '等待确认',
    QUESTION_ASKED: '等待补充需求', CONFIRMATION_REQUIRED: '已请你核对方案', CONFIRMATION_REJECTED: '确认未通过',
    STATE_CHANGED: '任务状态更新',
    PLAN_PROPOSED: '预约方案已生成', EXECUTION_STARTED: '开始创建预约',
    EXECUTION_VERIFIED: '预约核验完成', TASK_COMPLETED: '处理完成', TASK_FAILED: '处理失败',
    EXECUTING: '创建预约', VERIFYING: '核验结果', COMPLETED: '处理完成', FAILED: '处理失败',
    READ_TOOL_STARTED: '正在查询可用号源', READ_TOOL_SUCCEEDED: '号源查询完成', READ_TOOL_FAILED: '号源暂时无法查询',
    MODEL_RETRY_REQUIRED: '需要重新尝试',
  } as Record<string, string>)[type] ?? '服务进度更新'
}

function eventSummary(type: string, summary: string) {
  if (summary === 'getAppointmentRules' && type.startsWith('READ_TOOL')) return '确认预约前，助手会核对预约规则'
  if (type === 'STATE_CHANGED') return '任务进度已保存'
  if (type === 'TASK_CREATED') return statusText(summary as TaskStatus)
  if (type === 'PLAN_PROPOSED') return '已为你找到一个可预约的时间'
  if (type === 'CONFIRMATION_REQUIRED') return '请在左侧核对方案并点击确认预约'
  if (type === 'EXECUTION_STARTED') return '已收到你的确认，正在提交预约'
  if (type === 'EXECUTION_VERIFIED') return '预约核验成功'
  if (type === 'TASK_STARTED') return '已收到您的预约需求'
  if (type === 'QUESTION_ASKED') return '等待您补充就诊情况'
  if (type === 'READ_TOOL_STARTED') return '正在查找符合条件的号源'
  if (type === 'READ_TOOL_SUCCEEDED') return '已获得可用号源信息'
  if (type === 'READ_TOOL_FAILED') return '您可以稍后重新查询'
  if (type === 'MODEL_RETRY_REQUIRED') return '预约尚未提交，您可以重试'
  if (type === 'TASK_COMPLETED') return '预约流程已完成'
  if (type === 'TASK_FAILED') return '预约流程未完成'
  return summary
}

function formatDate(date: string | undefined) {
  if (!date) return '以预约方案为准'
  const value = new Date(`${date}T00:00:00`)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(value)
}
</script>

<template>
  <AppShell>
    <div class="guide-page page-wrap">
      <div class="guide-page-heading">
        <button class="back-link" type="button" @click="router.push('/')"><ArrowLeft :size="16" />返回就诊服务</button>
        <div class="guide-heading-row">
          <div>
            <div class="eyebrow"><span class="eyebrow-line" />智能导诊与预约</div>
            <h1>就诊小助手</h1>
          </div>
          <div class="guide-actions">
            <button class="button-primary triage-button" type="button" :disabled="!turns.length || !canReply" @click="startTriage">
              <Sparkles :size="15" />发起智能分诊
            </button>
            <button class="button-secondary new-task-button" type="button" @click="startNewTask"><RefreshCw :size="15" />新建预约需求</button>
          </div>
        </div>
      </div>

      <div class="agent-workspace">
        <section class="conversation-panel" aria-label="预约需求对话">
          <div class="request-summary">
            <div class="summary-label"><Stethoscope :size="15" />本次预约需求</div>
            <div class="summary-value">
              <template v-if="summaryFacts.length">
                <span v-for="([key, value]) in summaryFacts" :key="key" class="fact-value">{{ key }}：{{ value }}</span>
              </template>
              <span v-else>{{ turns.length ? turns[0]?.userMessage : '提交需求后，这里会汇总已收集的信息。' }}</span>
            </div>
            <span v-if="taskStatus" class="status-chip" :class="{ done: isComplete, failed: isFailed }">{{ statusText(taskStatus) }}</span>
          </div>

          <div ref="conversation" class="conversation-body">
            <div v-if="!turns.length" class="conversation-empty">
              <div class="empty-symbol"><Sparkles :size="20" /></div>
              <p class="empty-title">先说说哪里不舒服</p>
              <p class="empty-description">也可以告诉我症状持续了多久，或直接说想看哪个科室、什么时候方便。</p>
              <div class="prompt-suggestions">
                <button type="button" @click="submit('我最近老是咳嗽，已经一周了')">我最近老是咳嗽 <ArrowRight :size="14" /></button>
                <button type="button" @click="submit('帮我找最近有号的时间')">帮我找最近有号的时间 <ArrowRight :size="14" /></button>
              </div>
            </div>

            <div v-for="turn in turns" :key="turn.clientRequestId" class="turn-block">
              <div class="message-row user-row">
                <span class="message-role">你 · {{ currentDate }}</span>
                <div class="user-message">{{ turn.userMessage }}</div>
              </div>
              <div v-if="turn.assistantMessage" class="message-row assistant-row">
                <div class="assistant-mark"><HeartPulse :size="16" /></div>
                <div class="assistant-content">
                  <span class="message-role assistant-role">导诊助手</span>
                  <p>{{ turn.assistantMessage }}</p>
                </div>
              </div>
            </div>

            <div v-if="optimisticMessage" class="turn-block optimistic-turn" aria-live="polite">
              <div class="message-row user-row">
                <span class="message-role">你 · {{ currentDate }}</span>
                <div class="user-message">{{ optimisticMessage }}</div>
              </div>
              <div v-if="agent.streamingReply" class="message-row assistant-row streaming-row">
                <div class="assistant-mark"><HeartPulse :size="16" /></div>
                <div class="assistant-content"><span class="message-role assistant-role">导诊助手 · 正在回复</span><p>{{ agent.streamingReply }}<span class="stream-caret" /></p></div>
              </div>
              <div v-else class="working-row" role="status"><LoaderCircle :size="16" class="spin" />正在理解你的情况并整理就诊建议…</div>
            </div>

            <div v-if="agent.busy && !optimisticMessage" class="working-row" role="status"><LoaderCircle :size="16" class="spin" />正在处理预约需求…</div>
            <div v-if="agent.error" class="inline-error" role="alert"><CircleHelp :size="16" />{{ agent.error }}</div>

            <section v-if="recommendation" class="triage-card" aria-labelledby="triage-heading">
              <div class="triage-card-heading"><div><span class="eyebrow">初步就诊方向</span><h2 id="triage-heading">{{ recommendation.departmentName }}</h2></div><span class="triage-badge">可调整</span></div>
              <p>{{ recommendation.reason }}</p>
              <small>这是基于当前描述的就诊方向建议，不是医学诊断；补充信息后可能调整。</small>
            </section>

            <section v-if="plan && slot" class="proposal-section" aria-labelledby="proposal-heading">
              <div class="proposal-heading">
                <div><h2 id="proposal-heading">为你推荐的就诊方案</h2><span>数据来自模拟医院号源</span></div>
                <span class="proposal-count">1 个方案</span>
              </div>
              <div class="proposal-row" :class="{ selected: true }">
                <span class="proposal-check"><Check :size="14" /></span>
                <div class="proposal-details">
                  <strong>{{ slot.departmentName }} · {{ slot.doctorName }}</strong>
                  <span>{{ slot.doctorTitle }} · {{ formatDate(plan.draft.appointmentDate) }}</span>
                  <span>{{ slot.timeRange }} · {{ slot.clinicPeriod === 'AFTERNOON' ? '下午门诊' : slot.clinicPeriod === 'MORNING' ? '上午门诊' : slot.clinicPeriod === 'EVENING' ? '晚间门诊' : '门诊' }}</span>
                </div>
                <span class="recommend-label">推荐</span>
              </div>
              <div class="confirm-summary">
                <div class="confirm-summary-copy">
                  <strong>确认前请核对</strong>
                  <span>{{ slot.departmentName }} · {{ slot.doctorName }} · {{ formatDate(plan.draft.appointmentDate) }} {{ slot.timeRange }}</span>
                </div>
                <button class="button-primary confirm-button" type="button" :disabled="!canConfirm" @click="confirmAppointment">
                  <LoaderCircle v-if="agent.busy" :size="15" class="spin" />
                  <span v-else>确认预约</span>
                </button>
              </div>
            </section>

            <div v-if="isComplete" class="completion-state" role="status">
              <CircleCheck :size="19" />
              <div><strong>预约已创建并核验</strong><span>预约状态已由后端确认</span></div>
            </div>
            <div v-if="isFailed" class="failure-state" role="alert">
              <CircleHelp :size="18" />本次预约未完成。你可以新建需求并重新查询号源。
            </div>
          </div>

          <form class="composer" @submit.prevent="submit()">
              <textarea
              v-model="draft"
              :disabled="!canReply"
              :placeholder="canReply ? (taskStatus === 'AWAITING_CONFIRMATION' ? '如果方案不合适，可以继续说想调整的日期或时间…' : '说说哪里不舒服，或补充日期、时间偏好…') : '本次处理已结束，可新建预约需求'
              "
              rows="1"
              aria-label="补充预约需求"
              @keydown.enter.exact.prevent="submit()"
            />
            <button class="send-button" type="submit" :disabled="!draft.trim() || !canReply" aria-label="发送消息">
              <Send :size="16" />
            </button>
            <span class="composer-note">提供就诊方向建议与预约协助；提交前需要你确认</span>
          </form>
        </section>

        <aside class="progress-panel" aria-label="预约处理进度">
          <div class="progress-header">
            <div><h2>处理进度</h2><p>本次预约任务</p></div>
            <span class="progress-live" :class="{ finished: isComplete, failed: isFailed }"><span />{{ confirming ? statusText('EXECUTING') : statusText(taskStatus) }}</span>
          </div>

          <ol class="progress-steps">
            <li v-for="step in steps" :key="step.title" :class="{ active: step.active, done: step.done, failed: isFailed && step.active }">
              <span class="step-indicator"><Check v-if="step.done" :size="12" /><span v-else /></span>
              <div><strong>{{ step.title }}</strong><span>{{ step.detail }}</span></div>
            </li>
          </ol>

          <div class="progress-separator" />
          <div class="event-heading"><h3>本次服务记录</h3><span v-if="agent.task">{{ turns.length }} 轮对话</span></div>
          <div v-if="latestTurn" class="service-recap">
            <div><strong>你 · 最近一次补充</strong><p>{{ latestTurn.userMessage }}</p></div>
            <div v-if="latestTurn.assistantMessage"><strong>导诊助手 · 最近一次回复</strong><p>{{ latestTurn.assistantMessage }}</p></div>
          </div>
          <div v-if="visibleEvents.length" class="event-list">
              <div v-for="(event, index) in visibleEvents" :key="`${event.type}-${event.summary}-${index}`" class="event-item">
              <span class="event-dot" :class="event.type.toLowerCase().includes('failed') ? 'error' : event.type.includes('SUCCEEDED') || event.type === 'COMPLETED' ? 'success' : ''" />
                <div><strong>系统 · {{ eventLabel(event.type, event.summary) }}</strong><span>{{ eventSummary(event.type, event.summary) }}</span></div>
            </div>
          </div>
          <div v-else class="event-empty">{{ turns.length ? '待了解就诊需求后，会在这里记录查号和预约进度。' : '先告诉助手哪里不舒服，就可以开始导诊。' }}</div>

          <div class="privacy-note"><ShieldCheck :size="15" /><span>这里显示系统处理进度；你和助手的对话在左侧。</span></div>
        </aside>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.service-recap { display: grid; gap: 12px; margin: 16px 0 20px; font-size: 12px; }
.service-recap strong { color: var(--text-primary, #213c3b); font-weight: 500; }
.service-recap p { margin: 5px 0 0; color: var(--text-muted, #617b7a); line-height: 1.7; overflow-wrap: anywhere;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.streaming-row { margin-top: 10px; }
.stream-caret { display: inline-block; width: 2px; height: 1em; margin-left: 3px; vertical-align: -2px; background: currentColor; animation: blink 1s steps(2, start) infinite; }
.triage-card { margin: 18px 0; padding: 18px 20px; border: 1px solid #c7dfd8; border-radius: 8px; background: #f4faf8; }
.triage-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.triage-card h2 { margin: 3px 0 0; font-size: 20px; color: var(--text-primary, #213c3b); }
.triage-card p { margin: 12px 0 8px; color: var(--text-primary, #213c3b); line-height: 1.7; }
.triage-card small { color: var(--text-muted, #617b7a); line-height: 1.6; }
.triage-badge { padding: 4px 8px; color: #2d6f62; background: #e2f2ed; border-radius: 999px; font-size: 12px; }
@keyframes blink { 50% { opacity: 0; } }
</style>
