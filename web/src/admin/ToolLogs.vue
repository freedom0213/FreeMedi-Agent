<script setup lang="ts">
import { computed, ref, shallowRef, watch, watchEffect } from 'vue'
import { Activity, RefreshCw, Search } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { adminHttpStatus, readAdminAgentTrace, readRecentAgentTasks, type AgentTaskSummary, type AgentTracePage } from '../api/admin'
import { toolNames } from './toolLabels'

const route = useRoute()
const router = useRouter()
const recent = shallowRef<AgentTaskSummary[]>([])
const defaultTaskId = ref('')
const trace = shallowRef<AgentTracePage>()
const search = ref('')
const recentLoading = ref(true)
const recentIssue = ref('')
const detailLoading = ref(false)
const detailIssue = ref('')
const moreLoading = ref(false)
const moreIssue = ref(false)
const refresh = ref(0)
const explicitId = computed(() => typeof route.query.taskId === 'string' ? route.query.taskId : '')
const taskId = computed(() => explicitId.value || defaultTaskId.value)
let detailRequest: AbortController | undefined

const actionNames: Record<string, string> = {
  TURN_STARTED: '开始处理请求', MODEL_DECISION: '模型判断', READ_TOOL_ATTEMPT: '只读工具调用',
  TURN_REPLY_SAVED: '保存回复', TURN_LIMIT: '任务步数已达上限', CANDIDATE_SELECTED: '选择号源',
  CONFIRMATION_CLAIMED: '接收预约确认', CONFIRMATION_REJECTED: '预约确认被拒绝',
  APPOINTMENT_WRITE: '创建预约', APPOINTMENT_VERIFY: '核验预约', APPOINTMENT_LOOKUP: '查询原预约',
}
const outcomes: Record<string, string> = {
  SUCCEEDED: '成功', FAILED: '失败', STARTED: '已开始', PENDING: '待核验', UNKNOWN: '结果待确认',
}
const statuses: Record<string, string> = {
  UNDERSTANDING: '了解需求', NEED_MORE_INFO: '待补充需求', SEARCHING: '查询中', PROPOSAL_READY: '待选号',
  AWAITING_CONFIRMATION: '待确认', EXECUTING: '办理中', VERIFYING: '核验中',
  COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消',
}
const reasons: Record<string, string> = {
  MODEL_TRANSIENT: '模型服务暂时不可用', MODEL_TIMEOUT: '模型调用超时',
  TOOL_TRANSIENT: '工具服务暂时不可用', TOOL_TIMEOUT: '工具调用超时',
  TOOL_DISABLED: '工具已停用',
  KNOWLEDGE_INDEX_UNAVAILABLE: '资料索引暂不可用', PLAN_EXPIRED: '确认单已过期',
  SLOT_UNAVAILABLE: '号源已不可用', OUTCOME_UNKNOWN: '提交结果待核验', VERIFY_PENDING: '预约核验未完成',
}
const clock = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
const taskNames: Record<string, string> = { APPOINTMENT_BOOKING: '智能导诊预约', VISIT_CONSULTATION: '就诊准备咨询' }
const taskName = (type: string) => taskNames[type] ?? type
const taskStatus = (status: string) => statuses[status] ?? status

watch(explicitId, value => { search.value = value }, { immediate: true })

watchEffect((invalidate) => {
  void refresh.value
  const request = new AbortController()
  invalidate(() => request.abort())
  recentLoading.value = true
  recentIssue.value = ''
  readRecentAgentTasks(request.signal).then((tasks) => {
    if (!request.signal.aborted) {
      recent.value = tasks
      if (!defaultTaskId.value) defaultTaskId.value = tasks[0]?.taskId ?? ''
    }
  }).catch((error) => {
    if (!request.signal.aborted) {
      const status = adminHttpStatus(error)
      recentIssue.value = status === 401 || status === 403 ? '管理员会话已失效。' : '最近任务加载失败。'
    }
  }).finally(() => { if (!request.signal.aborted) recentLoading.value = false })
})

watch([taskId, refresh], ([id], _, invalidate) => {
  const request = new AbortController()
  detailRequest = request
  invalidate(() => request.abort())
  trace.value = undefined
  detailIssue.value = ''
  moreLoading.value = false
  moreIssue.value = false
  detailLoading.value = !!id
  if (!id) return
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(id)) {
    detailIssue.value = '请输入有效的任务编号。'
    detailLoading.value = false
    return
  }
  readAdminAgentTrace(id, 0, request.signal).then((value) => {
    if (!request.signal.aborted) trace.value = value
  }).catch((error) => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    detailIssue.value = status === 404 ? '找不到这条任务，请核对任务编号。'
      : status === 401 || status === 403 ? '管理员会话已失效。' : '追踪详情加载失败。'
  }).finally(() => { if (!request.signal.aborted) detailLoading.value = false })
}, { immediate: true })

function locate() {
  const id = search.value.trim()
  if (id === explicitId.value) refresh.value++
  else void router.push({ name: 'admin-tool-logs', query: id ? { taskId: id } : {} })
}

async function loadMore() {
  const current = trace.value
  const request = detailRequest
  if (!current?.nextAfter || !request || moreLoading.value) return
  moreIssue.value = false
  moreLoading.value = true
  try {
    const next = await readAdminAgentTrace(current.task.taskId, current.nextAfter, request.signal)
    if (!request.signal.aborted) trace.value = { ...next, entries: [...current.entries, ...next.entries] }
  } catch {
    if (!request.signal.aborted) moreIssue.value = true
  } finally {
    if (!request.signal.aborted) moreLoading.value = false
  }
}
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">工具调用日志</h1>
    <button class="admin-button" type="button" :disabled="recentLoading || detailLoading" @click="refresh++">
      <RefreshCw :size="15" aria-hidden="true" /> 刷新日志
    </button>
  </header>
  <div class="admin-trace-grid">
    <section class="admin-panel admin-trace-picker" aria-labelledby="admin-recent-tasks">
      <div class="admin-panel-toolbar"><h2 id="admin-recent-tasks">最近任务</h2></div>
      <form class="admin-trace-search" @submit.prevent="locate">
        <label for="trace-task-search">按任务编号定位</label>
        <div>
          <input id="trace-task-search" v-model="search" class="admin-input" type="search" maxlength="64" placeholder="输入完整任务编号" />
          <button class="admin-button" type="submit" aria-label="查找任务"><Search :size="17" aria-hidden="true" /></button>
        </div>
      </form>
      <p v-if="recentLoading && !recent.length" class="admin-empty" role="status">正在读取任务…</p>
      <p v-else-if="recentIssue" class="admin-empty" role="alert">{{ recentIssue }}</p>
      <p v-else-if="!recent.length" class="admin-empty">暂无任务记录。</p>
      <ul v-else class="admin-trace-tasks">
        <li v-for="task in recent" :key="task.taskId">
          <RouterLink :to="{ name: 'admin-tool-logs', query: { taskId: task.taskId } }"
                      :aria-current="task.taskId === taskId ? 'true' : undefined">
            <span class="admin-trace-task-top"><strong>{{ taskName(task.taskType) }}</strong><small>{{ taskStatus(task.status) }}</small></span>
            <code :title="task.taskId">{{ task.taskId }}</code>
            <time :datetime="task.updatedAt">{{ clock.format(new Date(task.updatedAt)) }}</time>
          </RouterLink>
        </li>
      </ul>
    </section>
    <section class="admin-panel admin-trace-detail" aria-labelledby="admin-trace-title">
      <header class="admin-panel-toolbar"><h2 id="admin-trace-title"><Activity :size="18" aria-hidden="true" /> 调用记录</h2></header>
      <p v-if="detailLoading" class="admin-empty" role="status">正在读取调用记录…</p>
      <p v-else-if="detailIssue" class="admin-empty" role="alert">{{ detailIssue }}</p>
      <p v-else-if="!trace" class="admin-empty">选择一个任务以查看调用记录。</p>
      <template v-else>
        <dl class="admin-trace-summary">
          <div><dt>任务编号</dt><dd><code>{{ trace.task.taskId }}</code></dd></div>
          <div><dt>任务状态</dt><dd>{{ taskStatus(trace.task.status) }}</dd></div>
          <div><dt>对话轮次</dt><dd>{{ trace.task.turnCount }}</dd></div>
        </dl>
        <p v-if="!trace.entries.length" class="admin-empty">此任务暂无 S5 追踪记录。</p>
        <ol v-else class="admin-trace-events" aria-label="按时间排列的调用记录">
          <li v-for="event in trace.entries" :key="event.sequence" :data-outcome="event.outcome">
            <div class="admin-trace-event-clock">
              <time :datetime="event.recordedAt">{{ clock.format(new Date(event.recordedAt)) }}</time>
              <small>第 {{ event.turnNo }} 轮 · 步骤 {{ event.stepNo }}</small>
            </div>
            <div class="admin-trace-event-name">
              <strong>{{ event.tool ? toolNames[event.tool] ?? event.tool : actionNames[event.type] ?? event.type }}</strong>
              <code v-if="event.tool">{{ event.tool }}</code>
              <span v-if="event.reason" class="admin-trace-reason">
                {{ reasons[event.reason] ?? '原因码' }} · <code>{{ event.reason }}</code>
              </span>
              <small v-if="event.resultCount !== null">{{ event.resultCount }} 条结果</small>
              <small v-if="event.modelVersion">模型版本 {{ event.modelVersion === 'environment' ? '启动配置' : event.modelVersion }}</small>
            </div>
            <div class="admin-trace-event-result">
              <span class="admin-tag" :data-state="event.outcome === 'SUCCEEDED' ? 'INDEXED' : event.outcome === 'FAILED' ? 'FAILED' : 'PENDING'">
                {{ outcomes[event.outcome] ?? event.outcome }}
              </span>
              <small v-if="event.attempt > 1">第 {{ event.attempt }} 次 · 重试</small>
            </div>
            <span class="admin-trace-duration" :aria-label="event.durationMs === null ? '未记录耗时' : '耗时 ' + event.durationMs + ' 毫秒'">
              {{ event.durationMs === null ? '—' : event.durationMs + ' ms' }}
            </span>
          </li>
        </ol>
        <footer v-if="trace.nextAfter" class="admin-panel-toolbar admin-trace-footer">
          <p v-if="moreIssue" class="admin-form-error" role="alert">后续记录加载失败，可以重试。</p>
          <button class="admin-button" type="button" :disabled="moreLoading" @click="loadMore">
            {{ moreLoading ? '正在加载…' : '加载后续记录' }}
          </button>
        </footer>
      </template>
    </section>
  </div>
</template>
