<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowRight, CalendarDays, ChevronRight, CircleHelp, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import { errorMessage } from '../api/http'
import { channelLabel, formatAppointmentDate, getAppointment, listMyAppointments } from '../api/portal'
import type { AppointmentRecord } from '../types/portal'

const route = useRoute()
const requested = computed(() => typeof route.query.appointmentNo === 'string' ? route.query.appointmentNo : '')
const records = ref<AppointmentRecord[]>([])
const selected = ref<AppointmentRecord | null>(null)
const loading = ref(true)
const error = ref('')
const heading = ref<HTMLElement | null>(null)
const now = ref(new Date())
let requestId = 0

const hospitalClock = new Intl.DateTimeFormat('sv-SE', {
  dateStyle: 'short', timeStyle: 'short', timeZone: 'Asia/Shanghai',
})
const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'short', timeZone: 'UTC' })

const groups = computed(() => {
  const currentTime = hospitalClock.format(now.value)
  const chronological = [...records.value].sort((first, second) => visitStart(first).localeCompare(visitStart(second)))
  const upcoming: AppointmentRecord[] = []
  const history: AppointmentRecord[] = []
  for (const record of chronological) {
    if (record.status === 'BOOKED' && visitStart(record) >= currentTime) upcoming.push(record)
    else history.push(record)
  }
  return { next: upcoming[0], upcoming: upcoming.slice(1), history: history.reverse() }
})

watch(requested, (_, previous) => {
  void load()
  if (previous !== undefined) void nextTick(() => heading.value?.focus())
}, { immediate: true })

async function load() {
  const id = ++requestId
  const number = requested.value
  loading.value = true
  error.value = ''
  selected.value = null
  try {
    if (number) {
      const detail = await getAppointment(number)
      if (id === requestId) selected.value = detail
    } else {
      const list = await listMyAppointments()
      if (id === requestId) {
        records.value = list
        now.value = new Date()
      }
    }
  } catch (failure) {
    if (id === requestId) error.value = errorMessage(failure)
  } finally {
    if (id === requestId) loading.value = false
  }
}

function visitStart(record: AppointmentRecord) {
  return record.appointmentDate + ' ' + record.timeRange.slice(0, 5)
}

function shortWeekday(date: string) {
  return weekday.format(new Date(date + 'T00:00:00Z'))
}

function dateNumber(date: string, part: number) {
  return Number(date.split('-')[part])
}

function detailRoute(record: AppointmentRecord) {
  return { name: 'appointments', query: { appointmentNo: record.appointmentNo } }
}

function preparationRoute(record: AppointmentRecord) {
  return { name: 'visit-preparation', params: { appointmentNo: record.appointmentNo } }
}

function statusLabel(status: string): string {
  return ({ BOOKED: '已预约', CANCELLED: '已取消' } as Record<string, string>)[status] ?? status
}
</script>

<template>
  <AppShell class="appointments-frame">
    <div class="appointments-page page-wrap">
      <RouterLink v-if="requested" class="appointments-back" :to="{ name: 'appointments' }">
        <ArrowLeft :size="16" aria-hidden="true" /> 返回预约列表
      </RouterLink>
      <header class="appointments-header">
        <h1 ref="heading" tabindex="-1">{{ requested ? '预约详情' : '我的预约' }}</h1>
        <button v-if="!requested && !error" class="button-secondary" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="15" aria-hidden="true" /> 刷新记录
        </button>
      </header>

      <p v-if="loading" class="appointments-notice" role="status">
        <LoaderCircle :size="17" class="spin" aria-hidden="true" />正在读取{{ requested ? '预约详情' : '预约记录' }}…
      </p>
      <div v-else-if="error" class="appointments-notice appointments-error">
        <p role="alert"><CircleHelp :size="17" aria-hidden="true" />{{ error }}</p>
        <button class="button-secondary" type="button" @click="load">重新读取</button>
      </div>

      <article v-else-if="selected" class="appointment-detail" aria-label="本次预约">
        <header class="appointment-detail-time">
          <div>
            <time :datetime="selected.appointmentDate">{{ formatAppointmentDate(selected.appointmentDate) }}</time>
            <p class="appointment-time">{{ selected.timeRange }}</p>
          </div>
          <span class="appointment-status" :data-status="selected.status">{{ statusLabel(selected.status) }}</span>
        </header>
        <dl class="appointment-metadata">
          <div><dt>科室</dt><dd>{{ selected.departmentName }}</dd></div>
          <div>
            <dt>医生</dt>
            <dd>{{ selected.doctorName }}<template v-if="selected.doctorTitle"> · {{ selected.doctorTitle }}</template></dd>
          </div>
          <div><dt>预约编号</dt><dd class="appointment-number">{{ selected.appointmentNo }}</dd></div>
          <div><dt>预约方式</dt><dd>{{ channelLabel(selected.bookedVia) }}</dd></div>
        </dl>
        <footer v-if="selected.status === 'BOOKED'" class="appointment-detail-actions">
          <RouterLink class="button-primary" :to="preparationRoute(selected)">
            就诊准备 <ArrowRight :size="16" aria-hidden="true" />
          </RouterLink>
        </footer>
      </article>

      <div v-else-if="records.length" class="appointment-list">
        <section v-if="groups.next" aria-labelledby="next-appointment-title">
          <h2 id="next-appointment-title">下一次就诊</h2>
          <div class="next-appointment">
            <time class="appointment-date-plate" :datetime="groups.next.appointmentDate">
              <span>{{ dateNumber(groups.next.appointmentDate, 1) }} 月</span>
              <strong>{{ dateNumber(groups.next.appointmentDate, 2) }}</strong>
              <span>{{ shortWeekday(groups.next.appointmentDate) }}</span>
            </time>
            <div class="next-appointment-copy">
              <p class="appointment-time">{{ groups.next.timeRange }}</p>
              <p class="next-appointment-doctor">{{ groups.next.departmentName }} · {{ groups.next.doctorName }}<template
                v-if="groups.next.doctorTitle"> · {{ groups.next.doctorTitle }}</template>
              </p>
              <span class="appointment-status" :data-status="groups.next.status">{{ statusLabel(groups.next.status) }}</span>
            </div>
            <div class="next-appointment-actions">
              <RouterLink class="button-primary" :to="preparationRoute(groups.next)">
                就诊准备 <ArrowRight :size="16" aria-hidden="true" />
              </RouterLink>
              <RouterLink class="button-secondary" :to="detailRoute(groups.next)">查看详情</RouterLink>
            </div>
          </div>
        </section>
        <section v-else class="no-next-appointment" aria-labelledby="next-appointment-title">
          <div><h2 id="next-appointment-title">下一次就诊</h2><p>目前没有待就诊的预约。</p></div>
          <RouterLink class="button-secondary" :to="{ name: 'booking' }">去预约挂号</RouterLink>
        </section>

        <section v-for="group in [{ id: 'upcoming', title: '其他待就诊预约', entries: groups.upcoming },
          { id: 'history', title: '历史记录', entries: groups.history }]"
          v-show="group.entries.length" :key="group.id" :aria-labelledby="'appointments-' + group.id">
          <h2 :id="'appointments-' + group.id">{{ group.title }}</h2>
          <ul class="appointment-rows">
            <li v-for="record in group.entries" :key="record.appointmentNo">
              <RouterLink class="appointment-row" :to="detailRoute(record)">
                <time :datetime="record.appointmentDate" class="appointment-row-date">
                  <strong>{{ dateNumber(record.appointmentDate, 1) }}.{{ dateNumber(record.appointmentDate, 2) }}</strong>
                  <span>{{ shortWeekday(record.appointmentDate) }}</span>
                </time>
                <span class="appointment-row-copy">
                  <strong>{{ record.timeRange }}</strong>
                  <span>{{ record.departmentName }} · {{ record.doctorName }}<template
                    v-if="record.doctorTitle"> · {{ record.doctorTitle }}</template></span>
                </span>
                <span class="appointment-status" :data-status="record.status">{{ statusLabel(record.status) }}</span>
                <ChevronRight :size="16" class="appointment-row-arrow" aria-hidden="true" />
              </RouterLink>
            </li>
          </ul>
        </section>
      </div>

      <section v-else class="appointments-empty" aria-labelledby="appointments-empty-title">
        <div class="appointments-empty-icon"><CalendarDays :size="32" :stroke-width="1.6" aria-hidden="true" /></div>
        <h2 id="appointments-empty-title">还没有预约</h2>
        <p>选好就诊时间并确认后，就能在这里查看预约和就诊准备。</p>
        <RouterLink class="button-primary" :to="{ name: 'booking' }">
          去预约挂号 <ArrowRight :size="16" aria-hidden="true" />
        </RouterLink>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.appointments-frame {
  min-width: 0;
  color: #233651;
  background: radial-gradient(ellipse at 85% 10%, #f1ebf4, transparent 58%), linear-gradient(140deg, #dce7f1, #e5e7f0 77%);
}
.appointments-frame :deep(.site-header), .appointments-frame :deep(.site-footer) {
  background: #f8fafc;
  border-color: #ccdbe8;
}
.appointments-frame :deep(.site-footer) { margin-top: 0; }
.appointments-page { width: min(960px, calc(100% - 48px)); padding-block: clamp(22px, 4vh, 46px); }
.appointments-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; margin-bottom: 20px; }
.appointments-header h1 { margin: 0; font-size: clamp(27px, 3vw, 38px); letter-spacing: -.025em; }
.appointments-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 38px;
  margin-bottom: 4px;
  color: var(--brand-plum);
  font-size: 14px;
  font-weight: 700;
}
.appointments-back:hover { text-decoration: underline; text-underline-offset: 3px; }
.appointments-page h2 { margin: 0; font-size: 18px; }
.appointment-list { display: grid; gap: 24px; }
.appointment-list section > h2 { margin-bottom: 8px; }
.next-appointment {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px 22px;
  padding: clamp(14px, 2vw, 24px);
  border: 1px solid #e0d2e5;
  border-radius: 15px;
  background: linear-gradient(114deg, #eee4f3, #fbefec 85%, #fff8f2);
  box-shadow: 0 8px 20px #49335314;
}
.appointment-date-plate {
  display: grid;
  justify-items: center;
  min-width: 80px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--brand-paper);
  color: var(--brand-plum);
  font-size: 13px;
  line-height: 1.42;
}
.appointment-date-plate strong { font-size: 36px; line-height: 1.05; }
.appointment-date-plate strong, .appointment-time, .appointment-row-date strong { font-variant-numeric: tabular-nums; }
.next-appointment-copy { display: grid; justify-items: start; gap: 5px; min-width: 0; }
.appointment-time { margin: 0; color: var(--brand-plum-dark); font-size: clamp(27px, 4vw, 40px); font-weight: 700; line-height: 1.12; }
.next-appointment-doctor { margin: 0; font-size: 15px; font-weight: 600; overflow-wrap: anywhere; }
.next-appointment-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.appointment-status {
  display: inline-block;
  align-self: center;
  width: fit-content;
  padding: 2px 8px;
  border: 1px solid #e4dfe8;
  border-radius: 99px;
  background: #f3f0f4;
  color: var(--brand-muted);
  font-size: 12px;
  white-space: nowrap;
}
.appointment-status[data-status="BOOKED"] { border-color: #c9e1d8; background: #e6f3ed; color: #244d40; }
.appointment-rows { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.appointment-row {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px 14px;
  padding: 11px 15px;
  border: 1px solid #dce4ed;
  border-radius: 11px;
  background: var(--brand-paper);
}
.appointment-row:hover { border-color: #bca9c5; background: #fbf8fc; }
.appointment-row-date, .appointment-row-copy { display: grid; gap: 2px; }
.appointment-row-date strong { color: #3c5371; font-size: 17px; }
.appointment-row-date span, .appointment-row-copy span { color: var(--brand-muted); font-size: 13px; }
.appointment-row-copy { overflow-wrap: anywhere; }
.appointment-row-copy strong { font-size: 15px; }
.appointment-row-arrow { color: var(--brand-plum); }
.no-next-appointment, .appointments-notice, .appointments-empty, .appointment-detail {
  border: 1px solid #dbe3ee;
  border-radius: 15px;
  background: var(--brand-paper);
}
.no-next-appointment, .appointments-notice {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 14px;
  padding: 14px 16px;
}
.no-next-appointment p { margin: 3px 0 0; color: var(--brand-muted); font-size: 14px; }
.appointments-notice { justify-content: start; margin: 0; font-size: 14px; }
.appointments-error { justify-content: space-between; border-color: #edceca; background: #fff4f2; color: #863c39; }
.appointments-error p { display: flex; align-items: start; gap: 8px; margin: 0; }
.appointments-notice svg { flex: none; }
.appointment-detail { overflow: hidden; box-shadow: 0 8px 20px #283f5b12; }
.appointment-detail-time {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: clamp(15px, 3vw, 25px);
  background: linear-gradient(114deg, #eee4f3, #faebe8);
  color: var(--brand-plum);
}
.appointment-detail-time time { display: block; margin-bottom: 6px; font-size: 14px; font-weight: 700; }
.appointment-detail-time .appointment-status { align-self: start; }
.appointment-metadata {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
  margin: 0;
  padding: clamp(15px, 3vw, 25px);
}
.appointment-metadata dt { margin-bottom: 3px; color: var(--brand-muted); font-size: 13px; }
.appointment-metadata dd { margin: 0; font-size: 15px; overflow-wrap: anywhere; }
.appointment-number { font-variant-numeric: tabular-nums; }
.appointment-detail-actions { padding: 12px clamp(15px, 3vw, 25px); border-top: 1px solid var(--brand-line); }
.appointments-empty { display: grid; justify-items: center; gap: 10px; padding: clamp(28px, 6vw, 60px) 14px; text-align: center; }
.appointments-empty-icon {
  display: grid;
  place-items: center;
  width: 62px;
  height: 62px;
  border-radius: 16px;
  background: #e7effb;
  color: #315991;
}
.appointments-empty p { margin: 0 0 6px; color: var(--brand-muted); font-size: 14px; }

@media (max-width: 720px) {
  .appointments-page { width: calc(100% - 24px); }
  .next-appointment { grid-template-columns: auto minmax(0, 1fr); gap: 12px; }
  .next-appointment-actions { grid-column: 1 / -1; }
  .next-appointment-actions .button-primary { flex: 1; }
}
@media (max-width: 420px) {
  .appointment-row { grid-template-columns: 47px minmax(0, 1fr) auto; gap: 8px; padding-inline: 9px; }
  .appointment-row-arrow { display: none; }
  .appointment-date-plate { min-width: 58px; }
  .appointment-metadata { gap: 12px; }
  .appointment-detail-actions .button-primary { width: 100%; }
}
</style>
