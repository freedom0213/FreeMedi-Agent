<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, CalendarDays, CircleCheck, CircleHelp, Clock3,
  LoaderCircle, RefreshCw, ShieldCheck, Stethoscope, UserRound,
} from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import { errorMessage } from '../api/http'
import {
  bookSlot, formatAppointmentDate, listDepartments, listDoctors,
  periodLabel, searchSlots, slotLabel,
} from '../api/portal'
import type { AvailableSlot, BookingResult, Department, Doctor } from '../types/portal'

const route = useRoute()
const router = useRouter()

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
const searchEndDate = ref('')
const error = ref('')
const stage = ref(1)
const busy = reactive({ departments: false, doctors: false, slots: false, submitting: false })

const periods = [
  { value: '', label: '不限' },
  { value: 'MORNING', label: '上午' },
  { value: 'AFTERNOON', label: '下午' },
  { value: 'EVENING', label: '晚上' },
]

const selectedDepartment = computed(() => departments.value.find((item) => item.id === departmentId.value))
const selectedDoctor = computed(() => doctors.value.find((item) => item.id === doctorId.value))
const canSubmit = computed(() => !!selectedSlot.value && !busy.submitting)

onMounted(async () => {
  busy.departments = true
  try {
    departments.value = await listDepartments()
    const requested = Number(route.query.departmentId)
    const initial = departments.value.find((item) => item.id === requested)
    if (initial) await chooseDepartment(initial)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.departments = false
  }
})

async function chooseDepartment(department: Department) {
  departmentId.value = department.id
  doctorId.value = null
  doctors.value = []
  slots.value = []
  clearSelection()
  stage.value = 2
  busy.doctors = true
  try {
    doctors.value = await listDoctors(department.id)
    await loadSlots()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.doctors = false
  }
}

async function chooseDoctor(id: number | null) {
  doctorId.value = id
  clearSelection()
  stage.value = 3
  await loadSlots()
}

async function loadSlots() {
  if (departmentId.value === null) return
  busy.slots = true
  error.value = ''
  try {
    const response = await searchSlots({
      departmentId: departmentId.value,
      doctorId: doctorId.value ?? undefined,
      date: appointmentDate.value || undefined,
      period: clinicPeriod.value || undefined,
    })
    slots.value = response.slots
    searchEndDate.value = response.searchEndDate
    if (slots.value.length > 0) stage.value = 4
  } catch (failure) {
    error.value = errorMessage(failure)
    slots.value = []
  } finally {
    busy.slots = false
  }
}

function selectSlot(slot: AvailableSlot) {
  selectedSlot.value = slot
  // One key per chosen slot: a retry of the same intent must not create a second appointment.
  idempotencyKey.value = crypto.randomUUID()
  booking.value = null
  error.value = ''
}

function clearSelection() {
  selectedSlot.value = null
  idempotencyKey.value = ''
  booking.value = null
}

async function submit() {
  const slot = selectedSlot.value
  if (!slot || !canSubmit.value) return
  busy.submitting = true
  error.value = ''
  try {
    const result = await bookSlot(slot.slotId, idempotencyKey.value)
    booking.value = result
    if (result.success || result.code === 'SLOT_UNAVAILABLE' || result.code === 'SLOT_NOT_FOUND') {
      clearSelectionFields()
      await loadSlots()
    } else {
      // The key was already used for something else, so the next attempt needs a fresh one.
      idempotencyKey.value = crypto.randomUUID()
    }
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    busy.submitting = false
  }
}

/** Keeps the outcome visible while dropping the consumed selection. */
function clearSelectionFields() {
  selectedSlot.value = null
  idempotencyKey.value = ''
}

function restart() {
  departmentId.value = null
  doctorId.value = null
  doctors.value = []
  slots.value = []
  appointmentDate.value = ''
  clinicPeriod.value = ''
  stage.value = 1
  clearSelection()
  error.value = ''
}
</script>

<template>
  <AppShell>
    <div class="portal-page page-wrap">
      <div class="portal-heading">
        <button class="back-link" type="button" @click="router.push('/')">
          <ArrowLeft :size="16" />返回就诊服务
        </button>
        <div class="portal-heading-row">
          <div>
            <div class="eyebrow"><span class="eyebrow-line" />普通预约挂号</div>
            <h1>按科室和医生选号</h1>
          </div>
          <p class="portal-hint">不经过智能助手，直接筛选可用号源。提交前请核对信息。</p>
        </div>
      </div>

      <div class="portal-workspace">
        <section class="portal-main" aria-label="预约筛选步骤">
          <article class="step-block" :class="{ current: stage === 1 }">
            <header class="step-head">
              <span class="step-no">1</span>
              <div>
                <h2>选择科室</h2>
                <p>先确定就诊的科室方向</p>
              </div>
              <LoaderCircle v-if="busy.departments" :size="15" class="spin" />
            </header>
            <div v-if="departments.length" class="option-list">
              <button
                v-for="department in departments"
                :key="department.id"
                class="option-row"
                :class="{ selected: department.id === departmentId }"
                type="button"
                @click="chooseDepartment(department)"
              >
                <span class="option-name">{{ department.name }}</span>
                <span class="option-meta">{{ department.description ?? '' }}</span>
              </button>
            </div>
            <p v-else-if="!busy.departments" class="block-empty">暂时没有可预约的科室。</p>
          </article>

          <article v-if="departmentId !== null" class="step-block" :class="{ current: stage === 2 }">
            <header class="step-head">
              <span class="step-no">2</span>
              <div>
                <h2>选择医生</h2>
                <p>{{ selectedDepartment?.name }} · 不确定就选不限，会列出该科室全部医生</p>
              </div>
              <LoaderCircle v-if="busy.doctors" :size="15" class="spin" />
            </header>
            <div class="option-list">
              <button
                class="option-row"
                :class="{ selected: doctorId === null }"
                type="button"
                @click="chooseDoctor(null)"
              >
                <span class="option-name">不限医生</span>
                <span class="option-meta">按时间优先查看该科室所有号源</span>
              </button>
              <button
                v-for="doctor in doctors"
                :key="doctor.id"
                class="option-row"
                :class="{ selected: doctor.id === doctorId }"
                type="button"
                @click="chooseDoctor(doctor.id)"
              >
                <span class="option-name">{{ doctor.name }}<span class="option-title">{{ doctor.title }}</span></span>
                <span class="option-meta">{{ doctor.specialty ?? '' }}</span>
              </button>
            </div>
          </article>

          <article v-if="departmentId !== null" class="step-block" :class="{ current: stage === 3 }">
            <header class="step-head">
              <span class="step-no">3</span>
              <div>
                <h2>日期与时段</h2>
                <p>可以都不选，直接看最近的号源</p>
              </div>
            </header>
            <div class="filter-row">
              <label class="filter-field">
                <span><CalendarDays :size="14" />就诊日期</span>
                <input v-model="appointmentDate" type="date" @change="loadSlots()" />
              </label>
              <div class="filter-field">
                <span><Clock3 :size="14" />就诊时段</span>
                <div class="chip-row">
                  <button
                    v-for="option in periods"
                    :key="option.value"
                    class="chip"
                    :class="{ selected: clinicPeriod === option.value }"
                    type="button"
                    @click="clinicPeriod = option.value; loadSlots()"
                  >
                    {{ option.label }}
                  </button>
                </div>
              </div>
            </div>
            <p class="filter-note">
              这里只按你选的条件查询，不会自动换成别的日期或时段。
              <template v-if="searchEndDate">可查询至 {{ searchEndDate }}。</template>
            </p>
          </article>

          <article v-if="departmentId !== null" class="step-block" :class="{ current: stage === 4 }">
            <header class="step-head">
              <span class="step-no">4</span>
              <div>
                <h2>选择号源</h2>
                <p>选择一个具体时段作为预约方案</p>
              </div>
              <button class="text-action" type="button" @click="loadSlots()">
                <RefreshCw :size="13" />重新查询
              </button>
            </header>
            <div v-if="busy.slots" class="block-empty"><LoaderCircle :size="15" class="spin" />正在查询号源…</div>
            <div v-else-if="slots.length" class="slot-list">
              <button
                v-for="slot in slots"
                :key="slot.slotId"
                class="slot-row"
                :class="{ selected: slot.slotId === selectedSlot?.slotId }"
                type="button"
                @click="selectSlot(slot)"
              >
                <span class="slot-body">
                  <span class="slot-date">{{ formatAppointmentDate(slot.appointmentDate) }}</span>
                  <span class="slot-time">{{ periodLabel(slot.clinicPeriod) }} {{ slot.timeRange }}</span>
                  <span class="slot-doctor">
                    <UserRound :size="13" />{{ slot.doctorName }}<template v-if="slot.doctorTitle"> · {{ slot.doctorTitle }}</template>
                  </span>
                  <span class="slot-dept"><Stethoscope :size="13" />{{ slot.departmentName }}</span>
                </span>
              </button>
            </div>
            <p v-else class="block-empty">
              这个条件下暂时没有可用号源。可以换一个日期、时段或医生后再看。
            </p>
          </article>
        </section>

        <aside class="portal-aside" aria-label="本次预约摘要">
          <div class="aside-head">
            <h2>预约摘要</h2>
            <button class="text-action" type="button" @click="restart()">
              <RefreshCw :size="13" />重新选择
            </button>
          </div>

          <dl class="summary-list">
            <div><dt>科室</dt><dd>{{ selectedDepartment?.name ?? '未选择' }}</dd></div>
            <div><dt>医生</dt><dd>{{ selectedDoctor ? `${selectedDoctor.name}${selectedDoctor.title ? ` · ${selectedDoctor.title}` : ''}` : doctorId === null && departmentId !== null ? '不限医生' : '未选择' }}</dd></div>
            <div><dt>日期</dt><dd>{{ appointmentDate ? formatAppointmentDate(appointmentDate) : '不限' }}</dd></div>
            <div><dt>时段</dt><dd>{{ clinicPeriod ? periodLabel(clinicPeriod) : '不限' }}</dd></div>
          </dl>

          <div v-if="selectedSlot" class="confirm-box">
            <strong>确认前请核对</strong>
            <span>{{ slotLabel(selectedSlot) }}</span>
            <button class="button-primary confirm-button" type="button" :disabled="!canSubmit" @click="submit">
              <LoaderCircle v-if="busy.submitting" :size="15" class="spin" />
              <template v-else>确认预约 <ArrowRight :size="15" /></template>
            </button>
          </div>
          <p v-else class="aside-note">在上方选择一个号源后，这里会显示确认信息。</p>

          <div v-if="booking?.success" class="completion-state" role="status">
            <CircleCheck :size="18" />
            <div>
              <strong>{{ booking.outcome === 'EXISTING' ? '该预约已存在' : '预约已创建' }}</strong>
              <span>预约编号 {{ booking.appointmentNo }}</span>
              <RouterLink class="text-action" to="/appointments">查看我的预约 <ArrowRight :size="13" /></RouterLink>
            </div>
          </div>
          <div v-else-if="booking && !booking.success" class="failure-state" role="alert">
            <CircleHelp :size="17" />
            <div>
              <strong>预约未提交</strong>
              <span>{{ booking.message }}</span>
            </div>
          </div>

          <p v-if="error" class="inline-error" role="alert"><CircleHelp :size="15" />{{ error }}</p>

          <div class="privacy-note">
            <ShieldCheck :size="15" />
            <span>号源、医生和预约数据均为合成演示数据；提交前需要你确认。</span>
          </div>
        </aside>
      </div>
    </div>
  </AppShell>
</template>
