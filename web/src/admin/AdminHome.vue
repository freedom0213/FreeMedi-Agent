<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, RefreshCw } from 'lucide-vue-next'
import {
  adminSession, readAppointmentOperations, readDocuments, readKnowledgeLibrary,
  readModelCallAttempts, readModelCallOverview,
} from '../api/admin'
import { clock, observedModelLabel, reasons } from './agentTraceLabels'
import { hospitalDay } from './hospitalCalendar'
import { useKnowledgeRead } from './useKnowledgeRead'

const today = ref(hospitalDay())
const dates = computed(() => ({ from: today.value, to: today.value }))
const visits = useKnowledgeRead(signal => readAppointmentOperations(dates.value, signal))
const knowledge = useKnowledgeRead(async signal => {
  const [documents, groups] = await Promise.all([readDocuments(signal), readKnowledgeLibrary(signal)])
  return {
    drafts: documents.filter(row => row.draftVersion != null).length,
    failed: groups.reduce((total, group) => total + group.failed, 0),
  }
})
const calls = useKnowledgeRead(async signal => {
  const range = dates.value
  const overview = await readModelCallOverview(range, signal)
  const failed = overview.totals.failed
  if (!failed) return { failed, latest: undefined, model: undefined }
  const { items } = await readModelCallAttempts({ ...range, outcome: 'FAILED' }, signal)
  const latest = items[0]
  return { failed, latest, model: overview.options.find(option => option.key === latest?.modelKey) }
})

const running = computed(() => [knowledge, visits, calls].some(section => section.state.value === 'loading'))
const expired = computed(() => [knowledge, visits, calls].some(section => section.state.value === 'session'))
const counts = computed(() => visits.data.value ? [
  { label: '开放排班', value: visits.data.value.openSchedules, unit: '场', to: { name: 'admin-schedules', query: { ...dates.value, status: 'OPEN' } } },
  { label: '有效预约', value: visits.data.value.totals.booked, unit: '笔', to: { name: 'admin-visit-operations', query: dates.value } },
  { label: '可预约余号', value: visits.data.value.totals.remaining, unit: '个', to: { name: 'admin-visit-operations', query: dates.value } },
] : [])
const number = new Intl.NumberFormat('zh-CN')

function refresh() {
  today.value = hospitalDay()
  knowledge.reload()
  visits.reload()
  calls.reload()
}
</script>

<template>
  <header class="admin-list-heading workbench-heading">
    <div>
      <h1 class="admin-page-title">欢迎，{{ adminSession?.displayName }}</h1>
      <p class="admin-muted">工作台 · <time :datetime="today">{{ today }}</time>（北京时间）</p>
    </div>
    <button class="admin-button" type="button" :disabled="running" @click="refresh"><RefreshCw :size="16" aria-hidden="true" /> 刷新</button>
  </header>

  <p v-if="expired" class="admin-form-error" role="alert">
    管理员会话已失效。<RouterLink class="admin-text-link" :to="{ name: 'admin-login', query: { next: '/admin' } }">重新登录</RouterLink>
  </p>
  <section class="admin-panel workbench-knowledge" aria-labelledby="workbench-knowledge">
    <header class="admin-panel-toolbar">
      <h2 id="workbench-knowledge">知识库待处理</h2>
      <RouterLink class="admin-text-link" :to="{ name: 'admin-knowledge' }">查看院内资料 <ArrowRight :size="15" aria-hidden="true" /></RouterLink>
    </header>
    <p v-if="knowledge.state.value === 'loading'" class="admin-empty" role="status">正在读取知识库待处理事项…</p>
    <div v-else-if="knowledge.state.value === 'error'" class="admin-empty" role="alert">
      知识库摘要加载失败。<button class="admin-button" type="button" @click="knowledge.reload">重试知识库</button>
    </div>
    <div v-else-if="knowledge.state.value === 'ready' && knowledge.data.value" class="workbench-queue">
      <RouterLink :to="{ name: 'admin-knowledge', query: { status: 'DRAFT' } }">
        <div><strong>待发布文档</strong><small>按文档统计，打开最新草稿</small></div>
        <span class="workbench-value">{{ number.format(knowledge.data.value.drafts) }} <small>份</small></span>
        <ArrowRight :size="17" aria-hidden="true" />
      </RouterLink>
      <RouterLink :to="{ name: 'admin-knowledge-index', query: { status: 'PUBLISHED', embedding: 'FAILED' }, hash: '#knowledge-fragment-list' }">
        <div><strong>索引失败片段</strong><small>已发布且可重试的片段</small></div>
        <span class="workbench-value" :class="{ 'admin-negative': knowledge.data.value.failed > 0 }">{{ number.format(knowledge.data.value.failed) }} <small>段</small></span>
        <ArrowRight :size="17" aria-hidden="true" />
      </RouterLink>
    </div>
  </section>

  <div class="workbench-secondary">
    <section class="admin-panel" aria-labelledby="workbench-visits">
      <header class="admin-panel-toolbar"><h2 id="workbench-visits">今日门诊</h2></header>
      <p v-if="visits.state.value === 'loading'" class="admin-empty" role="status">正在读取今日排班与号源…</p>
      <div v-else-if="visits.state.value === 'error'" class="admin-empty" role="alert">
        今日门诊加载失败。<button class="admin-button" type="button" @click="visits.reload">重试今日门诊</button>
      </div>
      <template v-else-if="visits.state.value === 'ready'">
        <div class="workbench-queue">
          <RouterLink v-for="row in counts" :key="row.label" :to="row.to">
            <strong>{{ row.label }}</strong><span class="workbench-value">{{ number.format(row.value) }} <small>{{ row.unit }}</small></span>
            <ArrowRight :size="17" aria-hidden="true" />
          </RouterLink>
        </div>
        <p class="workbench-note admin-muted">按今天出诊统计。余号为当前仍可预约的号源。</p>
      </template>
    </section>

    <section class="admin-panel" aria-labelledby="workbench-calls">
      <header class="admin-panel-toolbar"><h2 id="workbench-calls">AI 调用失败</h2></header>
      <p v-if="calls.state.value === 'loading'" class="admin-empty" role="status">正在读取今日模型调用…</p>
      <div v-else-if="calls.state.value === 'error'" class="admin-empty" role="alert">
        AI 调用摘要加载失败。<button class="admin-button" type="button" @click="calls.reload">重试 AI 调用</button>
      </div>
      <template v-else-if="calls.state.value === 'ready' && calls.data.value">
        <div class="workbench-queue">
          <RouterLink :to="{ name: 'admin-model-calls', query: { ...dates, outcome: 'FAILED' }, hash: '#call-details' }">
            <strong>今日失败尝试</strong>
            <span class="workbench-value" :class="{ 'admin-negative': calls.data.value.failed > 0 }">{{ number.format(calls.data.value.failed) }} <small>次</small></span>
            <ArrowRight :size="17" aria-hidden="true" />
          </RouterLink>
        </div>
        <div v-if="calls.data.value.latest" class="workbench-failure">
          <p><strong>{{ calls.data.value.latest.reason ? (reasons[calls.data.value.latest.reason] ?? calls.data.value.latest.reason) : '未记录原因' }}</strong>
            <small>最近失败 · <time :datetime="calls.data.value.latest.recordedAt">{{ clock.format(new Date(calls.data.value.latest.recordedAt)) }}</time>
              · {{ calls.data.value.model ? observedModelLabel(calls.data.value.model) : '版本未记录' }}</small>
          </p>
          <RouterLink class="admin-text-link" :to="{ name: 'admin-agent-timeline', query: { taskId: calls.data.value.latest.taskId } }">
            查看执行时间线 <ArrowRight :size="15" aria-hidden="true" />
          </RouterLink>
        </div>
        <p v-else-if="!calls.data.value.failed" class="workbench-note admin-muted">今日暂无失败记录。</p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.workbench-heading p { margin: 5px 0 0; }
.workbench-heading button { flex: none; }
.workbench-knowledge { border-color: #cadaf0; }
.workbench-knowledge .admin-panel-toolbar { background: #eef4ff; border-radius: 8px 8px 0 0; flex-wrap: wrap; }
.workbench-queue a { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 12px;
  min-height: 60px; padding: 12px 18px; border-top: 1px solid var(--admin-border); }
.workbench-queue a:hover { background: #f3f7ff; }
.workbench-queue a:focus-visible { outline-offset: -3px; }
.workbench-queue a > svg { color: var(--admin-blue); }
.workbench-queue a small, .workbench-failure small { color: var(--admin-muted); font-size: 13px; font-weight: 400; }
.workbench-queue a div small, .workbench-failure small { display: block; margin-top: 2px; }
.workbench-value { font-size: 22px; font-weight: 680; font-variant-numeric: tabular-nums; white-space: nowrap; }
.workbench-secondary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 16px; align-items: start; }
.workbench-note, .workbench-failure { margin: 0; padding: 12px 18px; border-top: 1px solid var(--admin-border); }
.workbench-failure { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; justify-content: space-between; }
.workbench-failure p { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 950px) { .workbench-secondary { grid-template-columns: minmax(0, 1fr); } }
</style>
