<script setup lang="ts">
import { computed, ref } from 'vue'
import { Activity, BrainCircuit, CalendarCheck, RefreshCw, Wrench } from 'lucide-vue-next'
import type { AgentTraceEntry } from '../api/admin'
import AdminTaskTracePicker from './AdminTaskTracePicker.vue'
import { actionNames, clock, outcomes, reasons, taskName, taskStatus } from './agentTraceLabels'
import { toolNames } from './toolLabels'
import { useAdminTaskTrace } from './useAdminTaskTrace'

const { recent, trace, search, taskId, recentLoading, recentIssue, detailLoading, detailIssue,
  moreLoading, moreIssue, refresh, locate, loadMore } = useAdminTaskTrace('admin-agent-timeline')

const filters = [
  { key: 'all', label: '全部步骤' },
  { key: 'model', label: '模型判断' },
  { key: 'tool', label: '工具调用' },
  { key: 'booking', label: '预约办理' },
] as const
type Category = Exclude<typeof filters[number]['key'], 'all'> | 'turn'
const filter = ref<typeof filters[number]['key']>('all')
const labels: Record<Category, string> = { model: '模型', tool: '工具', booking: '预约', turn: '任务' }
const icons = { model: BrainCircuit, tool: Wrench, booking: CalendarCheck, turn: Activity }

function category(type: string): Category {
  if (type === 'MODEL_DECISION') return 'model'
  if (type === 'READ_TOOL_ATTEMPT') return 'tool'
  if (type === 'CANDIDATE_SELECTED' || type.startsWith('CONFIRMATION_') || type.startsWith('APPOINTMENT_')) return 'booking'
  return 'turn'
}

const turns = computed(() => {
  const groups: { number: number; entries: AgentTraceEntry[] }[] = []
  for (const event of trace.value?.entries ?? []) {
    if (filter.value !== 'all' && category(event.type) !== filter.value) continue
    const current = groups.at(-1)
    if (current?.number === event.turnNo) current.entries.push(event)
    else groups.push({ number: event.turnNo, entries: [event] })
  }
  return groups
})
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">Agent 执行时间线</h1>
    <button class="admin-button" type="button" :disabled="recentLoading || detailLoading" @click="refresh++">
      <RefreshCw :size="15" aria-hidden="true" /> 刷新时间线
    </button>
  </header>
  <div class="admin-trace-grid">
    <AdminTaskTracePicker v-model="search" route-name="admin-agent-timeline" :recent="recent" :recent-loading="recentLoading"
      :recent-issue="recentIssue" :task-id="taskId" @locate="locate" />
    <section class="admin-panel admin-trace-detail" aria-labelledby="agent-timeline-title">
      <header class="admin-panel-toolbar timeline-toolbar">
        <h2 id="agent-timeline-title">任务执行记录</h2>
        <RouterLink v-if="trace" class="admin-button" :to="{ name: 'admin-tool-logs', query: { taskId: trace.task.taskId } }">
          查看工具调用日志
        </RouterLink>
      </header>
      <p v-if="detailLoading" class="admin-empty" role="status">正在读取任务时间线…</p>
      <p v-else-if="detailIssue" class="admin-empty" role="alert">{{ detailIssue }}</p>
      <p v-else-if="!trace" class="admin-empty">请选择任务，或输入完整任务编号。</p>
      <template v-else>
        <dl class="admin-trace-summary timeline-summary">
          <div><dt>任务编号</dt><dd><code>{{ trace.task.taskId }}</code></dd></div>
          <div><dt>任务类型</dt><dd>{{ taskName(trace.task.taskType) }}</dd></div>
          <div><dt>当前状态</dt><dd>{{ taskStatus(trace.task.status) }}</dd></div>
          <div><dt>对话轮次</dt><dd>{{ trace.task.turnCount }}</dd></div>
        </dl>
        <p v-if="!trace.entries.length" class="admin-empty">该任务还没有可显示的执行记录。</p>
        <template v-else>
          <div class="timeline-filters" role="group" aria-label="执行步骤类型">
            <button v-for="item in filters" :key="item.key" class="admin-button" type="button"
              :aria-pressed="filter === item.key" @click="filter = item.key">{{ item.label }}</button>
          </div>
          <p v-if="!turns.length" class="admin-empty">已加载的记录中没有这类步骤。</p>
          <section v-for="turn in turns" :key="turn.entries[0]?.sequence" class="timeline-turn">
            <h3>{{ turn.number ? '第 ' + turn.number + ' 轮' : '对话前的操作' }}</h3>
            <ol class="timeline-events" :aria-label="turn.number ? '第 ' + turn.number + ' 轮执行事件' : '对话前执行事件'">
              <li v-for="event in turn.entries" :key="event.sequence" class="timeline-event">
                <div class="timeline-marker"><component :is="icons[category(event.type)]" :size="15" aria-hidden="true" /></div>
                <div class="timeline-event-body">
                  <div class="timeline-event-meta">
                    <span>{{ labels[category(event.type)] }}</span>
                    <time :datetime="event.recordedAt">{{ clock.format(new Date(event.recordedAt)) }}</time>
                    <span v-if="event.stepNo > 0">步骤 {{ event.stepNo }}</span>
                  </div>
                  <strong>{{ event.tool ? toolNames[event.tool] ?? event.tool : actionNames[event.type] ?? event.type }}</strong>
                  <code v-if="event.tool" class="timeline-tool">{{ event.tool }}</code>
                  <p v-if="event.reason" class="admin-trace-reason">{{ reasons[event.reason] ?? '原因码' }} · <code>{{ event.reason }}</code></p>
                  <p v-if="event.modelVersion || event.promptVersion || event.resultCount != null" class="admin-muted timeline-detail">
                    <span v-if="event.modelVersion">模型版本 {{ event.modelVersion === 'environment' ? '启动配置' : event.modelVersion }}</span>
                    <span v-if="event.promptVersion">Prompt {{ event.promptVersion.startsWith('builtin-') ? '内置 ' + event.promptVersion.slice(8) : event.promptVersion }}</span>
                    <span v-if="event.resultCount != null">{{ event.resultCount }} 条结果</span>
                  </p>
                </div>
                <div class="timeline-outcome">
                  <span class="admin-tag" :data-state="event.outcome === 'SUCCEEDED' ? 'INDEXED' : event.outcome === 'FAILED' ? 'FAILED' : 'PENDING'">
                    {{ outcomes[event.outcome] ?? event.outcome }}
                  </span>
                  <small v-if="event.attempt > 1">第 {{ event.attempt }} 次 · 重试</small>
                  <small v-if="event.durationMs != null">{{ event.durationMs }} ms</small>
                </div>
              </li>
            </ol>
          </section>
        </template>
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

<style scoped>
.timeline-toolbar { flex-wrap: wrap; }
.timeline-summary { grid-template-columns: minmax(0, 1.25fr) repeat(3, minmax(0, 1fr)); }
.timeline-filters { display: flex; flex-wrap: wrap; gap: 7px; padding: 12px 16px; border-bottom: 1px solid var(--admin-border); }
.timeline-filters button[aria-pressed=true] { background: #eaf2ff; border-color: #a8c0e6; color: var(--admin-blue-deep); font-weight: 700; }
.timeline-turn { padding: 13px 16px 0; }
.timeline-turn:last-child { padding-bottom: 10px; }
.timeline-turn h3 { margin: 0 0 8px; font-size: 14px; }
.timeline-events { list-style: none; padding: 0; margin: 0; }
.timeline-event { position: relative; display: grid; grid-template-columns: 28px minmax(0, 1fr) max-content; gap: 10px; padding: 9px 0 13px; }
.timeline-event:not(:last-child)::after { content: ''; position: absolute; top: 34px; bottom: -10px; left: 13px; width: 1px; background: var(--admin-border); }
.timeline-marker { position: relative; z-index: 1; width: 28px; height: 28px; border: 1px solid var(--admin-border);
  display: grid; place-items: center; border-radius: 50%; background: #f6f8fc; color: var(--admin-blue-deep); }
.timeline-event-body { min-width: 0; overflow-wrap: anywhere; }
.timeline-event-meta, .timeline-detail, .timeline-outcome small { font-size: 12px; color: var(--admin-muted); }
.timeline-event-meta { display: flex; flex-wrap: wrap; gap: 2px 8px; margin-bottom: 2px; }
.timeline-tool { display: inline-block; margin-inline-start: 6px; }
.timeline-detail, .timeline-event-body p { margin: 3px 0 0; }
.timeline-detail { display: flex; flex-wrap: wrap; gap: 2px 10px; overflow-wrap: anywhere; }
.timeline-outcome { display: grid; align-content: start; justify-items: end; gap: 3px; white-space: nowrap; }
@media (max-width: 460px) {
  .timeline-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .timeline-event { gap: 7px; }
  .timeline-turn { padding-inline: 12px; }
  .timeline-tool { display: block; margin-inline-start: 0; }
}
</style>
