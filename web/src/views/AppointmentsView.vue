<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, CalendarDays, ChevronRight, CircleHelp, ClipboardList, Copy, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import AppointmentNotifications from '../components/AppointmentNotifications.vue'
import PaginationBar from '../components/PaginationBar.vue'
import consultationArt from '../assets/home-consultation.webp'
import { patientSession } from '../api/auth'
import { errorMessage } from '../api/http'
import { cancelAppointment, channelLabel, formatAppointmentDate, formatHospitalDateTime, getAppointment, markAppointmentNoticeRead,
  readAppointmentOverview, visitOutcomeConfirmed, visitOutcomeLabel } from '../api/portal'
import { readPending, rescheduleSubmissionKey } from '../stores/pending'
import type { AppointmentOverview, AppointmentRecord } from '../types/portal'

const route = useRoute()
const router = useRouter()
const requested = computed(() => typeof route.query.appointmentNo === 'string' ? route.query.appointmentNo : '')
const noticeKind = computed(() => route.query.notice === 'STOPPED' || route.query.notice === 'ARRANGEMENT'
  ? route.query.notice : undefined)
const overview = ref<AppointmentOverview>()
const upcomingPage = computed(() => readPage(route.query.upcomingPage))
const historyPage = computed(() => readPage(route.query.historyPage))
const recordCount = computed(() => Number(!!overview.value?.next) + (overview.value?.upcoming.totalCount ?? 0) + (overview.value?.history.totalCount ?? 0))
const selected = ref<AppointmentRecord | null>(null)
const pendingReschedule = computed(() => {
  const record = selected.value
  const patient = patientSession.value
  return !!(record && patient && !record.replacementAppointmentNo
    && readPending(rescheduleSubmissionKey(patient.patientId, record.appointmentNo)))
})
const loading = ref(true)
const error = ref('')
const noticeNotRead = ref(false)
const noticeRefresh = ref(0)
const copyStatus = ref('')
const cancelAsk = ref(false)
const cancelBusy = ref(false)
const changeNotice = ref('')
const cancelProblem = ref('')
const heading = ref<HTMLElement | null>(null)
let requestId = 0

const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'short', timeZone: 'UTC' })

function readPage(value: unknown) {
  const page = typeof value === 'string' ? Number(value) : NaN
  return Number.isInteger(page) && page > 0 && page <= 2_147_483_647 ? page : 1
}

const pageQuery = computed(() => ({
  upcomingPage: upcomingPage.value > 1 ? upcomingPage.value : undefined,
  historyPage: historyPage.value > 1 ? historyPage.value : undefined,
}))

watch([requested, upcomingPage, historyPage, noticeKind], async ([number, upcoming, history], previous) => {
  if (number !== previous[0]) {
    cancelAsk.value = false
    changeNotice.value = ''
    cancelProblem.value = ''
  }
  const pending = load()
  if (previous.length && number !== previous[0]) void nextTick(() => heading.value?.focus())
  await pending
  if (number || !previous.length || previous[0] || error.value || loading.value
      || upcomingPage.value !== upcoming || historyPage.value !== history || requested.value) return
  const group = upcoming !== previous[1] ? 'upcoming' : history !== previous[2] ? 'history' : undefined
  if (group) void nextTick(() => document.getElementById('appointments-' + group)?.scrollIntoView())
}, { immediate: true })

function goPage(section: 'upcoming' | 'history', page: number) {
  void router.replace({ name: 'appointments', query: {
    ...pageQuery.value, [section + 'Page']: page > 1 ? page : undefined,
  } })
}

async function load() {
  const id = ++requestId
  const number = requested.value
  const kind = noticeKind.value
  loading.value = true
  error.value = ''
  noticeNotRead.value = false
  copyStatus.value = ''
  selected.value = null
  try {
    if (number) {
      const detail = await getAppointment(number)
      if (id !== requestId) return
      selected.value = detail
      if (kind && detail.interruption && (kind === 'STOPPED' || detail.interruption.outcome)) {
        try {
          await markAppointmentNoticeRead(detail.appointmentNo, kind)
        } catch {
          if (id === requestId) noticeNotRead.value = true
        }
      }
    } else {
      const result = await readAppointmentOverview(upcomingPage.value, historyPage.value)
      if (id === requestId) overview.value = result
    }
  } catch (failure) {
    if (id === requestId) error.value = errorMessage(failure)
  } finally {
    if (id === requestId) loading.value = false
  }
}

function refresh() {
  noticeRefresh.value++
  void load()
}

function shortWeekday(date: string) {
  return weekday.format(new Date(date + 'T00:00:00Z'))
}

function dateNumber(date: string, part: number) {
  return Number(date.split('-')[part])
}

function detailRoute(record: AppointmentRecord) {
  return appointmentDetail(record.appointmentNo)
}

function appointmentDetail(appointmentNo: string) {
  return { name: 'appointments', query: { ...pageQuery.value, appointmentNo } }
}

function preparationRoute(record: AppointmentRecord, from: 'list' | 'detail') {
  return { name: 'visit-preparation', params: { appointmentNo: record.appointmentNo }, query: { ...pageQuery.value, from } }
}

async function copyAppointmentNumber(number: string) {
  try {
    await navigator.clipboard.writeText(number)
    copyStatus.value = '已复制'
  } catch {
    copyStatus.value = '复制失败，请选择编号手动复制。'
  }
}

async function confirmCancel() {
  const number = selected.value?.appointmentNo
  if (!number || cancelBusy.value) return
  cancelBusy.value = true
  cancelProblem.value = ''
  try {
    const result = await cancelAppointment(number)
    if (requested.value !== number) return
    cancelAsk.value = false
    changeNotice.value = result.message
    await load()
  } catch (failure) {
    if (requested.value !== number) return
    cancelAsk.value = false
    cancelProblem.value = errorMessage(failure)
    await load()
  } finally {
    cancelBusy.value = false
  }
}

function statusLabel(appointment: AppointmentRecord): string {
  return visitOutcomeLabel(appointment) ?? ({
    BOOKED: '已预约', CHECKED_IN: '已签到', CANCELLED: '已取消', EXPIRED: '已过期',
    RESCHEDULED: '已改约', INTERRUPTED: '已停诊',
  } as Record<string, string>)[appointment.status] ?? appointment.status
}
</script>

<template>
  <AppShell class="appointments-frame" :class="{ 'appointments-brief': !loading && !error && (selected || recordCount < 4) }">
    <div class="appointments-page page-wrap">
      <RouterLink v-if="requested" class="appointments-back" :to="{ name: 'appointments', query: pageQuery }">
        <ArrowLeft :size="16" aria-hidden="true" /> 返回预约列表
      </RouterLink>
      <header class="appointments-header">
        <h1 ref="heading" tabindex="-1">{{ requested ? '预约详情' : '我的预约' }}</h1>
        <button v-if="!requested && !error" class="button-secondary" type="button" :disabled="loading" @click="refresh">
          <RefreshCw :size="15" aria-hidden="true" /> 刷新记录
        </button>
      </header>

      <AppointmentNotifications v-if="!requested" :refresh-key="noticeRefresh" />
      <div v-if="noticeNotRead" class="appointments-notice appointments-error" role="alert">
        <p>预约已打开，通知暂未标记为已读。</p>
        <button class="button-secondary" type="button" @click="load">重试</button>
      </div>
      <p v-if="loading" class="appointments-notice" role="status">
        <LoaderCircle :size="17" class="spin" aria-hidden="true" />正在读取{{ requested ? '预约详情' : '预约记录' }}…
      </p>
      <div v-else-if="error" class="appointments-notice appointments-error">
        <p role="alert"><CircleHelp :size="17" aria-hidden="true" />{{ error }}</p>
        <button class="button-secondary" type="button" @click="load">重新读取</button>
      </div>

      <div v-else-if="selected" class="appointment-columns">
        <article class="appointment-detail" aria-label="预约凭证">
          <header class="appointment-detail-time">
            <div>
              <h2>预约凭证</h2>
              <time :datetime="selected.appointmentDate">{{ formatAppointmentDate(selected.appointmentDate) }}</time>
              <p class="appointment-time">{{ selected.timeRange }}</p>
            </div>
            <span class="appointment-status" :data-status="selected.status">{{ statusLabel(selected) }}</span>
          </header>
          <dl class="appointment-metadata">
            <div><dt>科室</dt><dd>{{ selected.departmentName }}</dd></div>
            <div>
              <dt>医生</dt>
              <dd>{{ selected.doctorName }}<template v-if="selected.doctorTitle"> · {{ selected.doctorTitle }}</template></dd>
            </div>
            <div><dt>预约方式</dt><dd>{{ channelLabel(selected.bookedVia) }}</dd></div>
            <div v-if="selected.checkedInAt"><dt>签到时间</dt><dd>{{ formatHospitalDateTime(selected.checkedInAt) }}</dd></div>
            <div v-if="selected.visitProgress?.completedAt && visitOutcomeConfirmed(selected)">
              <dt>就诊登记时间</dt><dd>{{ formatHospitalDateTime(selected.visitProgress.completedAt) }}</dd>
            </div>
            <div v-if="selected.visitProgress?.reviewedAt">
              <dt>复核时间</dt><dd>{{ formatHospitalDateTime(selected.visitProgress.reviewedAt) }}</dd>
            </div>
            <div v-if="selected.cancelledAt"><dt>取消时间</dt><dd>{{ formatHospitalDateTime(selected.cancelledAt) }}</dd></div>
            <div v-if="selected.originalAppointmentNo"><dt>改约来源</dt>
              <dd><RouterLink :to="appointmentDetail(selected.originalAppointmentNo)">查看改约前的预约</RouterLink></dd>
            </div>
            <div class="appointment-number-line">
              <dt>预约编号</dt>
              <dd>
                <span class="appointment-number">{{ selected.appointmentNo }}</span>
                <button class="button-secondary" type="button" aria-label="复制预约编号" @click="copyAppointmentNumber(selected.appointmentNo)">
                  <Copy :size="14" aria-hidden="true" /> 复制
                </button>
                <span v-if="copyStatus" class="appointment-copy-status" role="status">{{ copyStatus }}</span>
              </dd>
            </div>
          </dl>
        </article>
        <aside class="appointment-support" aria-labelledby="appointment-support-title">
          <img :src="consultationArt" alt="" width="1536" height="1024" />
          <div class="appointment-support-copy">
            <h2 id="appointment-support-title">{{ selected.interruption ? '停诊与后续安排' : selected.status === 'RESCHEDULED' ? '改约结果'
              : selected.status === 'CHECKED_IN' || visitOutcomeLabel(selected) ? '就诊进度'
              : selected.status === 'BOOKED' ? '就诊准备' : '再次预约' }}</h2>
            <template v-if="selected.interruption">
              <p>{{ selected.interruption.reason }}</p>
              <p v-if="selected.interruption.arrangement">{{ selected.interruption.arrangement }}</p>
              <p v-else>待院方安排。请留意本页更新。</p>
              <RouterLink v-if="selected.interruption.outcome === 'REBOOK'" class="button-primary"
                :to="{ name: 'booking', query: { departmentId: selected.departmentId } }">
                按原科室重新挂号 <ArrowRight :size="16" aria-hidden="true" />
              </RouterLink>
            </template>
            <template v-else-if="selected.status === 'BOOKED'">
              <p>到院后请出示预约凭证，由门诊分诊台办理签到。其他准备事项可在下方咨询。</p>
              <RouterLink class="button-primary" :to="preparationRoute(selected, 'detail')">
                就诊准备 <ArrowRight :size="16" aria-hidden="true" />
              </RouterLink>
            </template>
            <template v-else-if="selected.status === 'CHECKED_IN'">
              <p v-if="selected.appointmentEndsAt && Date.parse(selected.appointmentEndsAt) <= Date.now()">已签到，院方尚未登记就诊结果。</p>
              <p v-else>已签到，请在诊区候诊。如需处理预约，请联系分诊台。</p>
            </template>
            <template v-else-if="visitOutcomeLabel(selected)">
              <p v-if="selected.visitProgress?.reviewedOutcome === 'UNCONFIRMED'">院方正在核实本次就诊结果。</p>
              <p v-else-if="visitOutcomeConfirmed(selected)">{{ selected.visitProgress?.reviewedOutcome === 'CONFIRMED'
                ? '院方已复核确认本次就诊。' : '本次就诊已完成。' }}</p>
              <p v-else>本次预约已结束，院方尚未确认就诊结果。</p>
            </template>
            <template v-else-if="selected.replacementAppointmentNo">
              <p>这笔预约已改约，请查看新的就诊时间和预约凭证。</p>
              <RouterLink class="button-primary" :to="appointmentDetail(selected.replacementAppointmentNo)">
                查看新预约 <ArrowRight :size="16" aria-hidden="true" />
              </RouterLink>
            </template>
            <template v-else>
              <p v-if="selected.status === 'EXPIRED'">本次就诊时段已结束。如需就诊，可重新挂号。</p>
              <p v-else>可以按本科室重新查询可预约时间。</p>
              <RouterLink class="button-secondary" :to="{ name: 'booking', query: { departmentId: selected.departmentId } }">
                查看可预约时间 <ArrowRight :size="16" aria-hidden="true" />
              </RouterLink>
            </template>
          </div>
        </aside>
        <section v-if="selected.status === 'BOOKED' || changeNotice || cancelProblem || pendingReschedule"
          class="appointment-management" aria-label="预约操作">
          <p v-if="changeNotice" role="status">{{ changeNotice }}</p>
          <p v-if="cancelProblem" role="alert" class="cancel-problem">{{ cancelProblem }}</p>
          <template v-if="selected.status === 'BOOKED' && selected.cancellationDeadline">
            <p v-if="selected.canCancel">线上取消截止至 {{ formatHospitalDateTime(selected.cancellationDeadline) }}（北京时间），改约沿用同一截止时间。</p>
            <p v-else>已过线上取消及改约时间，如需处理请联系门诊分诊台。</p>
            <div v-if="selected.canCancel && !cancelAsk && !pendingReschedule" class="cancel-actions">
              <button class="button-secondary cancel-appointment" type="button" @click="cancelAsk = true">取消预约</button>
              <RouterLink class="button-secondary" :to="{ name: 'appointment-reschedule', params: { appointmentNo: selected.appointmentNo }, query: pageQuery }">
                改约
              </RouterLink>
            </div>
            <div v-if="cancelAsk" class="cancel-confirmation" role="group" aria-label="核对取消预约">
              <p><strong>确认取消这笔预约？</strong></p>
              <p>{{ selected.departmentName }} · {{ selected.doctorName }} · {{ formatAppointmentDate(selected.appointmentDate) }} {{ selected.timeRange }}</p>
              <div class="cancel-actions">
                <button class="button-primary cancel-action" type="button" :disabled="cancelBusy" @click="confirmCancel">
                  {{ cancelBusy ? '正在取消…' : '确认取消预约' }}
                </button>
                <button class="button-secondary" type="button" :disabled="cancelBusy" @click="cancelAsk = false">保留预约</button>
              </div>
            </div>
          </template>
          <RouterLink v-if="pendingReschedule" class="button-secondary"
            :to="{ name: 'appointment-reschedule', params: { appointmentNo: selected.appointmentNo }, query: pageQuery }">
            核验改约
          </RouterLink>
        </section>
      </div>

      <div v-else-if="overview && recordCount" class="appointment-columns">
        <div class="appointment-list">
          <section v-if="overview.interrupted?.length" class="appointment-management" aria-labelledby="interruption-title">
            <h2 id="interruption-title">停诊提醒</h2>
            <ul>
              <li v-for="record in overview.interrupted" :key="record.appointmentNo">
                <RouterLink :to="detailRoute(record)">{{ formatAppointmentDate(record.appointmentDate) }} {{ record.timeRange }}
                  · {{ record.departmentName }} · {{ record.doctorName }}。查看停诊安排</RouterLink>
              </li>
            </ul>
          </section>
          <section v-if="overview.next" aria-labelledby="next-appointment-title">
            <h2 id="next-appointment-title">下一次就诊</h2>
            <div class="next-appointment">
              <time class="appointment-date-plate" :datetime="overview.next.appointmentDate">
                <span>{{ dateNumber(overview.next.appointmentDate, 1) }} 月</span>
                <strong>{{ dateNumber(overview.next.appointmentDate, 2) }}</strong>
                <span>{{ shortWeekday(overview.next.appointmentDate) }}</span>
              </time>
              <div class="next-appointment-copy">
                <p class="appointment-time">{{ overview.next.timeRange }}</p>
                <p class="next-appointment-doctor">{{ overview.next.departmentName }} · {{ overview.next.doctorName }}<template
                  v-if="overview.next.doctorTitle"> · {{ overview.next.doctorTitle }}</template>
                </p>
                <span class="appointment-status" :data-status="overview.next.status">{{ statusLabel(overview.next) }}</span>
                <small v-if="overview.next.status === 'CHECKED_IN' && overview.next.checkedInAt">
                  签到 {{ formatHospitalDateTime(overview.next.checkedInAt) }}
                </small>
              </div>
              <div class="next-appointment-actions">
                <RouterLink v-if="overview.next.status === 'BOOKED'" class="button-primary" :to="preparationRoute(overview.next, 'list')">
                  就诊准备 <ArrowRight :size="16" aria-hidden="true" />
                </RouterLink>
                <RouterLink class="button-secondary" :to="detailRoute(overview.next)">查看详情</RouterLink>
              </div>
            </div>
          </section>
          <section v-else class="no-next-appointment" aria-labelledby="next-appointment-title">
            <div><h2 id="next-appointment-title">下一次就诊</h2><p>目前没有待就诊的预约。</p></div>
            <RouterLink class="button-secondary" :to="{ name: 'booking' }">去预约挂号</RouterLink>
          </section>

          <section v-for="group in [{ id: 'upcoming' as const, title: '其他待就诊预约', page: overview.upcoming },
            { id: 'history' as const, title: '历史记录', page: overview.history }]"
            v-show="group.page.totalCount" :key="group.id" :aria-labelledby="'appointments-' + group.id">
            <h2 :id="'appointments-' + group.id">{{ group.title }}</h2>
            <ul class="appointment-rows">
              <li v-for="record in group.page.items" :key="record.appointmentNo">
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
                  <span class="appointment-status" :data-status="record.status">{{ statusLabel(record) }}</span>
                  <ChevronRight :size="16" class="appointment-row-arrow" aria-hidden="true" />
                </RouterLink>
              </li>
            </ul>
            <PaginationBar v-if="group.page.totalPages > 1" class="appointment-pagination" :label="group.title + '分页'"
              :page="group.page.page" :total-pages="group.page.totalPages" @change="goPage(group.id, $event)" />
          </section>
        </div>
        <aside class="appointment-support" aria-labelledby="appointment-overview-title">
          <img :src="consultationArt" alt="" width="1536" height="1024" />
          <div class="appointment-support-copy">
            <h2 id="appointment-overview-title"><ClipboardList :size="20" aria-hidden="true" /> 预约记录</h2>
            <dl class="appointment-overview">
              <div><dt>待就诊</dt><dd>{{ overview.upcoming.totalCount + Number(!!overview.next) }} 笔</dd></div>
              <div><dt>历史记录</dt><dd>{{ overview.history.totalCount }} 笔</dd></div>
            </dl>
            <RouterLink v-if="overview.next" class="button-secondary" :to="{ name: 'booking' }">
              预约挂号 <ArrowRight :size="16" aria-hidden="true" />
            </RouterLink>
          </div>
        </aside>
      </div>

      <section v-else class="appointments-empty" aria-labelledby="appointments-empty-title">
        <img class="appointments-empty-art" :src="consultationArt" alt="" width="1536" height="1024" />
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
.appointments-page { width: min(1200px, calc(100% - 48px)); padding-block: clamp(22px, 4vh, 46px); }
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
.appointment-columns { display: grid; align-items: start; grid-template-columns: minmax(0, 1fr) minmax(230px, 31%); gap: 18px; }
.appointment-support { min-width: 0; overflow: hidden; border: 1px solid #cedeec; border-radius: 15px; background: #eef5fb; }
.appointment-support img { display: block; width: 100%; height: clamp(122px, 20vh, 200px); object-fit: cover; object-position: 69% 15%; }
.appointment-support-copy { padding: clamp(14px, 2vw, 22px); }
.appointment-support h2 { display: flex; align-items: center; gap: 7px; color: #284a76; }
.appointment-support p { margin: 8px 0 14px; color: #3b5574; font-size: 14px; }
.appointment-overview { display: grid; gap: 5px; padding: 0 0 12px; margin: 10px 0 0; }
.appointment-overview div { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.appointment-overview dt { font-size: 14px; color: #3b5574; }
.appointment-overview dd { margin: 0; font-variant-numeric: tabular-nums; font-weight: 700; }
.appointment-list { display: grid; gap: 24px; }
.appointment-pagination { --pagination-border: var(--brand-line); --pagination-ink: var(--brand-ink);
  --pagination-active: var(--brand-plum); --pagination-muted: var(--brand-muted); margin-top: 12px; }
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
.appointment-management { grid-column: 1 / -1; color: var(--brand-muted); font-size: 14px; }
.appointment-management:empty { display: none; }
.appointment-management > p { margin: 0 0 9px; }
.cancel-problem, .cancel-appointment { color: #853c39; }
.cancel-confirmation { padding: 13px; border-radius: 10px; border: 1px solid #e8d1ce; background: #fff8f7; }
.cancel-confirmation p { margin: 0 0 9px; }
.cancel-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.cancel-action { background: #913f3c; }
.appointment-status[data-status="CHECKED_IN"] { background: #e5effb; border-color: #cbdced; color: #244f74; }
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
.appointment-detail-time h2 { margin-bottom: 10px; }
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
.appointment-number-line { grid-column: 1 / -1; padding-top: 12px; border-top: 1px dashed var(--brand-line); }
.appointment-number-line dd { display: flex; flex-wrap: wrap; align-items: center; gap: 5px 10px; }
.appointment-copy-status { flex-basis: 100%; color: var(--brand-muted); font-size: 13px; }
.appointment-number-line button { min-height: 36px; }

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
.appointments-empty-art { width: min(100%, 300px); height: 134px; border-radius: 11px; object-fit: cover; object-position: 72% 48%; }
.appointments-empty p { margin: 0 0 6px; color: var(--brand-muted); font-size: 14px; }

@media (min-width: 841px) {
  .appointments-brief :deep(.patient-page-content) { display: grid; align-content: safe center; }
}
@media (max-width: 840px) {
  .appointment-columns { grid-template-columns: minmax(0, 1fr); }
  .appointment-support { display: grid; grid-template-columns: minmax(85px, 29%) minmax(0, 1fr); }
  .appointment-support img { align-self: stretch; height: 100%; object-position: 79% 50%; }
}
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
}
</style>
