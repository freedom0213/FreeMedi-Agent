<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import { ArrowRight, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  adminHttpStatus, agentSettingsError, readModelCallAttempts, readModelCallOverview,
  type ModelCallOutcome, type ModelCallOverview, type ModelCallPage,
} from '../api/admin'
import { clock, observedModelLabel, outcomes, reasons } from './agentTraceLabels'
import { hospitalDay, queryDate } from './hospitalCalendar'

const route = useRoute()
const router = useRouter()
const from = ref('')
const to = ref('')
const model = ref('')
const outcome = ref(readOutcome(route.query.outcome))
const dateError = ref('')
const overviewError = ref('')
const detailError = ref('')
const extraError = ref('')
const overview = shallowRef<ModelCallOverview>()
const detail = shallowRef<ModelCallPage>()
const refresh = ref(0)
const detailRefresh = ref(0)
const loadingMore = ref(false)
const expired = ref(false)
let detailRequest: AbortController | undefined
onScopeDispose(() => detailRequest?.abort())

const applied = computed(() => ({
  from: queryDate(route.query.from) || hospitalDay(-6),
  to: queryDate(route.query.to) || hospitalDay(),
  model: typeof route.query.model === 'string' ? route.query.model : undefined,
}))

function readOutcome(value: unknown): ModelCallOutcome | '' {
  return value === 'FAILED' || value === 'SUCCEEDED' ? value : ''
}

watch(() => route.query.outcome, value => { outcome.value = readOutcome(value) })

watch(applied, filters => {
  from.value = filters.from
  to.value = filters.to
  model.value = filters.model ?? ''
  dateError.value = ''
}, { immediate: true })

watch([applied, refresh], ([filters], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  overview.value = undefined
  overviewError.value = ''
  expired.value = false
  void readModelCallOverview(filters, request.signal).then(data => {
    if (!request.signal.aborted) overview.value = data
  }).catch(error => {
    if (request.signal.aborted) return
    expired.value = [401, 403].includes(adminHttpStatus(error) ?? 0)
    overviewError.value = adminHttpStatus(error) === 400 ? agentSettingsError(error) : '调用统计加载失败。'
  })
}, { immediate: true })

watch([applied, outcome, refresh, detailRefresh], ([filters, result], _, onCleanup) => {
  const request = new AbortController()
  detailRequest = request
  onCleanup(() => request.abort())
  detail.value = undefined
  detailError.value = ''
  extraError.value = ''
  loadingMore.value = false
  void readModelCallAttempts({ ...filters, outcome: result || undefined }, request.signal).then(data => {
    if (!request.signal.aborted) detail.value = data
  }).catch(() => {
    if (!request.signal.aborted) detailError.value = '调用明细加载失败。'
  })
}, { immediate: true })

async function loadMore() {
  const current = detail.value
  const request = detailRequest
  if (!current?.nextBefore || !request || loadingMore.value) return
  loadingMore.value = true
  extraError.value = ''
  try {
    const data = await readModelCallAttempts({ ...applied.value, outcome: outcome.value || undefined, before: current.nextBefore }, request.signal)
    if (!request.signal.aborted) detail.value = { items: [...current.items, ...data.items], nextBefore: data.nextBefore }
  } catch {
    if (!request.signal.aborted) extraError.value = '更多明细加载失败，请重试。'
  } finally {
    if (!request.signal.aborted) loadingMore.value = false
  }
}

function apply() {
  const start = queryDate(from.value)
  const end = queryDate(to.value)
  if (!start || !end || end < start || (Date.parse(end) - Date.parse(start)) / 86_400_000 >= 31) {
    dateError.value = '请选择有效的记录日期范围，最多 31 天。'
    return
  }
  dateError.value = ''
  const target = { name: 'admin-model-calls', query: { from: start, to: end, model: model.value || undefined,
    outcome: outcome.value || undefined } }
  if (router.resolve(target).fullPath === route.fullPath) refresh.value++
  else void router.replace(target)
}

function reset() {
  from.value = hospitalDay(-6)
  to.value = hospitalDay()
  model.value = ''
  apply()
}

const integer = new Intl.NumberFormat('zh-CN')
const decimal = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 })
const percentage = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })
const count = (value: number) => integer.format(value)
const latency = (value: number | null) => value === null ? '—' : `${decimal.format(value)} ms`
const failureRate = (failed: number, total: number) => total ? percentage.format(failed / total) : '—'
const reasonName = (code: string | null) => code ? (reasons[code] ?? code) : '未记录原因'
const ceiling = computed(() => Math.max(1, ...(overview.value?.trend ?? []).map(day => day.succeeded + day.failed)))
const modelOptions = computed(() => new Map(overview.value?.options.map(option => [option.key, option])))

function modelLabel(key: string) {
  const option = modelOptions.value.get(key)
  return option ? observedModelLabel(option) : '当前筛选的模型版本'
}
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">AI 调用观测</h1>
    <button class="admin-button" type="button" :disabled="!overview && !overviewError" @click="refresh++">
      <RefreshCw :size="15" aria-hidden="true" /> 刷新统计
    </button>
  </header>

  <form class="admin-panel catalog-filter model-call-filter" aria-label="调用统计筛选" @submit.prevent="apply">
    <label>记录开始日期<input v-model="from" class="admin-input" type="date" required :max="to || undefined"></label>
    <label>记录结束日期<input v-model="to" class="admin-input" type="date" required :min="from || undefined"></label>
    <label>模型版本<select v-model="model" class="admin-input">
      <option value="">全部模型版本</option>
      <option v-if="model && !modelOptions.has(model)" :value="model">当前筛选的模型版本</option>
      <option v-for="item in overview?.options" :key="item.key" :value="item.key">{{ modelLabel(item.key) }}</option>
    </select></label>
    <div class="catalog-actions">
      <button class="admin-button admin-button--primary">查询</button>
      <button class="admin-button" type="button" @click="reset">最近 7 天</button>
    </div>
  </form>
  <p v-if="dateError" class="admin-form-error" role="alert">{{ dateError }}</p>

  <div class="model-call-workspace">
    <div v-if="overviewError" class="admin-panel admin-empty" role="alert">
      <template v-if="expired">管理员会话已失效，请 <RouterLink class="admin-text-link" :to="{ name: 'admin-login' }">重新登录</RouterLink>。</template>
      <template v-else>{{ overviewError }} <button class="admin-button" type="button" @click="refresh++">重试统计</button></template>
    </div>
    <p v-else-if="!overview" class="admin-panel admin-empty" role="status">正在读取模型调用统计…</p>
    <template v-else>
      <section class="admin-panel" aria-labelledby="call-summary">
        <div class="admin-panel-toolbar">
          <div><h2 id="call-summary">调用概览</h2><p class="admin-muted call-note">{{ applied.from }} 至 {{ applied.to }} · 北京时间</p></div>
        </div>
        <dl class="call-metrics">
          <div><dt>总尝试数</dt><dd>{{ count(overview.totals.attempts) }}</dd></div>
          <div><dt>失败率</dt><dd>{{ failureRate(overview.totals.failed, overview.totals.attempts) }}</dd></div>
          <div><dt>重试次数</dt><dd>{{ count(overview.totals.retries) }}</dd></div>
          <div><dt>平均耗时</dt><dd>{{ latency(overview.totals.averageMs) }}</dd></div>
          <div><dt>P95 耗时</dt><dd>{{ latency(overview.totals.p95Ms) }}</dd></div>
        </dl>
        <p class="admin-muted call-footnote">一次记录对应一次 Agent 模型判断尝试，重试单独计数。P95 表示约 95% 的已计时尝试在该耗时内完成。</p>
      </section>

      <p v-if="!overview.totals.attempts" class="admin-panel admin-empty">当前筛选范围暂无模型判断记录。</p>
      <template v-else>
        <div class="call-diagnostics">
          <section class="admin-panel" aria-labelledby="call-trend">
            <div class="admin-panel-toolbar call-trend-heading">
              <h2 id="call-trend">每日调用趋势</h2>
              <div class="call-legend"><span><i class="call-bar-success" aria-hidden="true"></i>成功</span>
                <span><i class="call-bar-failure" aria-hidden="true"></i>失败</span></div>
            </div>
            <div class="admin-table-scroll call-trend-scroll" role="region" aria-label="每日模型判断统计" tabindex="0">
              <table class="admin-table call-trend-table">
                <thead><tr><th scope="col">记录日期</th><th scope="col">尝试分布</th><th scope="col">成功</th><th scope="col">失败</th></tr></thead>
                <tbody><tr v-for="day in overview.trend" :key="day.date">
                  <th scope="row">{{ day.date }}</th>
                  <td><div class="call-bar" aria-hidden="true">
                    <i class="call-bar-success" :style="{ width: `${day.succeeded / ceiling * 100}%` }"></i>
                    <i class="call-bar-failure" :style="{ width: `${day.failed / ceiling * 100}%` }"></i>
                  </div></td>
                  <td>{{ count(day.succeeded) }}</td><td>{{ count(day.failed) }}</td>
                </tr></tbody>
              </table>
            </div>
          </section>

          <section class="admin-panel" aria-labelledby="call-reasons">
            <div class="admin-panel-toolbar"><h2 id="call-reasons">失败原因</h2></div>
            <p v-if="!overview.reasons.length" class="admin-empty">当前范围暂无失败记录。</p>
            <div v-else class="admin-table-scroll call-trend-scroll" role="region" aria-label="失败原因统计" tabindex="0">
              <table class="admin-table">
                <thead><tr><th scope="col">原因</th><th scope="col">失败次数</th></tr></thead>
                <tbody><tr v-for="reason in overview.reasons" :key="reason.code ?? ''">
                  <th scope="row">{{ reasonName(reason.code) }}<code v-if="reason.code && reasons[reason.code]">{{ reason.code }}</code></th>
                  <td>{{ count(reason.attempts) }}</td>
                </tr></tbody>
              </table>
            </div>
          </section>
        </div>

        <section class="admin-panel" aria-labelledby="call-versions">
          <div class="admin-panel-toolbar"><h2 id="call-versions">模型版本统计</h2></div>
          <div class="admin-table-scroll" role="region" aria-label="按模型版本统计" tabindex="0">
            <table class="admin-table call-version-table">
              <thead><tr><th scope="col">模型版本</th><th scope="col">尝试数</th><th scope="col">失败率</th>
                <th scope="col">重试次数</th><th scope="col">平均耗时</th><th scope="col">P95 耗时</th></tr></thead>
              <tbody><tr v-for="row in overview.models" :key="row.model.key">
                <th scope="row">{{ modelLabel(row.model.key) }}</th><td>{{ count(row.metrics.attempts) }}</td>
                <td>{{ failureRate(row.metrics.failed, row.metrics.attempts) }}</td><td>{{ count(row.metrics.retries) }}</td>
                <td>{{ latency(row.metrics.averageMs) }}</td><td>{{ latency(row.metrics.p95Ms) }}</td>
              </tr></tbody>
            </table>
          </div>
        </section>
      </template>
    </template>

    <section v-if="!expired" class="admin-panel" aria-labelledby="call-details">
      <div class="admin-panel-toolbar call-detail-toolbar">
        <h2 id="call-details">调用明细</h2>
        <label>结果 <select v-model="outcome" class="admin-input">
          <option value="">全部结果</option><option value="SUCCEEDED">成功</option><option value="FAILED">失败</option>
        </select></label>
      </div>
      <div v-if="detailError" class="admin-empty" role="alert">
        {{ detailError }} <button class="admin-button" type="button" @click="detailRefresh++">重试明细</button>
      </div>
      <p v-else-if="!detail" class="admin-empty" role="status">正在读取调用明细…</p>
      <p v-else-if="!detail.items.length" class="admin-empty">没有符合当前筛选条件的调用明细。</p>
      <template v-else>
        <div class="admin-table-scroll" role="region" aria-label="模型判断记录" tabindex="0">
          <table class="admin-table call-attempt-table">
            <thead><tr><th scope="col">记录时间</th><th scope="col">模型版本</th><th scope="col">尝试序号</th>
              <th scope="col">结果</th><th scope="col">失败原因</th><th scope="col">耗时</th><th scope="col">任务编号</th></tr></thead>
            <tbody><tr v-for="item in detail.items" :key="`${item.taskId}:${item.sequence}`">
              <th scope="row"><time :datetime="item.recordedAt">{{ clock.format(new Date(item.recordedAt)) }}</time></th>
              <td>{{ modelLabel(item.modelKey) }}</td><td>{{ item.attempt ?? '—' }}</td>
              <td :class="item.outcome === 'FAILED' ? 'call-failure' : 'call-success'">{{ outcomes[item.outcome] }}</td>
              <td>{{ item.outcome === 'FAILED' ? reasonName(item.reason) : '—' }}
                <code v-if="item.reason && reasons[item.reason]">{{ item.reason }}</code>
              </td>
              <td>{{ latency(item.durationMs) }}</td>
              <td><RouterLink class="admin-text-link call-task-link" :to="{ name: 'admin-agent-timeline', query: { taskId: item.taskId } }">
                {{ item.taskId }} <ArrowRight :size="14" aria-hidden="true" />
              </RouterLink></td>
            </tr></tbody>
          </table>
        </div>
        <div v-if="detail.nextBefore" class="call-page-actions">
          <span v-if="extraError" class="admin-form-error" role="alert">{{ extraError }}</span>
          <button class="admin-button" type="button" :disabled="loadingMore" @click="loadMore">
            {{ loadingMore ? '加载中…' : '加载更多明细' }}
          </button>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.model-call-filter { align-items: end; }
.model-call-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; margin-top: 16px; }
.call-note { margin: 3px 0 0; }
.call-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--admin-border); }
.call-metrics > div { padding: 12px 16px; border-inline-end: 1px solid var(--admin-border); }
.call-metrics > div:last-child { border-inline-end: 0; }
.call-metrics dt { color: var(--admin-muted); font-size: 13px; }
.call-metrics dd { margin: 3px 0 0; font-size: 22px; font-weight: 650; font-variant-numeric: tabular-nums; }
.call-footnote { margin: 0; padding: 11px 16px; border-top: 1px solid var(--admin-border); font-size: 13px; }
.call-diagnostics { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 16px; align-items: start; }
.call-trend-scroll { max-height: 328px; overflow-y: auto; }
.call-trend-scroll thead { position: sticky; top: 0; }
.call-trend-table { min-width: 305px; }
.call-trend-table td:nth-child(2) { width: 47%; min-width: 70px; }
.call-trend-heading { flex-wrap: wrap; }
.call-legend { display: flex; gap: 10px; color: var(--admin-muted); font-size: 12px; }
.call-legend i { display: inline-block; width: 9px; height: 9px; margin-inline-end: 4px; border-radius: 2px; }
.call-bar { display: flex; height: 10px; background: var(--admin-canvas); border-radius: 2px; overflow: hidden; }
.call-bar-success { background: #3d6caa; }
.call-bar-failure { background: #a24353; }
.call-version-table { min-width: 610px; }
.call-attempt-table { min-width: 815px; }
tbody th { background: inherit; color: var(--admin-ink); font-weight: 550; }
td, time { font-variant-numeric: tabular-nums; }
td code, th code { display: block; color: var(--admin-muted); overflow-wrap: anywhere; font-size: 11px; }
.call-detail-toolbar { flex-wrap: wrap; }
.call-detail-toolbar label { display: flex; gap: 8px; align-items: center; color: var(--admin-muted); }
.call-detail-toolbar select { width: 123px; }
.call-task-link { overflow-wrap: anywhere; }
.call-success { color: #226849; }
.call-failure { color: #9b3043; }
.call-page-actions { display: flex; align-items: center; justify-content: end; gap: 10px; padding: 10px 16px; }
@media (max-width: 920px) { .call-diagnostics { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 650px) {
  .call-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .call-metrics > div { border-bottom: 1px solid var(--admin-border); }
  .model-call-filter > label { flex: 1 1 135px; max-width: none; }
}
</style>
