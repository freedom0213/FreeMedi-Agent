<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch, watchEffect } from 'vue'
import { ArrowRight, RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import {
  adminHttpStatus, agentSettingsError, readAppointmentOperations, readCatalogOptions,
  type AppointmentCounts, type AppointmentOperations, type CatalogOption,
} from '../api/admin'
import { hospitalDay, queryCatalogId, queryDate } from './hospitalCalendar'

const route = useRoute()
const router = useRouter()
const from = ref('')
const to = ref('')
const department = ref<number | ''>('')
const problem = ref('')
const optionProblem = ref('')
const view = ref<'departments' | 'doctors'>('departments')
const data = shallowRef<AppointmentOperations>()
const options = shallowRef<CatalogOption[]>([])
const state = ref<'loading' | 'ready' | 'error' | 'session'>('loading')
const failure = ref('')
const attempt = ref(0)
const optionRequest = new AbortController()
onUnmounted(() => optionRequest.abort())
void readCatalogOptions('departments', optionRequest.signal)
  .then(value => { if (!optionRequest.signal.aborted) options.value = value })
  .catch(error => { if (!optionRequest.signal.aborted) optionProblem.value = agentSettingsError(error) })

const applied = computed(() => ({
  from: queryDate(route.query.from) || hospitalDay(),
  to: queryDate(route.query.to) || hospitalDay(13),
  departmentId: queryCatalogId(route.query.departmentId),
}))

watch(applied, filters => {
  from.value = filters.from
  to.value = filters.to
  department.value = filters.departmentId ?? ''
  problem.value = ''
}, { immediate: true })

watchEffect(onCleanup => {
  void attempt.value
  const params = applied.value
  const request = new AbortController()
  onCleanup(() => request.abort())
  state.value = 'loading'
  failure.value = ''
  void readAppointmentOperations(params, request.signal).then(result => {
    if (request.signal.aborted) return
    data.value = result
    state.value = 'ready'
  }).catch(error => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    failure.value = status === 400 ? agentSettingsError(error) : '统计加载失败，请重试。'
    state.value = status === 401 || status === 403 ? 'session' : 'error'
  })
})

const metrics: { key: keyof AppointmentCounts; label: string }[] = [
  { key: 'booked', label: '有效预约' }, { key: 'portal', label: '普通挂号' }, { key: 'agent', label: '智能导诊' },
  { key: 'remaining', label: '当前可预约余号' }, { key: 'occupied', label: '已占用号源' },
]
const rows = computed(() => view.value === 'doctors'
  ? data.value?.doctors.map(row => ({ ...row, name: row.doctorName })) ?? []
  : data.value?.departments.map(row => ({ ...row, name: row.departmentName, doctorId: undefined })) ?? [])
const ceiling = computed(() => Math.max(1, ...(data.value?.trend ?? []).map(day => day.booked)))
const labelEvery = computed(() => Math.max(1, Math.ceil((data.value?.trend.length ?? 0) / 7)))
const dateText = (date: string) => date.slice(5).replace('-', '/')
const number = new Intl.NumberFormat('zh-CN')
const format = (value: number) => number.format(value)
const barHeight = (value: number) => value / ceiling.value * 100 + '%'

function apply() {
  const first = queryDate(from.value)
  const last = queryDate(to.value)
  if (!first || !last || last < first || (Date.parse(last) - Date.parse(first)) / 86_400_000 >= 90) {
    problem.value = '请选择有效的出诊日期范围，最多 90 天。'
    return
  }
  problem.value = ''
  const query = { from: first, to: last, departmentId: department.value || undefined }
  const target = { name: 'admin-visit-operations', query }
  if (router.resolve(target).fullPath === route.fullPath) attempt.value++
  else void router.replace(target)
}

function showComingDays() {
  from.value = hospitalDay()
  to.value = hospitalDay(13)
  department.value = ''
  apply()
}

function scheduleLink(departmentId?: number, doctorId?: number, date?: string) {
  return { name: 'admin-schedules',
    query: { departmentId, doctorId, from: date ?? applied.value.from, to: date ?? applied.value.to } }
}
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">就诊运营看板</h1>
    <button class="admin-button" type="button" :disabled="state === 'loading'" @click="attempt++">
      <RefreshCw :size="15" aria-hidden="true" /> 刷新统计
    </button>
  </header>
  <form class="admin-panel catalog-filter visit-filter" aria-label="运营统计筛选" @submit.prevent="apply">
    <label>出诊开始日期<input v-model="from" class="admin-input" type="date" required :max="to || undefined"></label>
    <label>出诊结束日期<input v-model="to" class="admin-input" type="date" required :min="from || undefined"></label>
    <label>科室<select v-model="department" class="admin-input">
      <option value="">全部科室</option>
      <option v-for="item in options" :key="item.id" :value="item.id">{{ item.name }}</option>
    </select></label>
    <div class="catalog-actions">
      <button class="admin-button admin-button--primary">查询</button>
      <button class="admin-button" type="button" @click="showComingDays">未来 14 天</button>
    </div>
  </form>
  <p v-if="problem" class="admin-form-error visit-message" role="alert">{{ problem }}</p>
  <p v-if="optionProblem" class="admin-form-error visit-message" role="alert">科室选项加载失败。{{ optionProblem }}</p>
  <p v-if="state === 'loading'" class="admin-panel admin-empty visit-message" role="status">正在读取预约和号源统计…</p>
  <div v-else-if="state === 'session'" class="admin-panel admin-empty visit-message" role="alert">
    管理员会话已失效。<RouterLink :to="{ name: 'admin-login', query: { next: route.fullPath } }">重新登录</RouterLink>
  </div>
  <div v-else-if="state === 'error'" class="admin-panel admin-empty visit-message" role="alert">
    {{ failure }} <button class="admin-button" type="button" @click="attempt++">重试</button>
  </div>
  <div v-else-if="data" class="visit-workspace">
    <section class="admin-panel" aria-labelledby="visit-summary">
      <header class="admin-panel-toolbar">
        <div><h2 id="visit-summary">预约与号源概览</h2>
          <p class="admin-muted visit-caption">{{ data.from }} 至 {{ data.to }} · 按北京时间出诊日期统计</p>
        </div>
        <RouterLink class="admin-text-link" :to="scheduleLink(applied.departmentId)">查看对应排班 <ArrowRight :size="15" aria-hidden="true" /></RouterLink>
      </header>
      <dl class="visit-metrics">
        <div v-for="metric in metrics" :key="metric.key"><dt>{{ metric.label }}</dt><dd>{{ format(data.totals[metric.key]) }}</dd></div>
      </dl>
      <p class="admin-muted visit-note">预约数只计有效预约。余号只计当前仍开放且尚未开始的可预约号源。</p>
    </section>

    <section class="admin-panel" aria-labelledby="visit-trend">
      <header class="admin-panel-toolbar">
        <div><h2 id="visit-trend">每日预约趋势</h2><p class="admin-muted visit-caption">比较各出诊日的预约量与预约渠道。</p></div>
        <div class="visit-legend" aria-label="预约渠道图例">
          <span><i class="visit-channel-portal" aria-hidden="true"></i> 普通挂号</span>
          <span><i class="visit-channel-agent" aria-hidden="true"></i> 智能导诊</span>
        </div>
      </header>
      <p v-if="data.totals.booked === 0" class="admin-empty">这个日期范围目前没有有效预约。</p>
      <div v-else class="visit-chart-scroll" aria-hidden="true">
        <div class="visit-chart" :style="{ gridTemplateColumns: 'repeat(' + data.trend.length + ', minmax(38px, 1fr))' }">
          <div v-for="(day, index) in data.trend" :key="day.date" class="visit-day"
            :title="day.date + ' · 普通挂号 ' + day.portal + ' · 智能导诊 ' + day.agent">
            <div class="visit-bar-track">
              <div class="visit-channel-agent" :style="{ height: barHeight(day.agent) }"></div>
              <div class="visit-channel-portal" :style="{ height: barHeight(day.portal) }"></div>
            </div>
            <span>{{ index % labelEvery === 0 || index === data.trend.length - 1 ? dateText(day.date) : '' }}</span>
          </div>
        </div>
      </div>
      <details class="visit-trend-data">
        <summary>查看每日数据</summary>
        <div class="admin-table-scroll" role="region" aria-label="每日预约数据表" tabindex="0">
          <table class="admin-table visit-daily-table">
            <thead><tr><th scope="col">出诊日期</th><th scope="col">预约数</th><th scope="col">普通挂号</th>
              <th scope="col">智能导诊</th><th scope="col">排班</th></tr></thead>
            <tbody><tr v-for="day in data.trend" :key="day.date">
              <th scope="row">{{ day.date }}</th><td>{{ format(day.booked) }}</td><td>{{ format(day.portal) }}</td><td>{{ format(day.agent) }}</td>
              <td><RouterLink class="admin-text-link" :to="scheduleLink(applied.departmentId, undefined, day.date)"
                :aria-label="'查看 ' + day.date + ' 的排班'">查看</RouterLink></td>
            </tr></tbody>
          </table>
        </div>
      </details>
    </section>

    <section class="admin-panel" aria-labelledby="visit-breakdown">
      <header class="admin-panel-toolbar visit-breakdown-header">
        <h2 id="visit-breakdown">科室与医生明细</h2>
        <div class="catalog-actions" role="group" aria-label="统计维度">
          <button class="admin-button" type="button" :aria-pressed="view === 'departments'" @click="view = 'departments'">按科室</button>
          <button class="admin-button" type="button" :aria-pressed="view === 'doctors'" @click="view = 'doctors'">按医生</button>
        </div>
      </header>
      <p v-if="!data.departments.length" class="admin-empty">该范围暂无排班。</p>
      <div v-else class="admin-table-scroll" role="region" aria-label="科室或医生预约与号源明细" tabindex="0">
        <table class="admin-table catalog-table">
          <thead><tr><th scope="col">{{ view === 'departments' ? '科室' : '医生' }}</th><th scope="col">预约数</th>
            <th scope="col">普通挂号</th><th scope="col">智能导诊</th><th scope="col">可预约余号</th>
            <th scope="col">已占用号源</th><th scope="col">排班</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.doctorId ? 'doctor-' + row.doctorId : 'department-' + row.departmentId">
            <th scope="row">{{ row.name }}
              <small v-if="row.doctorId">{{ row.departmentName }}</small>
            </th>
            <td v-for="metric in metrics" :key="metric.key">{{ format(row.counts[metric.key]) }}</td>
            <td><RouterLink class="admin-text-link" :to="scheduleLink(row.departmentId, row.doctorId)"
              :aria-label="'查看' + row.name + '的排班'">查看排班</RouterLink></td>
          </tr></tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.visit-filter { align-items: end; }
.visit-message, .visit-workspace { margin-top: 16px; }
.visit-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.admin-panel-toolbar { flex-wrap: wrap; }
.visit-caption { margin: 3px 0 0; }
.visit-note { margin: 0; padding: 10px 16px; border-top: 1px solid var(--admin-border); }
.visit-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 0; border-top: 1px solid var(--admin-border); }
.visit-metrics > div { padding: 13px 16px; border-inline-end: 1px solid var(--admin-border); }
.visit-metrics > div:last-child { border-inline-end: 0; }
.visit-metrics dt { color: var(--admin-muted); font-size: 13px; }
.visit-metrics dd { margin: 2px 0 0; font-size: 23px; font-weight: 650; font-variant-numeric: tabular-nums; }
.visit-legend { display: flex; flex-wrap: wrap; gap: 12px; color: var(--admin-muted); font-size: 13px; }
.visit-legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; }
.visit-channel-portal { background: #366fc1; }
.visit-channel-agent { background: #287c67; }
.visit-chart-scroll { overflow-x: auto; margin: 0 16px 12px; }
.visit-chart { display: grid; gap: 5px; }
.visit-day { min-width: 0; text-align: center; color: var(--admin-muted); font-size: 11px; }
.visit-bar-track { display: flex; flex-direction: column; justify-content: flex-end; align-items: center;
  margin-bottom: 5px; height: 138px; border-bottom: 1px solid var(--admin-border); }
.visit-bar-track > div { width: min(70%, 32px); flex: none; }
.visit-trend-data { border-top: 1px solid var(--admin-border); }
.visit-trend-data summary { padding: 12px 16px; color: var(--admin-blue-deep); cursor: pointer; }
.visit-daily-table { min-width: 530px; font-variant-numeric: tabular-nums; }
.visit-daily-table tbody th { background: inherit; color: var(--admin-ink); font-weight: 600; }
.visit-breakdown-header button[aria-pressed=true] { border-color: #a8c0e6; background: #eaf2ff; color: var(--admin-blue-deep); }
@media (max-width: 650px) {
  .visit-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .visit-metrics > div { border-bottom: 1px solid var(--admin-border); }
  .visit-filter > label { flex: 1 1 135px; max-width: none; }
}
</style>
