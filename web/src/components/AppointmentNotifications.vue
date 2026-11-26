<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Bell, ChevronRight } from 'lucide-vue-next'
import { patientSession } from '../api/auth'
import { formatAppointmentDate, formatHospitalDateTime, readAppointmentNotifications } from '../api/portal'
import type { AppointmentInbox } from '../types/portal'
import PaginationBar from './PaginationBar.vue'

const props = defineProps<{ compact?: boolean; refreshKey?: number }>()
const inbox = ref<AppointmentInbox>()
const page = ref(1)
const retry = ref(0)
const busy = ref(false)
const error = ref(false)
const notices = computed(() => {
  const items = inbox.value?.items ?? []
  return props.compact ? items.filter(item => item.unread).slice(0, 2) : items
})

watch([page, retry, () => props.refreshKey, () => patientSession.value?.patientId], async ([current, , , patient], previous, onCleanup) => {
  if (patient !== previous[3]) inbox.value = undefined
  if (!patient) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  busy.value = true
  error.value = false
  try {
    const result = await readAppointmentNotifications(current, request.signal)
    if (!request.signal.aborted) inbox.value = result
  } catch {
    if (!request.signal.aborted) error.value = true
  } finally {
    if (!request.signal.aborted) busy.value = false
  }
}, { immediate: true })
</script>

<template>
  <section v-if="compact ? notices.length : notices.length || busy || error" class="appointment-inbox"
    aria-labelledby="appointment-notifications-heading" :aria-busy="busy">
    <header class="notice-header">
      <div>
        <Bell :size="19" aria-hidden="true" />
        <h2 id="appointment-notifications-heading">预约通知</h2>
        <span v-if="inbox?.unreadCount" class="notice-unread">{{ inbox.unreadCount }} 条未读</span>
      </div>
      <RouterLink v-if="compact" class="button-secondary" :to="{ name: 'appointments' }">查看全部通知</RouterLink>
    </header>
    <p v-if="busy && !inbox" class="notice-feedback" role="status">正在读取预约通知…</p>
    <div v-if="error" class="notice-feedback" role="alert">
      预约通知暂时无法读取。<button class="button-secondary" type="button" @click="retry++">重试</button>
    </div>
    <ul v-if="notices.length" class="notice-list">
      <li v-for="notice in notices" :key="notice.appointmentNo + ':' + notice.kind">
        <RouterLink :to="{ name: 'appointments', query: { appointmentNo: notice.appointmentNo, notice: notice.kind } }">
          <div class="notice-copy">
            <strong>{{ notice.kind === 'STOPPED' ? '门诊停诊' : '停诊安排更新' }}</strong>
            <small>{{ notice.departmentName }} · {{ notice.doctorName }} · 原就诊日 {{ formatAppointmentDate(notice.appointmentDate) }}</small>
          </div>
          <time :datetime="notice.occurredAt">{{ formatHospitalDateTime(notice.occurredAt) }}</time>
          <span v-if="notice.unread" class="notice-unread">未读</span>
          <ChevronRight :size="16" aria-hidden="true" />
        </RouterLink>
      </li>
    </ul>
    <PaginationBar v-if="!compact && inbox && inbox.totalPages > 1"
      label="预约通知分页" :page="inbox.page" :total-pages="inbox.totalPages" :busy="busy" @change="page = $event" />
  </section>
</template>

<style scoped>
.appointment-inbox { margin: 0 0 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); }
.notice-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; padding: 12px 16px; }
.notice-header > div { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.notice-header h2 { margin: 0; font-size: 18px; }
.notice-header svg, .notice-list svg { color: var(--brand); }
.notice-list { margin: 0; padding: 0; list-style: none; }
.notice-list a { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; padding: 10px 16px; border-top: 1px solid var(--line); color: inherit; text-decoration: none; }
.notice-list a:hover, .notice-list a:focus-visible { background: var(--mist, #edf3fa); }
.notice-copy { flex: 1 1 240px; min-width: 0; overflow-wrap: anywhere; }
.notice-copy small { display: block; }
.notice-list small, .notice-list time { color: var(--ink-soft); font-size: 13px; }
.notice-unread { padding: 2px 7px; border-radius: 8px; color: #2b4d7e; background: #e7effa; font-size: 12px; }
.notice-feedback { margin: 0; padding: 12px 16px; border-top: 1px solid var(--line); }
.table-pagination { margin-top: 10px; --pagination-border: var(--line); --pagination-ink: var(--ink); --pagination-active: #2b4d7e; }
</style>
