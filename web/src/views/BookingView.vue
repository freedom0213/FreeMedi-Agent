<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowLeft, ArrowRight, Brain, CalendarDays, Check, ChevronRight, CircleCheck, CircleHelp,
  Heart, LoaderCircle, RefreshCw, Stethoscope, UsersRound,
} from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import PaginationBar from '../components/PaginationBar.vue'
import consultationArt from '../assets/home-consultation.webp'
import { patientSession } from '../api/auth'
import { errorMessage } from '../api/http'
import {
  bookSlot, cancellationNotice, checkPortalBooking, formatAppointmentDate, listDepartments, listDoctors,
  getAppointment, periodLabel, rescheduleAppointment, searchSlots, slotLabel,
} from '../api/portal'
import type { AppointmentRecord, AvailableSlot, BookingResult, Department, Doctor } from '../types/portal'
import { readPending, rescheduleSubmissionKey, writePending } from '../stores/pending'
import lungsIcon from '../assets/department-lungs.png'
import stomachIcon from '../assets/department-stomach.png'
import boneIcon from '../assets/department-bone.png'

interface PortalIntent {
  slot: AvailableSlot
  key: string
  date: string
  period: string
  doctorId: number | null
}

const props = defineProps<{ originalNumber?: string }>()
const route = useRoute()
const patientId = patientSession.value!.patientId
const pendingStorageKey = props.originalNumber
  ? rescheduleSubmissionKey(patientId, props.originalNumber)
  : `freemedi-portal-submission:${patientId}`
const pending = ref(readPending<PortalIntent>(pendingStorageKey))
const canRetry = ref(false)
const pendingNotice = ref('')
const original = ref<AppointmentRecord | null>(null)
const originalError = ref('')
const loadingOriginal = ref(!!props.originalNumber)
const originalDetail = computed(() => ({ name: 'appointments', query: {
  appointmentNo: props.originalNumber, upcomingPage: route.query.upcomingPage, historyPage: route.query.historyPage,
} }))
const originalUnavailable = computed(() => !!props.originalNumber && !pending.value && !booking.value?.success
  && (!original.value || !original.value.canCancel || original.value.status !== 'BOOKED'))

const departments = ref<Department[]>([])
const doctors = ref<Doctor[]>([])
const slots = ref<AvailableSlot[]>([])
const departmentId = ref<number | null>(null)
const doctorId = ref<number | null>(null)
const appointmentDate = ref('')
const clinicPeriod = ref('')
const selectedSlot = ref<AvailableSlot | null>(null)
const idempotencyKey = ref('')
const booking = ref<BookingResult | null>(null)
const searchStartDate = ref('')
const searchEndDate = ref('')
const slotPage = ref(1)
const totalPages = ref(0)
const totalSlots = ref(0)
const slotPageSize = 12
const outsideWindow = computed(() => appointmentDate.value && searchEndDate.value
  && (appointmentDate.value < searchStartDate.value || appointmentDate.value > searchEndDate.value))
const departmentError = ref('')
const doctorError = ref('')
const slotError = ref('')
const departmentHeading = ref<HTMLElement | null>(null)
const filterHeading = ref<HTMLElement | null>(null)
const reviewHeading = ref<HTMLElement | null>(null)
const busy = reactive({ departments: false, doctors: false, slots: false, submitting: false })
let doctorRequestId = 0
let slotRequestId = 0

const periods = [
  { value: '', label: '不限' },
  { value: 'MORNING', label: '上午' },
  { value: 'AFTERNOON', label: '下午' },
  { value: 'EVENING', label: '晚上' },
]
const departmentIcons: Partial<Record<string, string>> = {
  RESPIRATORY: lungsIcon,
  GASTROENTEROLOGY: stomachIcon,
  ORTHOPEDICS: boneIcon,
}

const selectedDepartment = computed(() => departments.value.find((item) => item.id === departmentId.value))
const selectedDoctor = computed(() => doctors.value.find((item) => item.id === doctorId.value))
const writeLocked = computed(() => busy.submitting || !!pending.value)
const stage = computed(() => pending.value || selectedSlot.value || booking.value?.success ? 3
  : departmentId.value !== null ? 2 : 1)
const canSubmit = computed(() => !!selectedSlot.value
  && !busy.departments && !busy.slots && !writeLocked.value)
const groupedSlots = computed(() => {
  const dates = new Map<string, AvailableSlot[]>()
  for (const slot of slots.value) {
    const group = dates.get(slot.appointmentDate) ?? []
    group.push(slot)
    dates.set(slot.appointmentDate, group)
  }
  return [...dates.entries()].sort(([left], [right]) => left.localeCompare(right))
})

onMounted(() => props.originalNumber ? loadOriginal() : loadDepartments())

async function loadOriginal() {
  if (!props.originalNumber) return
  loadingOriginal.value = true
  originalError.value = ''
  try {
    original.value = await getAppointment(props.originalNumber)
    departmentId.value = original.value.departmentId
    doctorId.value = original.value.doctorId
    if (pending.value) {
      appointmentDate.value = pending.value.date
      clinicPeriod.value = pending.value.period
    } else if (!originalUnavailable.value) await loadSlots()
  } catch (failure) {
    originalError.value = errorMessage(failure)
  } finally {
    loadingOriginal.value = false
  }
}

async function loadDepartments() {
  busy.departments = true
  departmentError.value = ''
  try {
    departments.value = await listDepartments()
    if (pending.value) {
      const original = pending.value
      departmentId.value = original.slot.departmentId
      doctorId.value = original.doctorId
      appointmentDate.value = original.date
      clinicPeriod.value = original.period
      void loadDoctors()
    } else {
      const requested = Number(route.query.departmentId)
      const initial = departments.value.find((item) => item.id === requested)
      if (initial) void chooseDepartment(initial)
    }
  } catch (failure) {
    departmentError.value = errorMessage(failure)
  } finally {
    busy.departments = false
  }
}

async function chooseDepartment(department: Department) {
  if (writeLocked.value) return
  ++slotRequestId
  busy.slots = false
  departmentId.value = department.id
  doctorId.value = null
  doctors.value = []
  slots.value = []
  clearSelection()
  slotPage.value = 1

  void loadDoctors()
  void loadSlots()
  await nextTick()
  filterHeading.value?.focus()
}

async function loadDoctors() {
  const id = departmentId.value
  if (id === null || busy.doctors) return
  const requestId = ++doctorRequestId
  busy.doctors = true
  doctorError.value = ''
  try {
    const incoming = await listDoctors(id)
    if (requestId === doctorRequestId) doctors.value = incoming
  } catch (failure) {
    if (requestId === doctorRequestId) doctorError.value = errorMessage(failure)
  } finally {
    if (requestId === doctorRequestId) busy.doctors = false
  }
}

async function chooseDoctor(id: number | null) {
  if (writeLocked.value) return
  doctorId.value = id
  await changeFilters()
}

function changeSlotPage(page: number) {
  slotPage.value = page
  void loadSlots()
}

async function loadSlots() {
  if (departmentId.value === null) return
  const requestId = ++slotRequestId
  busy.slots = true
  slotError.value = ''
  try {
    const response = await searchSlots({
      departmentId: departmentId.value,
      doctorId: doctorId.value ?? undefined,
      date: appointmentDate.value || undefined,
      period: clinicPeriod.value || undefined,
      page: slotPage.value,
      limit: slotPageSize,
    })
    if (requestId !== slotRequestId) return
    slots.value = response.slots
    searchStartDate.value = response.searchStartDate ?? ''
    searchEndDate.value = response.searchEndDate
    slotPage.value = response.page ?? 1
    totalPages.value = response.totalPages ?? 1
    totalSlots.value = response.totalCount ?? response.count
    if (selectedSlot.value) {
      const refreshed = response.slots.find((slot) => slot.slotId === selectedSlot.value?.slotId)
      if (refreshed) selectedSlot.value = refreshed
      else clearSelectionFields()
    }
  } catch (failure) {
    if (requestId !== slotRequestId) return
    slotError.value = errorMessage(failure)
    slots.value = []
    clearSelectionFields()
  } finally {
    if (requestId === slotRequestId) busy.slots = false
  }
}

async function changeFilters() {
  if (writeLocked.value) return
  clearSelection()
  slotPage.value = 1
  slots.value = []
  await loadSlots()
}

async function resetFilters() {
  if (writeLocked.value) return
  doctorId.value = original.value?.doctorId ?? null
  appointmentDate.value = ''
  clinicPeriod.value = ''
  await changeFilters()
  filterHeading.value?.focus()
}

async function selectSlot(slot: AvailableSlot) {
  if (busy.slots || writeLocked.value) return
  selectedSlot.value = slot
  // One key per chosen slot: a retry of the same intent must not create a second appointment.
  idempotencyKey.value = crypto.randomUUID()
  booking.value = null
  slotError.value = ''
  await nextTick()
  reviewHeading.value?.focus()
}

function clearSelection() {
  selectedSlot.value = null
  idempotencyKey.value = ''
  booking.value = null
}

async function submit() {
  const slot = selectedSlot.value
  if (!slot || !canSubmit.value) return
  setPending({ slot, key: idempotencyKey.value, date: appointmentDate.value,
    period: clinicPeriod.value, doctorId: doctorId.value })
  await runSubmission('submit')
}

function setPending(intent: PortalIntent | null) {
  pending.value = intent
  writePending(pendingStorageKey, intent)
}

async function runSubmission(action: 'submit' | 'check' | 'retry') {
  const original = pending.value
  if (!original || busy.submitting || (action === 'retry' && !canRetry.value)) return
  busy.submitting = true
  canRetry.value = false
  slotError.value = ''
  try {
    const result = props.originalNumber
      ? await rescheduleAppointment(props.originalNumber, original.slot.slotId, original.key, action === 'check')
      : action === 'check'
      ? await checkPortalBooking(original.slot.slotId, original.key)
      : await bookSlot(original.slot.slotId, original.key)
    if (result.code === 'NOT_FOUND' && result.outcome === null) {
      pendingNotice.value = result.message
      canRetry.value = true
      return
    }
    booking.value = result
    setPending(null)
    pendingNotice.value = ''
    clearSelectionFields()
    if (props.originalNumber && (result.code === 'ORIGINAL_UNAVAILABLE' || result.code === 'RESCHEDULE_CUTOFF_PASSED' || result.code === 'NOT_FOUND')) {
      await loadOriginal()
    } else if (!props.originalNumber || !result.success) await loadSlots()
  } catch {
    pendingNotice.value = props.originalNumber ? '提交结果暂未确定，请先核验这次改约。' : '提交结果暂未确定，请先核验原预约。'
  } finally {
    busy.submitting = false
  }
}

/** Keeps the outcome visible while dropping the consumed selection. */
function clearSelectionFields() {
  selectedSlot.value = null
  idempotencyKey.value = ''
}

async function returnToSlots() {
  if (writeLocked.value) return
  clearSelection()
  await nextTick()
  filterHeading.value?.focus()
}

async function restart() {
  if (writeLocked.value) return
  ++doctorRequestId
  ++slotRequestId
  busy.doctors = false
  busy.slots = false
  departmentId.value = null
  doctorId.value = null
  doctors.value = []
  slots.value = []
  appointmentDate.value = ''
  clinicPeriod.value = ''
  clearSelection()
  departmentError.value = ''
  doctorError.value = ''
  slotError.value = ''
  await nextTick()
  departmentHeading.value?.focus()
}
</script>

<template>
  <AppShell class="booking-frame">
    <div class="booking-page page-wrap">
      <RouterLink v-if="originalNumber" class="booking-text reschedule-back" :to="originalDetail">
        <ArrowLeft :size="16" aria-hidden="true" /> 返回原预约详情
      </RouterLink>
      <header class="booking-header">
        <h1>{{ originalNumber ? '改约' : '预约挂号' }}</h1>
        <ol v-if="!loadingOriginal && !originalUnavailable" class="booking-steps" :aria-label="originalNumber ? '改约进度' : '挂号进度'">
          <li v-for="(label, index) in (originalNumber ? ['原预约', '选择新时段', '确认改约'] : ['选科室', '选医生和时间', '确认预约'])" :key="label"
            :aria-current="stage === index + 1 ? 'step' : undefined">
            <span aria-hidden="true"><Check v-if="stage > index + 1" :size="12" /><template v-else>{{ index + 1 }}</template></span>
            {{ label }}
          </li>
        </ol>
      </header>

      <p v-if="loadingOriginal" class="booking-note" role="status">正在读取原预约…</p>
      <div v-else-if="originalError" class="booking-note booking-error" role="alert">
        <p>原预约读取失败。{{ originalError }}</p>
        <button class="button-secondary" type="button" @click="loadOriginal">重新读取</button>
      </div>
      <p v-else-if="originalUnavailable" class="booking-note booking-error" role="alert">
        {{ booking?.message || '原预约当前不可改约，请返回详情核对状态。' }}
      </p>
      <div v-else class="booking-layout" :class="{ 'choose-department-layout': stage === 1 }">
        <div class="booking-work">
          <section v-if="stage === 1" aria-labelledby="booking-departments-title">
            <h2 id="booking-departments-title" ref="departmentHeading" tabindex="-1">选择就诊科室</h2>
            <p v-if="busy.departments" class="booking-note" role="status">
              <LoaderCircle class="spin" :size="16" />正在读取科室…
            </p>
            <div v-else-if="departments.length" class="department-grid">
              <button v-for="department in departments" :key="department.id" class="department-choice"
                :data-code="department.code" type="button" @click="chooseDepartment(department)">
                <span class="department-icon" aria-hidden="true">
                  <span v-if="departmentIcons[department.code]" class="department-art"
                    :style="{ '--department-image': 'url(' + departmentIcons[department.code] + ')' }" />
                  <Heart v-else-if="department.code === 'CARDIOLOGY'" :size="21" :stroke-width="1.6" />
                  <Brain v-else-if="department.code === 'NEUROLOGY'" :size="21" :stroke-width="1.6" />
                  <Stethoscope v-else :size="21" :stroke-width="1.6" />
                </span>
                <span class="choice-copy"><strong>{{ department.name }}</strong><small v-if="department.description">{{ department.description }}</small></span>
                <ChevronRight class="department-arrow" :size="16" aria-hidden="true" />
              </button>
            </div>
            <p v-else-if="!departmentError" class="booking-note" role="status">暂时没有可预约的科室。</p>
            <div v-if="departmentError" class="booking-note booking-error" role="alert">
              <p>{{ departmentError }}</p>
              <button class="booking-text" type="button" @click="loadDepartments">重新读取科室</button>
            </div>
          </section>

          <section v-else-if="stage === 2" aria-labelledby="booking-filters-title">
            <header class="booking-section-heading">
              <div class="booking-filter-title">
                <button v-if="!original" class="button-secondary change-department" type="button" @click="restart">
                  <ArrowLeft :size="16" aria-hidden="true" /> 更换科室
                </button>
                <h2 id="booking-filters-title" ref="filterHeading" tabindex="-1">{{ original ? '选择新的就诊时间' : '筛选医生和时间' }}</h2>
              </div>
              <div class="selected-department"><strong>{{ original ? original.departmentName + ' · ' + original.doctorName : selectedDepartment?.name }}</strong></div>
            </header>
            <div class="booking-filters">
              <h3 v-if="!original" id="booking-doctors-label">医生</h3>
              <p v-if="!original && busy.doctors" class="booking-note" role="status"><LoaderCircle class="spin" :size="16" />正在读取医生…</p>
              <div v-else-if="!original" class="doctor-grid" role="group" aria-labelledby="booking-doctors-label">
                <button type="button" class="doctor-choice" :aria-pressed="doctorId === null" @click="chooseDoctor(null)">
                  <span class="doctor-avatar" aria-hidden="true"><UsersRound :size="17" /></span>
                  <span class="choice-copy"><strong>不限医生</strong><small>查看该科室全部号源</small></span>
                  <Check v-if="doctorId === null" class="doctor-check" :size="15" aria-hidden="true" />
                </button>
                <button v-for="doctor in doctors" :key="doctor.id" type="button" class="doctor-choice"
                  :aria-pressed="doctorId === doctor.id" @click="chooseDoctor(doctor.id)">
                  <span class="doctor-avatar" aria-hidden="true">{{ doctor.name.slice(0, 1) }}</span>
                  <span class="choice-copy"><strong>{{ doctor.name }}</strong><small v-if="doctor.title">{{ doctor.title }}</small></span>
                  <Check v-if="doctorId === doctor.id" class="doctor-check" :size="15" aria-hidden="true" />
                </button>
              </div>
              <div v-if="doctorError" class="booking-note booking-error booking-doctor-error">
                <p role="alert"><CircleHelp :size="17" aria-hidden="true" />医生列表加载失败。{{ doctorError }}</p>
                <button class="button-secondary" type="button" @click="loadDoctors">重新加载医生</button>
              </div>
              <div class="booking-time-filters">
                <div>
                  <label for="booking-date">就诊日期</label>
                  <div class="date-control">
                    <input id="booking-date" v-model="appointmentDate" type="date" :min="searchStartDate || undefined" :max="searchEndDate || undefined"
                      @change="changeFilters" />
                    <button type="button" class="booking-pill" :aria-pressed="!appointmentDate"
                      @click="appointmentDate = ''; changeFilters()">不限日期</button>
                  </div>
                </div>
                <div role="group" aria-labelledby="booking-periods-label">
                  <span id="booking-periods-label">就诊时段</span>
                  <div class="periods">
                    <button v-for="period in periods" :key="period.value" class="booking-pill" type="button"
                      :aria-pressed="period.value === clinicPeriod"
                      @click="clinicPeriod = period.value; changeFilters()">{{ period.label }}</button>
                  </div>
                </div>
              </div>
              <p v-if="searchEndDate" class="date-limit">
                <CalendarDays :size="15" aria-hidden="true" />
                <span>可查至 <strong>{{ formatAppointmentDate(searchEndDate) }}</strong>。之后的排班和号源未纳入本次查询。</span>
              </p>
            </div>

            <div v-if="booking && !booking.success" class="booking-note booking-error" role="alert">
              <strong>{{ originalNumber ? '改约未成功' : booking.code === 'APPOINTMENT_CANCELLED' ? '原预约已取消' : '预约未提交' }}</strong><p v-if="booking.code !== 'APPOINTMENT_CANCELLED'">{{ booking.message }}</p>
              <RouterLink v-if="!originalNumber && booking.code === 'APPOINTMENT_RESCHEDULED'"
                :to="{ name: 'appointments', query: { appointmentNo: booking.appointmentNo } }">查看原预约详情</RouterLink>
            </div>
            <section class="booking-results" aria-labelledby="booking-results-title">
              <header class="booking-section-heading">
                <div class="result-title">
                  <h3 id="booking-results-title">可预约时间</h3>
                  <span v-if="!busy.slots && !slotError" role="status">查询范围内共 {{ totalSlots }} 个号源</span>
                </div>
                <button class="booking-text" type="button" :disabled="busy.slots" @click="loadSlots">
                  <RefreshCw :size="15" aria-hidden="true" />重新查询
                </button>
              </header>
              <p v-if="busy.slots" class="booking-note" role="status">
                <LoaderCircle :size="16" class="spin" />正在查询号源…
              </p>
              <p v-else-if="slotError" class="booking-note booking-error" role="alert"><CircleHelp :size="17" />{{ slotError }}</p>
              <div v-else-if="!groupedSlots.length" class="booking-note booking-empty">
                <p role="status">{{ outsideWindow ? '所选日期超出查询范围，请选择范围内的日期。' : original
                  ? '该医生在当前筛选范围内暂无可换时段，可以更换日期或就诊时段。' : '当前条件下暂无号源，可以换一个日期、时段或医生。' }}</p>
                <button v-if="(!original && doctorId !== null) || appointmentDate || clinicPeriod" class="booking-text" type="button"
                  @click="resetFilters">清除筛选</button>
              </div>
              <div v-else class="booking-slot-groups">
                <section v-for="[date, daySlots] in groupedSlots" :key="date" class="booking-day">
                  <h4>{{ formatAppointmentDate(date) }}</h4>
                  <ul>
                    <li v-for="slot in daySlots" :key="slot.slotId" class="booking-slot">
                      <div class="choice-copy"><strong>{{ slot.timeRange }}</strong><small>{{ periodLabel(slot.clinicPeriod) }}</small></div>
                      <div class="choice-copy"><strong>{{ slot.doctorName }}</strong><small v-if="slot.doctorTitle">{{ slot.doctorTitle }}</small></div>
                      <button class="button-secondary" type="button" :disabled="writeLocked || busy.departments"
                        @click="selectSlot(slot)">选此号源 <ChevronRight :size="15" aria-hidden="true" /></button>
                    </li>
                  </ul>
                </section>
              </div>
              <PaginationBar v-if="!slotError && totalPages > 1" class="booking-pagination" label="号源分页"
                :page="slotPage" :total-pages="totalPages" :busy="busy.slots" @change="changeSlotPage" />
            </section>
          </section>

          <section v-else aria-labelledby="booking-review-title">
            <h2 id="booking-review-title" ref="reviewHeading" tabindex="-1">
              {{ originalNumber ? (booking?.success ? '改约结果' : pending ? '核验改约结果' : '核对改约信息')
                : (booking?.success ? '预约结果' : pending ? '原预约核验' : '核对预约信息') }}
            </h2>
            <div v-if="pending" class="booking-review-message" aria-label="原预约核验状态">
              <strong>{{ slotLabel(pending.slot) }}</strong>
              <p role="status">{{ busy.submitting ? (originalNumber ? '正在处理改约…' : '正在处理预约…') : pendingNotice || '请查询这次提交的结果。' }}</p>
              <div class="booking-actions">
                <button class="button-secondary" type="button" :disabled="busy.submitting" @click="runSubmission('check')">{{ originalNumber ? '核验这次改约' : '核验原预约' }}</button>
                <button v-if="canRetry" class="button-primary" type="button" :disabled="busy.submitting" @click="runSubmission('retry')">重试原提交</button>
              </div>
            </div>
            <div v-else-if="booking?.success" class="booking-review-message booking-success" role="status">
              <CircleCheck :size="22" aria-hidden="true" />
              <div>
                <strong>{{ originalNumber ? '改约成功' : booking.outcome === 'EXISTING' ? '该预约已存在' : '预约已创建' }}</strong>
                <p>预约编号 {{ booking.appointmentNo }}</p>
                <div class="booking-actions">
                  <RouterLink class="button-primary appointment-result-link"
                     :to="{ name: 'appointments', query: { ...originalDetail.query, appointmentNo: booking.appointmentNo } }">
                    {{ originalNumber ? '查看新预约' : '查看原预约' }} <ArrowRight :size="16" aria-hidden="true" />
                  </RouterLink>
                  <button v-if="!originalNumber" class="booking-text" type="button" :disabled="writeLocked" @click="restart">预约另一笔</button>
                </div>
              </div>
            </div>
            <article v-else-if="selectedSlot" class="booking-receipt">
              <header>
                <div><p>{{ original ? '新时段 · ' : '' }}{{ formatAppointmentDate(selectedSlot.appointmentDate) }}</p><strong>{{ selectedSlot.timeRange }}</strong></div>
                <span>待你确认</span>
              </header>
              <dl>
                <div v-if="original"><dt>原时段</dt><dd>{{ formatAppointmentDate(original.appointmentDate) }} {{ original.timeRange }}</dd></div>
                <div><dt>科室</dt><dd>{{ selectedSlot.departmentName }}</dd></div>
                <div><dt>医生</dt><dd>{{ selectedSlot.doctorName }}<template v-if="selectedSlot.doctorTitle"> · {{ selectedSlot.doctorTitle }}</template></dd></div>
              </dl>
              <p v-if="selectedSlot.cancellationDeadline" class="booking-deadline">{{ cancellationNotice(selectedSlot.cancellationDeadline) }}</p>
              <div class="booking-actions receipt-actions">
                <button class="button-primary" type="button" :disabled="!canSubmit" @click="submit">
                  {{ originalNumber ? '确认改约' : '确认预约' }} <ArrowRight :size="16" aria-hidden="true" />
                </button>
                <button class="booking-text" type="button" @click="returnToSlots">
                  <ArrowLeft :size="16" aria-hidden="true" />返回换号
                </button>
              </div>
            </article>
          </section>
        </div>

        <aside v-if="original" class="booking-summary" aria-label="原预约">
          <h2>原预约</h2>
          <dl>
            <div><dt>科室</dt><dd>{{ original.departmentName }}</dd></div>
            <div><dt>医生</dt><dd>{{ original.doctorName }}</dd></div>
            <div><dt>日期</dt><dd>{{ formatAppointmentDate(original.appointmentDate) }}</dd></div>
            <div><dt>时段</dt><dd>{{ original.timeRange }}</dd></div>
          </dl>
          <p v-if="!booking?.success">新时段确认成功后，原预约才会变更。</p>
        </aside>
        <aside v-else-if="stage === 1" class="booking-guide" aria-labelledby="booking-guide-title">
          <img :src="consultationArt" alt="" width="1536" height="1024" />
          <div>
            <h2 id="booking-guide-title">不确定挂哪个科？</h2>
            <p>可以先描述不适和方便就诊的时间，由智能导诊帮你选择就诊方向。</p>
            <RouterLink class="button-secondary" :to="{ name: 'guide' }">
              前往智能导诊 <ArrowRight :size="16" aria-hidden="true" />
            </RouterLink>
          </div>
        </aside>
        <aside v-else class="booking-summary" aria-label="本次挂号">
          <h2>本次挂号</h2>
          <div class="booking-next">
            <ArrowRight :size="17" aria-hidden="true" />
            <div><span>下一步</span><strong>{{ booking?.success ? '查看预约记录' : pending ? '核验原预约'
              : selectedSlot ? '核对并确认预约' : stage === 2 ? '选择一个就诊时间' : '选择就诊科室' }}</strong></div>
          </div>
          <dl v-if="stage === 2">
            <div><dt>医生</dt><dd>{{ selectedDoctor?.name || (doctorId === null ? '不限医生' : '已指定医生') }}</dd></div>
            <div><dt>日期</dt><dd>{{ appointmentDate ? formatAppointmentDate(appointmentDate) : '不限日期' }}</dd></div>
            <div><dt>时段</dt><dd>{{ clinicPeriod ? periodLabel(clinicPeriod) : '不限时段' }}</dd></div>
          </dl>
        </aside>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.booking-frame {
  min-width: 0;
  color: #233651;
  background: radial-gradient(ellipse at 85% 10%, #f1ebf4, transparent 58%), linear-gradient(140deg, #dce7f1, #e5e7f0 77%);
}
.booking-frame :deep(.site-header), .booking-frame :deep(.site-footer) { background: #f8fafc; border-color: #ccdbe8; }
.booking-frame :deep(.site-footer) { margin-top: 0; }
.booking-page { padding-block: clamp(21px, 4vh, 46px); }
.reschedule-back { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.booking-header, .booking-section-heading, .result-title, .selected-department, .booking-actions { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
.booking-header, .booking-section-heading { justify-content: space-between; }
.booking-header { margin-bottom: 16px; }
.booking-header h1 { margin: 0; font-size: clamp(27px, 3vw, 38px); letter-spacing: -.025em; }
.booking-steps { display: flex; align-items: center; gap: 6px 14px; padding: 0; margin: 0; list-style: none; color: var(--brand-muted); font-size: 13px; flex-wrap: wrap; }
.booking-steps li { display: flex; align-items: center; gap: 5px; }
.booking-steps li > span { display: grid; place-items: center; width: 21px; height: 21px; border-radius: 50%; background: #fff; color: var(--brand-plum); }
.booking-steps [aria-current] { color: var(--brand-plum); font-weight: 700; }
.booking-steps [aria-current] > span { background: var(--brand-plum); color: #fff; }
.booking-layout { display: grid; grid-template-columns: minmax(0, 1fr) clamp(210px, 22vw, 273px); gap: 14px; align-items: start; }
.choose-department-layout { grid-template-columns: minmax(0, 1fr) minmax(250px, 32%); align-items: stretch; gap: 18px; }
.choose-department-layout .booking-work { padding: clamp(19px, 3vw, 32px); }
.choose-department-layout .department-choice { min-height: clamp(72px, 9vh, 94px); padding: 12px; }
.choose-department-layout .department-icon { width: 43px; height: 43px; }
.booking-guide { display: flex; flex-direction: column; overflow: hidden; min-width: 0; border: 1px solid #cadeed; border-radius: 16px; background: #eaf3fa; }
.booking-guide img { display: block; width: 100%; height: clamp(132px, 20vh, 210px); object-fit: cover; object-position: 67% 12%; }
.booking-guide > div { padding: clamp(15px, 2vw, 23px); }
.booking-guide h2 { margin: 0; font-size: 20px; }
.booking-guide p { margin: 7px 0 14px; font-size: 14px; color: #3b5574; }
.booking-filter-title { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 13px; }
.change-department { min-height: 40px; border-color: #cfc0d7; background: #f5eef8; }
.booking-pagination { --pagination-border: #ddcfe4; --pagination-ink: var(--brand-ink); --pagination-active: var(--brand-plum);
  --pagination-muted: var(--brand-muted); margin-top: 14px; bottom: max(8px, env(safe-area-inset-bottom)); }
.booking-work, .booking-summary { min-width: 0; border: 1px solid #dce4ee; border-radius: 16px; }
.booking-work { padding: clamp(14px, 2vw, 24px); background: var(--brand-paper); box-shadow: 0 8px 20px #283f5b12; }
.booking-summary { padding: 15px; background: #f8fafd; }
.booking-work h2, .booking-summary h2 { margin: 0; font-size: 20px; }
.booking-summary h2 { font-size: 16px; }
.department-grid, .doctor-grid { display: grid; gap: 9px; }
.department-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 14px; }
.doctor-grid { grid-template-columns: repeat(auto-fit, minmax(min(100%, 145px), 1fr)); }
.department-choice, .doctor-choice {
  display: flex; align-items: center; gap: 10px; min-width: 0;
  border: 1px solid #dce5ee; border-radius: 10px; padding: 9px;
  background: #fff; text-align: left; cursor: pointer;
}
.department-choice { min-height: 65px; }
.department-choice:hover, .doctor-choice:hover { background: #f8fbfd; border-color: #a8bdd2; }
.doctor-choice[aria-pressed="true"] { background: var(--brand-lilac); border-color: #9277a0; }
.department-icon, .doctor-avatar { display: grid; place-items: center; flex: none; width: 35px; height: 35px; border-radius: 9px; color: #244f95; background: #e1eefb; }
.department-art { width: 83%; height: 83%; background: currentColor; mask: var(--department-image) center / contain no-repeat; }
.department-choice[data-code="CARDIOLOGY"] .department-icon { color: #794833; background: var(--brand-peach); }
.department-choice[data-code="NEUROLOGY"] .department-icon { color: #2c6350; background: #e4f0e7; }
.department-choice[data-code="GASTROENTEROLOGY"] .department-icon, .doctor-avatar { color: var(--brand-plum); background: var(--brand-lilac); }
.department-arrow, .doctor-check { margin-left: auto; flex: none; color: var(--brand-plum); }
.choice-copy { display: grid; min-width: 0; gap: 2px; overflow-wrap: anywhere; }
.choice-copy strong { font-size: 14px; }
.choice-copy small, .date-limit, .result-title > span { color: var(--brand-muted); font-size: 12px; line-height: 1.5; }
.selected-department strong { padding: 3px 7px; border-radius: 6px; color: #244f95; background: #e8f1f9; font-size: 13px; }
.booking-text { display: inline-flex; align-items: center; gap: 5px; min-height: 35px; padding: 0; border: 0; background: transparent; color: var(--brand-plum); font-size: 13px; font-weight: 700; cursor: pointer; }
.booking-text:hover:not(:disabled) { text-decoration: underline; text-underline-offset: 3px; }
.booking-text:disabled { opacity: .55; cursor: not-allowed; }
.booking-filters { margin: 11px 0 17px; padding: 12px; border: 1px solid #dce7f1; border-radius: 11px; background: #eff5fb; }
.booking-filters h3, .booking-time-filters label, .booking-time-filters [id="booking-periods-label"] { display: block; margin: 0 0 6px; font-size: 13px; font-weight: 700; }
.booking-time-filters { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 10px 16px; margin-top: 12px; }
.date-control, .periods { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.date-control input { width: 145px; max-width: 100%; min-height: 38px; padding: 5px 8px; border: 1px solid #dce5ee; border-radius: 7px; background: white; color: var(--brand-ink); }
.booking-pill { min-height: 38px; padding-inline: 9px; border: 1px solid #dce5ee; border-radius: 7px; background: #fff; color: var(--brand-plum); font-size: 13px; cursor: pointer; }
.booking-pill[aria-pressed="true"] { color: white; background: var(--brand-plum); border-color: var(--brand-plum); }
.booking-pill:disabled { opacity: .55; cursor: not-allowed; }
.date-limit { display: flex; align-items: start; gap: 6px; margin: 10px 0 0; }
.date-limit svg { flex: none; margin-top: 2px; }
.result-title h3 { margin: 0; font-size: 16px; }
.booking-slot-groups, .booking-day ul { display: grid; gap: 8px; }
.booking-day h4 { margin: 4px 0 6px; color: #385479; font-size: 13px; }
.booking-day ul { margin: 0; padding: 0; list-style: none; }
.booking-slot { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 8px; align-items: center; padding: 8px; border: 1px solid #e0e6ef; border-radius: 9px; background: #fff; }
.booking-slot .button-secondary { white-space: nowrap; padding-inline: 9px; }
.booking-slot > div:first-child, .booking-receipt header strong { font-variant-numeric: tabular-nums; }
.booking-note, .booking-review-message { padding: 11px 13px; border-radius: 8px; background: #eff5fb; color: #374e6a; font-size: 14px; }
.booking-note { display: flex; align-items: start; gap: 7px; margin: 8px 0 0; }
.booking-note svg, .booking-review-message svg { flex: none; }
.booking-note p, .booking-review-message p { margin: 0; }
.booking-error { color: #863c39; background: #fff2ef; }
.booking-doctor-error { align-items: center; justify-content: space-between; flex-wrap: wrap; }
.booking-doctor-error p { display: flex; align-items: flex-start; flex: 1 1 200px; gap: 7px; overflow-wrap: anywhere; }
.booking-empty { align-items: center; justify-content: space-between; flex-wrap: wrap; }
.booking-receipt { margin-top: 12px; overflow: hidden; border: 1px solid var(--brand-line); border-radius: 13px; background: #fff; }
.booking-receipt header { display: flex; justify-content: space-between; align-items: start; gap: 7px; padding: 15px; background: linear-gradient(110deg, #eee5f3, #f8e9e5); color: var(--brand-plum); }
.booking-receipt header p { margin: 0 0 4px; font-size: 14px; font-weight: 700; }
.booking-receipt header strong { font-size: clamp(25px, 3vw, 35px); line-height: 1.1; }
.booking-receipt header > span { padding: 3px 8px; border-radius: 14px; background: var(--brand-paper); white-space: nowrap; font-size: 12px; }
.booking-receipt dl, .booking-summary dl { margin: 0; }
.booking-receipt dl { display: flex; flex-wrap: wrap; gap: 9px 22px; padding: 11px 15px; }
.booking-receipt dt, .booking-summary dt { color: var(--brand-muted); font-size: 12px; }
.booking-receipt dd, .booking-summary dd { margin: 0; font-size: 14px; }
.booking-deadline { margin: 0; padding: 0 15px 10px; color: var(--brand-muted); font-size: 13px; }
.receipt-actions { padding: 9px 15px; border-top: 1px solid var(--brand-line); }
.booking-review-message { display: grid; gap: 7px; margin-top: 12px; }
.booking-review-message .booking-actions { margin-top: 5px; }
.booking-success { display: flex; align-items: start; gap: 10px; background: #e4f1ed; color: #244d40; }
.booking-success p { margin-top: 5px; }
.booking-next { display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 9px 10px; border-radius: 9px; background: #e4f1ed; color: #244d40; }
.booking-next svg { flex: none; }
.booking-next > div { display: grid; }
.booking-next span { font-size: 12px; }
.booking-next strong { font-size: 14px; }
.booking-summary dl { display: grid; gap: 7px; margin-top: 12px; }
.booking-summary dl div { display: flex; justify-content: space-between; gap: 6px; }

@media (min-width: 721px) {
  .booking-frame:has(.choose-department-layout) :deep(.patient-page-content) { display: grid; align-content: safe center; }
}

@media (max-width: 720px) {
  .booking-page { width: calc(100% - 24px); padding-block: 14px; }
  .booking-layout { grid-template-columns: minmax(0, 1fr); }
  .booking-summary { display: none; }
  .booking-guide { flex-direction: row; align-items: center; }
  .booking-guide img { align-self: stretch; height: auto; width: min(30%, 130px); object-position: 76%; }
  .booking-guide > div { flex: 1; }
  .date-control input { font-size: 16px; }
  .receipt-actions { display: grid; grid-template-columns: minmax(0, 1fr); }
  .receipt-actions .booking-text { justify-content: center; }
}

@media (max-width: 450px) {
  .department-grid, .booking-time-filters { grid-template-columns: minmax(0, 1fr); }
  .booking-slot { grid-template-columns: minmax(0, 1fr) auto; }
  .booking-slot > div:nth-child(2) { grid-row: 2; }
  .booking-slot > button { grid-column: 2; grid-row: 1 / 3; }
}
</style>
