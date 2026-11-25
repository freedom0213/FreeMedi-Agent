<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentSettingsError, checkInAppointment, readCatalogOptions, type AdminAppointmentRow, type CatalogOption } from '../api/admin'
import { channelLabel, formatHospitalDateTime } from '../api/portal'
import PaginationBar from '../components/PaginationBar.vue'
import AppointmentHistoryDialog from './AppointmentHistoryDialog.vue'
import { queryCatalogId, queryDate } from './hospitalCalendar'
import { useCatalogPage } from './useCatalogPage'

const route = useRoute()
const router = useRouter()

function routeFilters() {
  return {
    appointmentNo: typeof route.query.appointmentNo === 'string' ? route.query.appointmentNo.trim().slice(0, 64) : '',
    departmentId: queryCatalogId(route.query.departmentId),
    doctorId: queryCatalogId(route.query.doctorId),
    scheduleId: queryCatalogId(route.query.scheduleId),
    from: queryDate(route.query.from) || undefined,
    to: queryDate(route.query.to) || undefined,
    status: typeof route.query.status === 'string' && Object.hasOwn(statusLabels, route.query.status)
      ? route.query.status : undefined,
    channel: route.query.channel === 'PORTAL' || route.query.channel === 'AGENT' ? route.query.channel : undefined,
  }
}

const statusLabels: Record<string, string> = { BOOKED: '已预约', CHECKED_IN: '已签到', CANCELLED: '已取消',
  EXPIRED: '已过期', RESCHEDULED: '已改约' }
const form = ref(routeFilters())
const { items, filters, state, totalPages, reload, go, search } = useCatalogPage<AdminAppointmentRow>('appointments', form.value)
const departments = shallowRef<CatalogOption[]>([])
const doctors = shallowRef<CatalogOption[]>([])
const departmentOptionsFailed = ref(false)
const doctorOptionsFailed = ref(false)
const departmentOptionsRetry = ref(0)
const doctorOptionsRetry = ref(0)
const loadingDoctors = ref(false)
const formError = ref('')
const signing = shallowRef<AdminAppointmentRow['appointment']>()
const checkInBusy = ref(false)
const checkInNotice = ref('')
const checkInError = ref('')
const historyNumber = ref(route.query.operations === '1' ? form.value.appointmentNo : '')
watch(departmentOptionsRetry, (_, __, onCleanup) => {
  departmentOptionsFailed.value = false
  const request = new AbortController()
  onCleanup(() => request.abort())
  void readCatalogOptions('departments', request.signal).then(result => {
    if (!request.signal.aborted) departments.value = result
  }).catch(() => {
    if (!request.signal.aborted) departmentOptionsFailed.value = true
  })
}, { immediate: true })

watch([() => form.value.departmentId, doctorOptionsRetry], ([id], _, onCleanup) => {
  doctors.value = []
  doctorOptionsFailed.value = false
  loadingDoctors.value = id !== undefined
  if (id === undefined) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  void readCatalogOptions('doctors', request.signal, id).then(result => {
    if (!request.signal.aborted) doctors.value = result
  }).catch(() => {
    if (!request.signal.aborted) doctorOptionsFailed.value = true
  }).finally(() => {
    if (!request.signal.aborted) loadingDoctors.value = false
  })
}, { immediate: true })

watch(() => route.query, () => {
  form.value = routeFilters()
  historyNumber.value = route.query.operations === '1' ? form.value.appointmentNo : ''
  signing.value = undefined
  formError.value = ''
  search(form.value)
})

function inspectRelated(number: string) {
  void router.push({ name: 'admin-appointments', query: { appointmentNo: number, operations: '1' } })
}

function closeHistory() {
  historyNumber.value = ''
  if (route.query.operations === '1') {
    void router.replace({ name: 'admin-appointments', query: { ...route.query, operations: undefined } })
  }
}

function apply() {
  const query = { ...form.value, appointmentNo: form.value.appointmentNo.trim() || undefined }
  if (query.from && query.to && query.from > query.to) {
    formError.value = '就诊开始日期不能晚于结束日期。'
    return
  }
  formError.value = ''
  const target = { name: 'admin-appointments', query }
  if (router.resolve(target).fullPath === route.fullPath) search(query)
  else void router.replace(target)
}

function clearSchedule() {
  form.value.scheduleId = undefined
  apply()
}

function clearFilters() {
  const target = { name: 'admin-appointments' }
  if (router.resolve(target).fullPath === route.fullPath) {
    form.value = routeFilters()
    formError.value = ''
    search(form.value)
  } else void router.replace(target)
}

async function confirmCheckIn() {
  if (!signing.value || checkInBusy.value) return
  checkInBusy.value = true
  checkInError.value = ''
  try {
    const result = await checkInAppointment(signing.value.appointmentNo)
    checkInNotice.value = result.message
    signing.value = undefined
    reload()
  } catch (failure) {
    checkInError.value = agentSettingsError(failure)
    reload()
  } finally {
    checkInBusy.value = false
  }
}

const bookedAt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short',
})
</script>

<template>
  <h1 class="admin-page-title">预约明细</h1>
  <section class="admin-panel" aria-label="预约明细">
    <form class="catalog-filter appointment-filters" aria-label="预约明细筛选" @submit.prevent="apply">
      <label>预约编号<input v-model="form.appointmentNo" class="admin-input" type="search" maxlength="64" placeholder="输入完整预约编号"></label>
      <label>就诊开始日期<input v-model="form.from" class="admin-input" type="date" :max="form.to"></label>
      <label>就诊结束日期<input v-model="form.to" class="admin-input" type="date" :min="form.from"></label>
      <div class="appointment-filter-option">
        <div class="appointment-filter-control">
          <label for="appointment-filter-department">科室</label>
          <select id="appointment-filter-department" v-model="form.departmentId" class="admin-input" @change="form.doctorId = undefined">
            <option :value="undefined">全部科室</option>
            <option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </div>
        <div v-if="departmentOptionsFailed" class="admin-form-error option-error" role="alert">
          科室选项加载失败。<button class="admin-button" type="button" @click="departmentOptionsRetry++">重试</button>
        </div>
      </div>
      <div class="appointment-filter-option">
        <div class="appointment-filter-control">
          <label for="appointment-filter-doctor">医生</label>
          <select id="appointment-filter-doctor" v-model="form.doctorId" class="admin-input"
            :disabled="form.departmentId === undefined || loadingDoctors || doctorOptionsFailed">
            <option :value="undefined">{{ form.departmentId === undefined ? '请先选择科室' : loadingDoctors ? '正在加载医生…' : doctorOptionsFailed ? '医生选项不可用' : '全部医生' }}</option>
            <option v-for="item in doctors" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
        </div>
        <div v-if="doctorOptionsFailed" class="admin-form-error option-error" role="alert">
          医生选项加载失败。<button class="admin-button" type="button" @click="doctorOptionsRetry++">重试</button>
        </div>
      </div>
      <label>预约渠道<select v-model="form.channel" class="admin-input">
        <option :value="undefined">全部渠道</option><option value="PORTAL">自助挂号</option><option value="AGENT">智能导诊</option>
      </select></label>
      <label>预约状态<select v-model="form.status" class="admin-input">
        <option :value="undefined">全部状态</option>
        <option v-for="(label, value) in statusLabels" :key="value" :value="value">{{ label }}</option>
      </select></label>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" type="submit">查询</button>
        <button class="admin-button" type="button" @click="clearFilters">重置</button>
        <button class="admin-button" type="button" @click="reload">刷新记录</button>
      </div>
    </form>
    <p v-if="formError" class="admin-form-error catalog-message" role="alert">{{ formError }}</p>
    <div v-if="filters.scheduleId" class="catalog-message catalog-actions">
      <span>正在查看排班 {{ filters.scheduleId }} 的预约</span>
      <button class="admin-button" type="button" @click="clearSchedule">清除排班筛选</button>
    </div>
    <p v-if="checkInNotice" class="admin-form-hint catalog-message" role="status">{{ checkInNotice }}</p>
    <p v-if="checkInError" class="admin-form-error catalog-message" role="alert">{{ checkInError }}</p>
    <div v-if="signing" class="catalog-message check-in-confirmation" role="group" aria-label="核对签到预约">
      <p><strong>确认患者已到院？</strong> {{ signing.appointmentNo }} · {{ signing.departmentName }} ·
        {{ signing.doctorName }} · {{ signing.appointmentDate }} {{ signing.timeRange }}</p>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" type="button" :disabled="checkInBusy" @click="confirmCheckIn">
          {{ checkInBusy ? '正在签到…' : '确认签到' }}
        </button>
        <button class="admin-button" type="button" :disabled="checkInBusy" @click="signing = undefined">返回</button>
      </div>
    </div>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取预约记录…</p>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      管理员会话已失效。<RouterLink :to="{ name: 'admin-login' }">重新登录</RouterLink>
    </div>
    <div v-else-if="state === 'failed'" class="admin-empty" role="alert">
      预约记录加载失败。<button class="admin-button" type="button" @click="reload">重新加载</button>
    </div>
    <p v-else-if="!items.length" class="admin-empty">没有符合条件的预约记录。</p>
    <div v-else class="admin-table-scroll" tabindex="0" role="region" aria-label="预约记录表">
      <table class="admin-table catalog-table" aria-label="预约记录">
        <thead><tr>
          <th scope="col">预约编号</th><th scope="col">就诊日期与时段</th><th scope="col">科室与医生</th>
          <th scope="col">预约渠道</th><th scope="col">预约状态</th><th scope="col">提交时间</th><th scope="col">到院操作</th>
        </tr></thead>
        <tbody>
          <tr v-for="{ appointment } in items" :key="appointment.appointmentNo">
            <th scope="row" class="appointment-id">
              {{ appointment.appointmentNo }}
              <button class="admin-text-link appointment-history-link" type="button"
                @click="historyNumber = appointment.appointmentNo">操作记录</button>
            </th>
            <td>{{ appointment.appointmentDate }}<small>{{ appointment.timeRange }}</small></td>
            <td>{{ appointment.departmentName }}<small>{{ appointment.doctorName }}</small></td>
            <td>{{ channelLabel(appointment.bookedVia) }}</td>
            <td><span class="catalog-status" :data-active="appointment.status === 'BOOKED'">{{ statusLabels[appointment.status] ?? appointment.status }}</span></td>
            <td>{{ bookedAt.format(new Date(appointment.bookedAt)) }}</td>
            <td>
              <button v-if="appointment.canCheckIn" class="admin-button" type="button" :disabled="checkInBusy"
                @click="signing = appointment; checkInNotice = ''; checkInError = ''">办理签到</button>
              <small v-else-if="appointment.checkedInAt">签到 {{ formatHospitalDateTime(appointment.checkedInAt) }}</small>
              <small v-else-if="appointment.cancelledAt">取消 {{ formatHospitalDateTime(appointment.cancelledAt) }}</small>
              <small v-else-if="appointment.status === 'EXPIRED'">签到已截止</small>
              <small v-else-if="appointment.status === 'BOOKED' && appointment.checkInOpensAt">签到自 {{ formatHospitalDateTime(appointment.checkInOpensAt) }} 开放</small>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <PaginationBar v-if="state === 'ready' && (totalPages ?? 0) > 1" label="预约明细分页" :page="filters.page"
      :total-pages="totalPages" @change="go" />
    <AppointmentHistoryDialog v-if="historyNumber" :key="historyNumber" :appointment-no="historyNumber"
      @close="closeHistory" @inspect="inspectRelated" />
  </section>
</template>

<style scoped>
.appointment-filters { align-items: start; }
.appointment-filters > .catalog-actions { margin-top: calc(1.55em + 4px); }
.appointment-filter-option { flex: 1 1 145px; max-width: 260px; min-width: 0; }
.appointment-filter-control { display: grid; gap: 4px; }
.option-error { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 6px; }
.check-in-confirmation { background: #eef5fc; border: 1px solid var(--admin-border); border-radius: 8px; padding: 10px 13px; }
.check-in-confirmation p { margin: 0 0 8px; overflow-wrap: anywhere; }
.appointment-id { overflow-wrap: anywhere; max-width: 235px; }
.appointment-history-link { display: block; padding: 0; margin-top: 4px; border: 0; background: none; text-align: left; cursor: pointer; }
</style>
