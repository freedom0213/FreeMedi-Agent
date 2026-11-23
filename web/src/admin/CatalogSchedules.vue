<script setup lang="ts">
import { computed, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  agentSettingsError, closeSchedule, createSchedule, readCatalogOptions, readScheduleDetail,
  type CatalogOption, type ClinicPeriod, type ScheduleDetail, type ScheduleRow,
} from '../api/admin'
import { useCatalogPage } from './useCatalogPage'
import AdminPagination from './AdminPagination.vue'
import { hospitalDay as localDate, queryCatalogId, queryDate } from './hospitalCalendar'

const route = useRoute()
const router = useRouter()
function routeFilters() {
  return { departmentId: queryCatalogId(route.query.departmentId), doctorId: queryCatalogId(route.query.doctorId),
    from: queryDate(route.query.from) || undefined, to: queryDate(route.query.to) || undefined,
    status: route.query.status === 'OPEN' || route.query.status === 'CLOSED' ? route.query.status : undefined }
}
const initial = routeFilters()
const { items, filters, hasNext, totalPages, state, reload, go, search } = useCatalogPage<ScheduleRow>('schedules', initial)
const departmentId = ref<number | ''>(initial.departmentId ?? '')
const doctorId = ref<number | ''>(initial.doctorId ?? '')
const from = ref(initial.from ?? '')
const to = ref(initial.to ?? '')
const status = ref(initial.status ?? '')
const departments = shallowRef<CatalogOption[]>([])
const filterDoctors = shallowRef<CatalogOption[]>([])
const draftDoctors = shallowRef<CatalogOption[]>([])
const optionError = ref('')
const optionRequest = new AbortController()
onUnmounted(() => optionRequest.abort())

void readCatalogOptions('departments', optionRequest.signal)
  .then(result => { departments.value = result })
  .catch(error => { if (!optionRequest.signal.aborted) optionError.value = agentSettingsError(error) })

const busy = ref(false)
const feedback = ref('')
const problem = ref('')
const closing = ref<ScheduleRow>()
const detailId = ref<number>()
const detail = shallowRef<ScheduleDetail>()
const detailError = ref('')
const detailAttempt = ref(0)
const draft = ref<{ departmentId: number | ''; doctorId: number | ''; date: string;
  period: ClinicPeriod; start: string; minutes: number; count: number }>()
const draftDepartment = computed(() => draft.value?.departmentId)
const periods: Record<ClinicPeriod, { label: string; min: number; end: number; defaultTime: string }> = {
  MORNING: { label: '上午', min: 0, end: 720, defaultTime: '09:00' },
  AFTERNOON: { label: '下午', min: 720, end: 1080, defaultTime: '14:00' },
  EVENING: { label: '晚间', min: 1080, end: 1439, defaultTime: '19:00' },
}
const slotStatus = { AVAILABLE: '可预约', RESERVED: '已占用', CLOSED: '已关闭' }
const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const time = (value: string) => timeFormat.format(new Date(value))
const asTime = (minute: number) => [Math.floor(minute / 60), minute % 60].map(value => String(value).padStart(2, '0')).join(':')

const preview = computed(() => {
  const value = draft.value
  if (!value || !/^\d{2}:\d{2}$/.test(value.start) || !Number.isInteger(value.minutes) || !Number.isInteger(value.count)
      || value.minutes < 1 || value.minutes > 120 || value.count < 1 || value.count > 50) return []
  const [hour = 0, minute = 0] = value.start.split(':').map(Number)
  const first = hour * 60 + minute
  const period = periods[value.period]
  if (first < period.min || first + value.minutes * value.count > period.end) return []
  return Array.from({ length: value.count }, (_, index) => {
    const begin = first + index * value.minutes
    return asTime(begin) + '–' + asTime(begin + value.minutes)
  })
})

watch(departmentId, (id, previous, onCleanup) => {
  if (previous !== undefined) doctorId.value = ''
  filterDoctors.value = []
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  void readCatalogOptions('doctors', request.signal, id).then(result => {
    if (!request.signal.aborted) filterDoctors.value = result
  }).catch(error => { if (!request.signal.aborted) optionError.value = agentSettingsError(error) })
}, { flush: 'sync', immediate: true })

watch(() => route.query, () => {
  const next = routeFilters()
  departmentId.value = next.departmentId ?? ''
  doctorId.value = next.doctorId ?? ''
  from.value = next.from ?? ''
  to.value = next.to ?? ''
  status.value = next.status ?? ''
  search(next)
})

watch(draftDepartment, (id, _, onCleanup) => {
  if (draft.value) draft.value.doctorId = ''
  draftDoctors.value = []
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  void readCatalogOptions('doctors', request.signal, id).then(result => {
    if (!request.signal.aborted) draftDoctors.value = result.filter(doctor => doctor.status === 'ACTIVE')
  }).catch(error => { if (!request.signal.aborted) optionError.value = agentSettingsError(error) })
})

watch([detailId, detailAttempt], ([id], _, onCleanup) => {
  detailError.value = ''
  detail.value = undefined
  if (!id) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  void readScheduleDetail(id, request.signal).then(result => {
    if (!request.signal.aborted) detail.value = result
  }).catch(error => { if (!request.signal.aborted) detailError.value = agentSettingsError(error) })
})

function applyFilters() {
  const query = { departmentId: departmentId.value || undefined, doctorId: doctorId.value || undefined,
    from: from.value || undefined, to: to.value || undefined, status: status.value || undefined }
  const target = { name: 'admin-schedules', query }
  if (router.resolve(target).fullPath === route.fullPath) search(query)
  else void router.replace(target)
}

function clearFilters() {
  departmentId.value = ''
  doctorId.value = ''
  from.value = ''
  to.value = ''
  status.value = ''
  applyFilters()
}

function beginSchedule() {
  feedback.value = ''
  problem.value = ''
  closing.value = undefined
  draft.value = { departmentId: '', doctorId: '', date: localDate(1), period: 'MORNING', start: '09:00', minutes: 15, count: 4 }
}

async function save() {
  const input = draft.value
  if (!input?.departmentId || !input.doctorId || !preview.value.length || busy.value) return
  const data = { ...input, departmentId: input.departmentId, doctorId: input.doctorId }
  busy.value = true
  problem.value = ''
  try {
    await createSchedule(data)
    feedback.value = '排班已开放，并生成 ' + data.count + ' 个号源。'
    draft.value = undefined
    departmentId.value = ''
    doctorId.value = ''
    from.value = data.date
    to.value = data.date
    status.value = ''
    applyFilters()
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    busy.value = false
  }
}

function requestClose(row: ScheduleRow) {
  closing.value = row
  draft.value = undefined
  feedback.value = ''
  problem.value = ''
}

async function confirmClose() {
  const row = closing.value
  if (!row || busy.value) return
  busy.value = true
  problem.value = ''
  try {
    await closeSchedule(row.id)
    feedback.value = '排班已关闭，患者将无法预约该排班的号源。'
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    closing.value = undefined
    busy.value = false
    reload()
    if (detailId.value === row.id) detailAttempt.value += 1
  }
}
</script>

<template>
  <h1 class="admin-page-title">排班管理</h1>
  <section class="admin-panel" aria-label="排班列表">
    <form class="catalog-filter" @submit.prevent="applyFilters">
      <label>科室<select v-model="departmentId" class="admin-input">
        <option value="">全部科室</option>
        <option v-for="option in departments" :key="option.id" :value="option.id">{{ option.name }}</option>
      </select></label>
      <label>医生<select v-model="doctorId" class="admin-input" :disabled="!departmentId">
        <option value="">全部医生</option>
        <option v-for="option in filterDoctors" :key="option.id" :value="option.id">{{ option.name }}</option>
      </select></label>
      <label>出诊开始日期<input v-model="from" class="admin-input" type="date" :max="to || undefined"></label>
      <label>出诊结束日期<input v-model="to" class="admin-input" type="date" :min="from || undefined"></label>
      <label>状态<select v-model="status" class="admin-input">
        <option value="">全部状态</option><option value="OPEN">开放</option><option value="CLOSED">已关闭</option>
      </select></label>
      <div class="catalog-actions">
        <button class="admin-button">查询</button>
        <button class="admin-button" type="button" @click="clearFilters">重置</button>
      </div>
      <button class="admin-button admin-button--primary catalog-create" type="button" :disabled="busy" @click="beginSchedule">新增排班</button>
    </form>
    <p v-if="optionError" class="admin-form-error catalog-message" role="alert">筛选选项加载失败。{{ optionError }}</p>
    <p v-if="feedback" class="admin-form-hint catalog-message" role="status">{{ feedback }}</p>
    <p v-if="problem" class="admin-form-error catalog-message" role="alert">{{ problem }}</p>
    <form v-if="draft" class="catalog-editor" @submit.prevent="save">
      <h2>新增排班与号源</h2>
      <p class="admin-muted">时间按北京时间填写，号源日期须在患者可查的未来 90 天内。</p>
      <fieldset class="catalog-fields" :disabled="busy">
        <label>排班科室<select v-model="draft.departmentId" class="admin-input" required>
          <option value="" disabled>选择科室</option>
          <option v-for="option in departments.filter(item => item.status === 'ACTIVE')" :key="option.id" :value="option.id">{{ option.name }}</option>
        </select></label>
        <label>出诊医生<select v-model="draft.doctorId" class="admin-input" required :disabled="!draft.departmentId">
          <option value="" disabled>{{ draft.departmentId ? '选择医生' : '先选择科室' }}</option>
          <option v-for="option in draftDoctors" :key="option.id" :value="option.id">{{ option.name }}</option>
        </select></label>
        <label>排班日期<input v-model="draft.date" class="admin-input" type="date" required :min="localDate(0)" :max="localDate(90)"></label>
        <label>出诊时段<select v-model="draft.period" class="admin-input" @change="draft.start = periods[draft.period].defaultTime">
          <option v-for="(period, key) in periods" :key="key" :value="key">{{ period.label }}</option>
        </select></label>
        <label>开始时间<input v-model="draft.start" class="admin-input" type="time" required></label>
        <label>每号时长（分钟）<input v-model.number="draft.minutes" class="admin-input" type="number" required min="1" max="120"></label>
        <label>号源数量<input v-model.number="draft.count" class="admin-input" type="number" required min="1" max="50"></label>
      </fieldset>
      <details v-if="preview.length" class="catalog-preview" open>
        <summary>号源预览 · {{ preview.length }} 个</summary>
        <ol><li v-for="slot in preview" :key="slot">{{ slot }}</li></ol>
      </details>
      <p v-else class="admin-form-hint">请填写有效的时长和数量，并将所有号源安排在所选时段内。</p>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" :disabled="busy || !preview.length">{{ busy ? '正在保存…' : '保存并开放号源' }}</button>
        <button class="admin-button" type="button" :disabled="busy" @click="draft = undefined; problem = ''">取消</button>
      </div>
    </form>
    <section v-if="closing" class="catalog-editor" aria-label="关闭排班确认">
      <h2>关闭排班</h2>
      <p>{{ closing.doctorName }} · {{ closing.scheduleDate }} {{ periods[closing.clinicPeriod].label }}。
        当前余号 {{ closing.remaining }} 个。关闭后患者无法再预约，系统会重新核对有效预约。</p>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" type="button" :disabled="busy" @click="confirmClose">确认关闭</button>
        <button class="admin-button" type="button" :disabled="busy" @click="closing = undefined">取消</button>
      </div>
    </section>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取排班…</p>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      管理员会话已失效。<RouterLink :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
    </div>
    <div v-else-if="state === 'failed'" class="admin-empty" role="alert">
      排班加载失败。<button class="admin-button" type="button" @click="reload">重新加载</button>
    </div>
    <p v-else-if="!items.length" class="admin-empty">没有符合条件的排班。</p>
    <div v-else class="admin-table-scroll">
      <table class="admin-table catalog-table" aria-label="排班记录">
        <thead><tr>
          <th scope="col">医生 / 科室</th><th scope="col">日期 / 时段</th><th scope="col">总号源</th><th scope="col">余号</th>
          <th scope="col">有效预约</th><th scope="col">排班状态</th><th scope="col">操作</th>
        </tr></thead>
        <tbody>
          <template v-for="row in items" :key="row.id">
            <tr>
              <th scope="row">{{ row.doctorName }}<small>{{ row.departmentName }}</small></th>
              <td>{{ row.scheduleDate }}<small>{{ periods[row.clinicPeriod].label }}</small></td>
              <td>{{ row.slots }}</td><td>{{ row.remaining }}</td><td>{{ row.booked }}</td>
              <td><span class="catalog-status" :data-active="row.status === 'OPEN'">{{ row.status === 'OPEN' ? '开放' : '已关闭' }}</span></td>
              <td><div class="catalog-actions">
                <button class="admin-button" type="button" :aria-expanded="detailId === row.id"
                  @click="detailId = detailId === row.id ? undefined : row.id">{{ detailId === row.id ? '收起号源' : '查看号源' }}</button>
                <button v-if="row.status === 'OPEN' && !row.booked" class="admin-button" type="button" :disabled="busy"
                  @click="requestClose(row)">关闭排班</button>
              </div><small v-if="row.status === 'OPEN' && row.booked">已有 {{ row.booked }} 笔有效预约，无法关闭</small></td>
            </tr>
            <tr v-if="detailId === row.id" class="catalog-slot-row"><td colspan="7">
              <p v-if="detailError" role="alert">{{ detailError }}
                <button class="admin-button" type="button" @click="detailAttempt++">重新加载</button>
              </p>
              <p v-else-if="!detail" role="status">正在读取号源…</p>
              <p v-else-if="!detail.slots.length">此排班暂无号源。</p>
              <ul v-else class="catalog-slot-list">
                <li v-for="slot in detail.slots" :key="slot.number">{{ slot.number }} 号 · {{ time(slot.startAt) }}–{{ time(slot.endAt) }}
                  <span>{{ slotStatus[slot.status] }}</span>
                </li>
              </ul>
            </td></tr>
          </template>
        </tbody>
      </table>
    </div>
    <AdminPagination v-if="state === 'ready' && (filters.page > 1 || hasNext || (totalPages ?? 0) > 1)"
      :page="filters.page" :total-pages="totalPages" :has-next="hasNext" @change="go" />
  </section>
</template>
