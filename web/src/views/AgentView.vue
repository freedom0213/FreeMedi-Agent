<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Check, ChevronDown, CircleCheck, CircleHelp, LoaderCircle, Plus, Send, Sparkles } from 'lucide-vue-next'
import { ElMessage } from 'element-plus'
import AppShell from '../components/AppShell.vue'
import AppointmentRequirementsForm from '../components/AppointmentRequirementsForm.vue'
import KnowledgeSources from '../components/KnowledgeSources.vue'
import { errorMessage } from '../api/http'
import { periodLabel } from '../api/portal'
import { useAgentStore } from '../stores/agent'
import type { KnowledgeReply, RequirementChanges, TaskStatus } from '../types/agent'

const route = useRoute()
const router = useRouter()
const agent = useAgentStore()
const draft = ref('')
const optimisticMessage = ref('')
const conversation = ref<HTMLElement | null>(null)
const composerInput = ref<HTMLTextAreaElement | null>(null)
const conditionsButton = ref<HTMLButtonElement | null>(null)
const confirmHeading = ref<HTMLElement | null>(null)
const openSidebar = ref<'session' | 'progress' | null>(null)
const formOpen = ref(false)
const choosingAgain = ref(false)

const turns = computed(() => agent.task?.turns ?? [])
const knowledgeReplies = computed(() => agent.task?.facts.knowledgeReplies as Record<string, KnowledgeReply> | undefined)
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
const restoring = ref(true)
const formDirty = ref(false)
const formReset = ref(0)
const canEdit = computed(() => !restoring.value && !agent.restoreFailed && !agent.pendingConfirmation && !agent.busy && (!taskStatus.value
  || ['UNDERSTANDING', 'SEARCHING', 'NEED_MORE_INFO', 'PROPOSAL_READY', 'AWAITING_CONFIRMATION'].includes(taskStatus.value)))
const canReply = computed(() => canEdit.value && !formDirty.value)
const showCandidates = computed(() => !!agent.candidateBatch
  && ['NEED_MORE_INFO', 'PROPOSAL_READY', 'AWAITING_CONFIRMATION'].includes(taskStatus.value ?? ''))
const showReview = computed(() => !!plan.value && !!slot.value && taskStatus.value === 'AWAITING_CONFIRMATION'
  && !agent.pendingConfirmation && !choosingAgain.value)
const canConfirm = computed(() => canReply.value && showReview.value)
const isComplete = computed(() => taskStatus.value === 'COMPLETED' && !!agent.task?.facts.appointmentId)
const verifiedAppointmentNo = computed(() => typeof agent.task?.facts.appointmentId === 'string'
  ? agent.task.facts.appointmentId : undefined)
const isFailed = computed(() => taskStatus.value === 'FAILED')
const canStartNew = computed(() => !restoring.value && !agent.busy && !agent.pendingConfirmation)
const showWelcome = computed(() => !restoring.value && !turns.value.length && !agent.candidateBatch
  && !optimisticMessage.value && !taskStatus.value && !agent.restoreFailed)
const conditions = computed(() => {
  const saved = agent.requirements
  return {
    department: saved?.departmentName || (saved?.departmentId ? '已选科室' : '科室待定'),
    doctor: saved?.doctorName || (saved?.doctorId ? '指定医生' : '不限医生'),
    date: saved?.appointmentDate ? formatDate(saved.appointmentDate) : '日期不限',
    period: saved?.clinicPeriod ? periodLabel(saved.clinicPeriod) : '时段不限',
  }
})
const hasConditions = computed(() => {
  const saved = agent.requirements
  return !!(saved?.departmentId || saved?.departmentName || saved?.doctorId || saved?.doctorName
    || saved?.appointmentDate || saved?.clinicPeriod)
})
const nextAction = computed(() => {
  if (restoring.value) return '正在恢复会话'
  if (agent.restoreFailed) return '重新读取任务'
  if (confirming.value) return '正在提交预约'
  if (agent.pendingConfirmation) return '核验原预约'
  if (agent.busy) return '正在处理需求'
  if (isComplete.value) return '查看原预约'
  if (isFailed.value || taskStatus.value === 'CANCELLED' || taskStatus.value === 'COMPLETED') return '新建会话'
  if (showReview.value) return '核对并确认预约'
  if (showCandidates.value) return agent.candidateBatch?.slots.length ? '选择就诊时间' : '调整条件后再查'
  if (taskStatus.value === 'SEARCHING' && agent.confirmationNotice) return '按原需求重新查号'
  return turns.value.length ? '补充就诊需求' : '描述就诊需求'
})

const steps = computed(() => {
  const status = taskStatus.value
  const stage = isComplete.value ? 3
    : showReview.value || confirming.value || agent.pendingConfirmation ? 2
      : showCandidates.value || status === 'SEARCHING' ? 1
        : !status || status === 'NEED_MORE_INFO' || status === 'UNDERSTANDING' ? 0 : -1
  return ['了解就诊需求', '选择号源', '确认预约'].map((title, index) => ({
    title, active: stage === index, done: stage > index,
  }))
})

const visibleEvents = computed(() => agent.events.filter(event => [
  'READ_TOOL_STARTED', 'READ_TOOL_SUCCEEDED', 'READ_TOOL_FAILED', 'PLAN_PROPOSED',
  'CONFIRMATION_REQUIRED', 'EXECUTION_STARTED', 'EXECUTION_VERIFIED', 'MODEL_RETRY_REQUIRED', 'TASK_FAILED',
].includes(event.type) && (!['PLAN_PROPOSED', 'CONFIRMATION_REQUIRED'].includes(event.type)
  || event.summary === currentPlanId.value)).slice(-8).reverse())

onMounted(async () => {
  await restoreTask()
  const intent = typeof route.query.intent === 'string' ? route.query.intent : ''
  if (intent || route.query.new === '1') {
    await router.replace({ name: 'guide' })
    if (agent.pendingConfirmation || agent.restoreFailed) return
    agent.startNewTask()
    if (intent) await submit(intent)
  }
})

async function restoreTask() {
  restoring.value = true
  await agent.restore()
  if (agent.pendingMessage?.taskId === agent.taskId && agent.pendingMessage) draft.value = agent.pendingMessage.text
  restoring.value = false
}

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
    if (agent.pendingMessage?.retryRequired) draft.value = message
    optimisticMessage.value = ''
    await nextTick()
    if (conversation.value) conversation.value.scrollTop = conversation.value.scrollHeight
  } catch (error) {
    optimisticMessage.value = ''
    draft.value = message
    const messageText = agent.error || errorMessage(error)
    agent.error = messageText
    ElMessage.error(messageText)
  }
}

async function queryCandidates(changes: RequirementChanges) {
  if (!canEdit.value) return
  try {
    const result = await agent.saveRequirements(changes)
    if (result.events.some(event => event.type === 'TOOL_RETRY_REQUIRED')) {
      agent.error = result.message
      return
    }
    await nextTick()
    if (!formDirty.value && formOpen.value) {
      formOpen.value = false
      await nextTick()
      conditionsButton.value?.focus()
    }
  } catch (error) {
    agent.error = errorMessage(error)
  }
}

async function chooseCandidate(slotId: number) {
  if (!canReply.value) return
  try {
    if (plan.value?.draft.slotId !== slotId) await agent.chooseCandidate(slotId)
    if (plan.value?.draft.slotId === slotId && taskStatus.value === 'AWAITING_CONFIRMATION') {
      choosingAgain.value = false
      await nextTick()
      confirmHeading.value?.focus()
    }
  } catch (error) {
    agent.error = errorMessage(error)
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
    if (result?.success) ElMessage.success('预约已创建并完成核验')
    else if (result && !agent.pendingConfirmation && result.status !== 'SEARCHING') ElMessage.warning(result.message)
  } catch (error) {
    const messageText = errorMessage(error)
    agent.error = messageText
    ElMessage.error(messageText)
  } finally {
    confirming.value = false
  }
}

async function startNewTask() {
  if (!canStartNew.value) return
  agent.startNewTask()
  formReset.value++
  formOpen.value = false
  choosingAgain.value = false
  openSidebar.value = null
  draft.value = ''
  conversation.value?.scrollTo({ top: 0 })
  await nextTick()
  composerInput.value?.focus()
}

async function returnToCandidates() {
  choosingAgain.value = true
  if (!showCandidates.value) formOpen.value = true
  await nextTick()
  conditionsButton.value?.focus()
}

function toggleSidebar(panel: 'session' | 'progress') {
  openSidebar.value = openSidebar.value === panel ? null : panel
}

function sendOnEnter(event: KeyboardEvent) {
  if (event.isComposing) return
  event.preventDefault()
  void submit()
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
  if (summary === 'searchHospitalKnowledge' && type.startsWith('READ_TOOL')) {
    return type === 'READ_TOOL_STARTED' ? '正在查阅医院资料'
      : type === 'READ_TOOL_SUCCEEDED' ? '已查阅医院资料' : '暂时无法查阅医院资料'
  }
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
  if (summary === 'searchHospitalKnowledge' && type.startsWith('READ_TOOL')) return '可在本轮回复中查看查询结果'
  if (summary === 'getAppointmentRules' && type.startsWith('READ_TOOL')) return '确认预约前，助手会核对预约规则'
  if (type === 'STATE_CHANGED') return '任务进度已保存'
  if (type === 'TASK_CREATED') return statusText(summary as TaskStatus)
  if (type === 'PLAN_PROPOSED') return '已为你找到一个可预约的时间'
  if (type === 'CONFIRMATION_REQUIRED') return '请在对话区核对方案并点击确认预约'
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
  <AppShell class="guide-frame">
    <div class="guide-page page-wrap">
      <div class="agent-workspace">
        <div class="guide-disclosures">
          <button type="button" class="button-secondary" :aria-expanded="openSidebar === 'session'"
            aria-controls="guide-session" @click="toggleSidebar('session')">
            会话 <ChevronDown :size="15" aria-hidden="true" />
          </button>
          <button type="button" class="button-secondary insights-toggle" :aria-expanded="openSidebar === 'progress'"
            aria-controls="guide-insights" @click="toggleSidebar('progress')">
            本次导诊 <ChevronDown :size="15" aria-hidden="true" />
          </button>
        </div>

        <aside id="guide-session" class="guide-session" :class="{ 'is-open': openSidebar === 'session' }" aria-label="会话">
          <h2>会话</h2>
          <button type="button" class="button-secondary new-session" :disabled="!canStartNew" @click="startNewTask">
            <Plus :size="17" aria-hidden="true" />新建会话
          </button>
          <div class="current-session" :class="{ 'has-session': agent.taskId }">
            <span>当前会话</span>
            <strong>{{ restoring ? '正在恢复会话…' : agent.taskId ? '智能导诊与预约' : '尚未开始会话' }}</strong>
          </div>
        </aside>

        <aside id="guide-insights" class="guide-insights" :class="{ 'is-open': openSidebar === 'progress' }" aria-label="本次导诊">
          <h2>本次导诊</h2>
          <div class="next-action">
            <ArrowRight :size="16" aria-hidden="true" />
            <div><span>下一步</span><strong>{{ nextAction }}</strong></div>
          </div>
          <section v-if="hasConditions" class="guide-details" aria-labelledby="guide-details-title">
            <h3 id="guide-details-title">已了解的需求</h3>
            <dl>
              <div><dt>科室</dt><dd>{{ conditions.department }}</dd></div>
              <div><dt>医生</dt><dd>{{ conditions.doctor }}</dd></div>
              <div><dt>日期</dt><dd>{{ conditions.date }}</dd></div>
              <div><dt>时段</dt><dd>{{ conditions.period }}</dd></div>
            </dl>
          </section>
          <section class="guide-progress" aria-labelledby="guide-progress-title">
            <h3 id="guide-progress-title">办理进度</h3>
            <ol>
              <li v-for="step in steps" :key="step.title" :class="{ done: step.done }"
                :aria-current="step.active ? 'step' : undefined">
                <span class="progress-marker" aria-hidden="true"><Check v-if="step.done" :size="12" /><span v-else>·</span></span>
                {{ step.title }}
              </li>
            </ol>
          </section>
          <details v-if="visibleEvents.length" class="guide-events">
            <summary>本次服务记录</summary>
            <ul>
              <li v-for="(event, index) in visibleEvents" :key="event.type + ':' + event.summary + ':' + index">
                <strong>{{ eventLabel(event.type, event.summary) }}</strong>
                <span>{{ eventSummary(event.type, event.summary) }}</span>
              </li>
            </ul>
          </details>
        </aside>

        <section class="guide-conversation" :class="{ 'is-welcome': showWelcome }" aria-label="预约需求和对话">
          <header class="guide-title">
            <span class="guide-assistant-icon" aria-hidden="true"><Sparkles :size="21" /></span>
            <div><h1>智能导诊</h1><p>就诊方向与预约协助</p></div>
            <button v-if="turns.length && !isComplete" class="button-secondary triage-button" type="button"
              :disabled="!canReply" @click="startTriage">发起智能分诊</button>
          </header>

          <section v-if="agent.restoreFailed" class="guide-status" role="alert">
            <p>暂时无法恢复预约任务，已保留本次任务。</p>
            <button type="button" class="button-secondary" :disabled="restoring" @click="restoreTask">重新读取任务</button>
          </section>
          <section v-if="agent.pendingConfirmation" class="guide-status" aria-label="原预约核验">
            <p role="status">{{ confirming ? '正在提交预约…' : agent.confirmationNotice || '提交结果尚未核实，请查询原预约。' }}</p>
            <div class="guide-actions">
              <button type="button" class="button-secondary" :disabled="agent.busy"
                @click="agent.runConfirmation('check')">核验原预约</button>
              <button v-if="agent.canRetryConfirmation" type="button" class="button-primary" :disabled="agent.busy"
                @click="agent.runConfirmation('retry')">重试原提交</button>
            </div>
          </section>
          <section v-else-if="taskStatus === 'SEARCHING' && agent.confirmationNotice" class="guide-status">
            <p role="status">{{ agent.confirmationNotice }}</p>
            <button type="button" class="button-secondary" :disabled="!canReply"
              @click="queryCandidates({})">按原需求重新查号</button>
          </section>

          <div ref="conversation" class="guide-transcript"
            :class="{ 'has-turns': turns.length || optimisticMessage }">
            <div v-if="restoring" class="guide-working" role="status"><LoaderCircle :size="16" class="spin" />正在读取当前会话…</div>
            <div v-else-if="showWelcome" class="guide-welcome">
              <h2>今天想看哪个科室，<br />或是哪里不舒服？</h2>
              <p>可以告诉我不适持续了多久、想看的科室，或者方便就诊的时间。</p>
              <div class="guide-actions">
                <button class="button-secondary" type="button" :disabled="!canReply" @click="submit('我最近老是咳嗽，已经一周了')">我最近老是咳嗽 <ArrowRight :size="14" /></button>
                <button class="button-secondary" type="button" :disabled="!canReply" @click="submit('帮我找最近有号的时间')">帮我找最近有号的时间 <ArrowRight :size="14" /></button>
              </div>
            </div>

            <article v-for="turn in turns" :key="turn.clientRequestId" class="guide-turn">
              <p class="user-message">{{ turn.userMessage }}</p>
              <div v-if="turn.assistantMessage" class="guide-reply">
                <span class="guide-assistant-icon small" aria-hidden="true"><Sparkles :size="15" /></span>
                <div>
                  <strong>导诊助手</strong>
                  <p>{{ turn.assistantMessage }}</p>
                  <KnowledgeSources :sources="knowledgeReplies?.[turn.turnNo]?.sources ?? []" />
                </div>
              </div>
            </article>

            <div v-if="optimisticMessage" class="guide-turn" aria-live="polite">
              <p class="user-message">{{ optimisticMessage }}</p>
              <div v-if="agent.streamingReply" class="guide-reply">
                <span class="guide-assistant-icon small" aria-hidden="true"><Sparkles :size="15" /></span>
                <div><strong>导诊助手 · 正在回复</strong><p>{{ agent.streamingReply }}</p></div>
              </div>
              <div v-else class="guide-working" role="status"><LoaderCircle :size="16" class="spin" />正在整理就诊需求…</div>
            </div>

            <div v-if="agent.busy && !optimisticMessage" class="guide-working" role="status"><LoaderCircle :size="16" class="spin" />正在处理预约需求…</div>
            <div v-if="agent.error" class="guide-status alert" role="alert"><CircleHelp :size="16" />{{ agent.error }}</div>
          </div>

          <section v-if="recommendation && !showCandidates && !showReview && !isComplete && !agent.pendingConfirmation"
            class="guide-recommendation" aria-labelledby="triage-heading">
            <span>初步就诊方向</span>
            <h2 id="triage-heading">{{ recommendation.departmentName }}</h2>
            <p>{{ recommendation.reason }}</p>
            <small>这是一条就诊方向建议，补充信息后可能调整。</small>
            <button v-if="Number(recommendation.departmentId) > 0" class="button-secondary" type="button"
              :disabled="!canReply" @click="queryCandidates({ departmentId: Number(recommendation.departmentId) })">
              按{{ recommendation.departmentName }}查号
            </button>
          </section>

          <div v-show="!showReview && !isComplete && !isFailed && !agent.pendingConfirmation && !agent.restoreFailed && taskStatus !== 'COMPLETED' && taskStatus !== 'CANCELLED'"
            class="guide-slot-area">
            <header v-if="showCandidates && agent.candidateBatch" class="guide-result-heading">
              <h2 id="candidate-heading">选择就诊时间</h2>
              <span role="status">{{ agent.candidateBatch.slots.length }} 个号源</span>
            </header>
            <div class="guide-condition-bar">
              <span v-if="hasConditions">{{ conditions.department }} · {{ conditions.date }} · {{ conditions.period }} · {{ conditions.doctor }}</span>
              <span v-else>已知道科室或就诊时间？</span>
              <button ref="conditionsButton" class="guide-text-button" type="button"
                :aria-expanded="formOpen" aria-controls="guide-condition-editor"
                :disabled="!canEdit || formDirty" @click="formOpen = !formOpen">
                {{ formOpen ? '收起条件' : hasConditions || showCandidates ? '修改条件' : '填写预约条件' }}
                <ChevronDown :size="15" aria-hidden="true" />
              </button>
            </div>
            <div v-show="formOpen" id="guide-condition-editor">
              <AppointmentRequirementsForm :key="formReset" :requirements="agent.requirements"
                :disabled="!canEdit" @dirty="formDirty = $event" @search="queryCandidates" />
            </div>
            <section v-if="showCandidates && agent.candidateBatch" aria-labelledby="candidate-heading">
              <p v-if="!agent.candidateBatch.slots.length" class="guide-status">{{ agent.candidateBatch.message }}</p>
              <ul v-else class="candidate-list">
                <li v-for="candidate in agent.candidateBatch.slots" :key="candidate.slotId" class="guide-slot">
                  <div><strong>{{ candidate.timeRange }}</strong><span>{{ formatDate(candidate.appointmentDate) }} · {{ periodLabel(candidate.clinicPeriod) }}</span></div>
                  <div><strong>{{ candidate.doctorName }} · {{ candidate.departmentName }}</strong><span>{{ candidate.differences?.length ? candidate.differences.join('、') : '符合当前条件' }}</span></div>
                  <button type="button" class="button-secondary" :disabled="!canReply" @click="chooseCandidate(candidate.slotId)">选择此号源</button>
                </li>
              </ul>
            </section>
          </div>

          <section v-if="showReview && plan && slot" class="guide-review" aria-labelledby="proposal-heading">
            <h2 id="proposal-heading" ref="confirmHeading" tabindex="-1">预约确认单</h2>
            <div class="guide-receipt">
              <div class="guide-receipt-time">
                <div><p>{{ formatDate(plan.draft.appointmentDate) }}</p><strong>{{ slot.timeRange }}</strong></div>
                <span>待你确认</span>
              </div>
              <dl class="guide-receipt-details">
                <div><dt>科室</dt><dd>{{ slot.departmentName }}</dd></div>
                <div><dt>医生</dt><dd>{{ slot.doctorName }}</dd></div>
              </dl>
              <div class="guide-actions guide-receipt-actions">
                <button class="button-primary" type="button" :disabled="!canConfirm" @click="confirmAppointment">
                  确认预约 <ArrowRight :size="16" aria-hidden="true" />
                </button>
                <button class="guide-text-button" type="button" :disabled="!canReply" @click="returnToCandidates">
                  <ArrowLeft :size="15" aria-hidden="true" />{{ showCandidates ? '返回换时间' : '修改条件' }}
                </button>
              </div>
            </div>
          </section>

          <div v-if="isComplete" class="guide-completion" role="status">
            <CircleCheck :size="20" aria-hidden="true" />
            <div><strong>预约已创建并核验</strong>
              <RouterLink class="guide-text-button appointment-result-link"
                :to="{ name: 'appointments', query: { appointmentNo: verifiedAppointmentNo } }">查看原预约</RouterLink>
            </div>
          </div>
          <div v-if="isFailed && !agent.pendingConfirmation" class="guide-status alert" role="alert">
            <CircleHelp :size="18" />本次预约未完成。你可以新建会话并重新查询号源。
          </div>

          <form class="guide-composer" @submit.prevent="submit()">
            <textarea ref="composerInput" v-model="draft" :disabled="!canReply"
              :placeholder="isComplete || isFailed ? '可以新建会话继续导诊' : showWelcome ? '说说哪里不舒服…' : '补充就诊需求，例如「改到下午」'"
              rows="1" aria-label="补充预约需求" @keydown.enter.exact="sendOnEnter" />
            <button class="button-primary" type="submit" :disabled="!draft.trim() || !canReply" aria-label="发送消息">
              发送 <Send :size="15" aria-hidden="true" />
            </button>
          </form>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.guide-frame {
  min-width: 0;
  background: radial-gradient(ellipse at 85% 10%, #f1ebf4, transparent 57%), linear-gradient(140deg, #dce7f1, #e5e7f0 77%);
  color: var(--brand-ink);
}
.guide-frame :deep(main) { display: grid; align-content: safe center; }
.guide-frame :deep(.site-footer) { margin-top: 0; }
.guide-frame :deep(.site-header), .guide-frame :deep(.site-footer) { background: #f8fafc; border-color: #ccdbe8; }
.guide-page { width: min(1360px, calc(100% - 48px)); padding-block: clamp(18px, 3vh, 34px); }
.agent-workspace {
  display: grid;
  grid-template-columns: clamp(156px, 14vw, 200px) minmax(0, 1fr) clamp(216px, 20vw, 288px);
  grid-template-areas: "session conversation insights";
  gap: 14px;
  min-height: clamp(530px, 58vh, 670px);
  padding: 12px;
  border: 1px solid #dce4ee;
  border-radius: 23px;
  background: #f6f8fc;
  box-shadow: 0 17px 43px #283f5b22;
}
.guide-disclosures { display: none; grid-area: disclosures; }
.guide-session, .guide-insights, .guide-conversation {
  min-width: 0;
  border: 1px solid var(--brand-line);
  border-radius: 16px;
}
.guide-session, .guide-insights { padding: 17px 14px; background: #f2f2f8; border-color: #e4e5ef; }
.guide-session { grid-area: session; }
.guide-insights { grid-area: insights; background: #f9fafc; }
.guide-conversation { grid-area: conversation; display: flex; flex-direction: column; gap: 16px; padding: 18px; background: var(--brand-paper); box-shadow: 0 7px 18px #49335312; }
.guide-session h2, .guide-insights h2 { margin: 0 0 13px; font-size: 17px; }
.new-session { width: 100%; padding-inline: 6px; }
.current-session { display: grid; gap: 3px; padding: 10px; margin-top: 15px; border: 1px solid var(--brand-line); border-radius: 9px; background: var(--brand-paper); }
.current-session.has-session { background: var(--brand-lilac); }
.current-session span, .next-action span { font-size: 12px; color: var(--brand-muted); }
.current-session strong, .next-action strong { font-size: 14px; overflow-wrap: anywhere; }
.next-action { display: flex; align-items: center; gap: 9px; margin-bottom: 15px; padding: 10px; border-radius: 10px; background: #e4f1ed; color: #244d40; }
.next-action svg { flex: none; }
.next-action > div { display: grid; }
.next-action span { color: #365e51; }
.guide-details h3, .guide-progress h3 { margin: 0 0 6px; font-size: 14px; }
.guide-details dl, .guide-receipt-details { margin: 0; }
.guide-details dl { display: grid; gap: 6px; margin-bottom: 15px; }
.guide-details dl div { display: flex; justify-content: space-between; gap: 6px; }
.guide-details dt, .guide-receipt-details dt { color: var(--brand-muted); font-size: 13px; }
.guide-details dd, .guide-receipt-details dd { margin: 0; font-size: 14px; overflow-wrap: anywhere; }
.guide-details dd { text-align: right; }
.guide-progress { padding-top: 12px; border-top: 1px solid var(--brand-line); }
.guide-progress ol, .guide-events ul { display: grid; gap: 8px; list-style: none; margin: 0; padding: 0; }
.guide-progress li { display: flex; align-items: center; gap: 7px; color: var(--brand-muted); font-size: 13px; }
.guide-progress [aria-current] { font-weight: 700; color: var(--brand-plum); }
.progress-marker { display: grid; place-items: center; flex: 0 0 20px; height: 20px; border-radius: 50%; background: var(--brand-lilac); color: var(--brand-plum); }
.guide-progress [aria-current] .progress-marker { background: var(--brand-plum); color: white; }
.guide-events { margin-top: 15px; padding-top: 10px; border-top: 1px solid var(--brand-line); }
.guide-events summary { color: var(--brand-plum); font-size: 13px; font-weight: 700; cursor: pointer; }
.guide-events ul { margin-top: 9px; }
.guide-events li { display: grid; gap: 3px; font-size: 12px; overflow-wrap: anywhere; }
.guide-events li span { color: var(--brand-muted); }
.guide-title { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.guide-title h1 { margin: 0; font-size: 19px; }
.guide-title p { margin: 3px 0 0; color: var(--brand-muted); font-size: 13px; }
.triage-button { margin-left: auto; }
.guide-assistant-icon { display: grid; place-items: center; flex: none; width: 40px; height: 40px; border-radius: 11px; color: var(--brand-plum); background: var(--brand-lilac); }
.guide-assistant-icon.small { width: 27px; height: 27px; border-radius: 8px; }
.guide-transcript.has-turns { max-height: 370px; overflow: auto; }
.guide-transcript:empty { display: none; }
.is-welcome .guide-transcript { margin-top: auto; }
.is-welcome .guide-composer { margin-top: 0; margin-bottom: auto; }
.is-welcome .guide-condition-bar { padding: 0 2px; border: 0; background: transparent; color: var(--brand-muted); }
.guide-welcome {
  position: relative; isolation: isolate; overflow: hidden;
  padding: clamp(17px, 2.5vw, 34px);
  background: linear-gradient(118deg, #e9effb 8%, #f1e9f5 73%, #fbede1);
  border-radius: 13px;
}
.guide-welcome::after {
  content: '';
  position: absolute; z-index: -1;
  right: -45px; bottom: -115px;
  width: 260px; height: 215px;
  border: 18px solid #ffffff75; border-radius: 50%;
  box-shadow: 0 0 0 1px #ebd6ea, 0 0 0 17px #f9f3fa80;
  transform: rotate(-25deg);
}
.guide-welcome h2 { margin: 0; font-size: clamp(24px, 2.45vw, 36px); line-height: 1.25; letter-spacing: -.025em; color: #233651; }
.guide-welcome p { margin: 9px 0 13px; color: var(--brand-muted); font-size: 14px; line-height: 1.6; }
.guide-turn { display: grid; gap: 10px; margin-bottom: 14px; }
.guide-turn:last-child { margin-bottom: 0; }
.user-message { justify-self: end; max-width: 91%; margin: 0; padding: 8px 12px; border-radius: 11px 11px 3px 11px; background: var(--brand-lilac); color: var(--brand-ink); font-size: 14px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.guide-reply { display: flex; align-items: start; gap: 9px; }
.guide-reply > div { min-width: 0; }
.guide-reply strong { font-size: 13px; color: var(--brand-plum); }
.guide-reply p { margin: 4px 0 0; font-size: 14px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.guide-working { display: flex; align-items: center; gap: 7px; color: var(--brand-muted); font-size: 13px; }
.guide-status { margin: 0; padding: 10px 12px; border-radius: 9px; border: 1px solid var(--brand-line); background: var(--brand-soft); color: var(--brand-plum); font-size: 14px; }
.guide-status p { margin: 0 0 8px; }
.guide-status.alert { display: flex; align-items: start; gap: 8px; color: #8a3633; background: #fff6f4; }
.guide-status svg { flex: none; }
.guide-recommendation { padding: 13px; border: 1px solid var(--brand-line); border-radius: 11px; background: var(--brand-soft); }
.guide-recommendation h2, .guide-result-heading h2, .guide-review h2 { margin: 0; font-size: 19px; }
.guide-recommendation p { margin: 7px 0; white-space: pre-wrap; }
.guide-recommendation small, .guide-recommendation > span { display: block; color: var(--brand-muted); font-size: 13px; }
.guide-recommendation button { margin-top: 9px; }
.guide-slot-area, .guide-review { display: grid; gap: 9px; }
.guide-result-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 6px; }
.guide-result-heading > span { color: var(--brand-muted); font-size: 13px; white-space: nowrap; }
.guide-condition-bar { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 4px 10px; padding: 5px 10px; border: 1px solid #dce7f1; border-radius: 9px; background: #eff5fb; color: var(--brand-ink); font-size: 13px; }
.guide-text-button { display: inline-flex; align-items: center; gap: 5px; min-height: var(--button-min-height, 36px); padding: 0; border: 0; background: transparent; color: var(--brand-plum); font-weight: 700; font-size: 13px; cursor: pointer; }
.guide-text-button:hover:not(:disabled) { text-decoration: underline; text-underline-offset: 3px; }
.guide-text-button:disabled { opacity: .52; cursor: not-allowed; }
.candidate-list { display: grid; gap: 7px; margin: 0; padding: 0; list-style: none; }
.guide-slot { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 9px; border: 1px solid var(--brand-line); border-radius: 9px; background: white; }
.guide-slot div { display: grid; gap: 3px; overflow-wrap: anywhere; }
.guide-slot div:first-child strong, .guide-receipt-time strong { font-variant-numeric: tabular-nums; }
.guide-slot strong { font-size: 14px; }
.guide-slot span { font-size: 12px; line-height: 1.4; color: var(--brand-muted); }
.guide-slot button { white-space: nowrap; padding-inline: 9px; }
.guide-receipt { border: 1px solid var(--brand-line); border-radius: 13px; overflow: hidden; background: white; }
.guide-receipt-time { display: flex; align-items: start; justify-content: space-between; gap: 7px; padding: 13px 15px; background: linear-gradient(110deg, #eee5f3, #f8e9e5); color: var(--brand-plum); }
.guide-receipt-time p { margin: 0 0 3px; font-size: 14px; font-weight: 700; }
.guide-receipt-time strong { font-size: clamp(24px, 2.4vw, 34px); line-height: 1.1; }
.guide-receipt-time > span { padding: 3px 8px; border-radius: 20px; background: var(--brand-paper); white-space: nowrap; font-size: 12px; }
.guide-receipt-details { display: flex; flex-wrap: wrap; gap: 8px 24px; padding: 10px 15px; }
.guide-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 15px; }
.guide-receipt-actions { padding: 9px 15px; border-top: 1px solid var(--brand-line); }
.guide-completion { display: flex; gap: 8px; align-items: start; padding: 11px; border-radius: 10px; background: #f0f7f2; color: #316247; }
.guide-completion div { display: grid; gap: 4px; }
.guide-composer { display: flex; align-items: center; gap: 7px; margin-top: auto; padding: 5px 6px 5px 9px; border: 1px solid var(--brand-line); border-radius: 11px; background: var(--brand-soft); }
.guide-composer textarea { flex: 1; min-width: 0; min-height: 39px; max-height: 100px; padding: 8px 2px; border: 0; background: transparent; resize: vertical; color: var(--brand-ink); font-size: 14px; line-height: 1.6; }
.guide-composer textarea::placeholder { color: var(--brand-muted); opacity: 1; }
.guide-composer button { flex: none; }

@media (max-width: 900px) {
  .agent-workspace { grid-template-columns: minmax(0, 1fr) minmax(190px, .4fr); grid-template-areas: "disclosures disclosures" "conversation insights"; }
  .agent-workspace:has(.guide-session.is-open) { grid-template-areas: "disclosures disclosures" "session session" "conversation insights"; }
  .guide-disclosures { display: flex; gap: 7px; }
  .guide-disclosures .insights-toggle, .guide-session:not(.is-open) { display: none; }
  .guide-session { max-width: 290px; }
}
@media (max-width: 640px) {
  .guide-frame :deep(main) { align-content: start; }
  .guide-page { width: calc(100% - 24px); padding-block: 12px; }
  .agent-workspace, .agent-workspace:has(.guide-session.is-open) { display: flex; flex-direction: column; min-height: 0; gap: 9px; padding: 0; border: 0; background: transparent; box-shadow: none; }
  .guide-disclosures > button { flex: 1; }
  .guide-disclosures .insights-toggle { display: inline-flex; }
  .guide-session { max-width: none; }
  .guide-insights:not(.is-open) { display: none; }
  .guide-conversation { padding: 11px; gap: 12px; }
  .guide-transcript.has-turns { max-height: 315px; }
  .guide-welcome { padding: 14px; }
  .guide-slot { grid-template-columns: minmax(0, 1fr) auto; }
  .guide-slot div:nth-child(2) { grid-row: 2; }
  .guide-slot button { grid-column: 2; grid-row: 1 / 3; }
  .guide-receipt-actions { display: grid; }
  .guide-receipt-actions .guide-text-button { justify-content: center; }
  .guide-completion .appointment-result-link { border-radius: 9px; background: var(--brand-plum); color: #fff; }
  .guide-completion .appointment-result-link:hover { background: var(--brand-plum-dark); text-decoration: none; }
  .guide-composer textarea { font-size: 16px; }
}
</style>
