<script setup lang="ts">
import { onMounted, ref, shallowRef, watch } from 'vue'
import { readAppointmentHistory, type AppointmentHistory } from '../api/admin'

const props = defineProps<{ appointmentNo: string }>()
const emit = defineEmits<{ close: []; inspect: [number: string] }>()
const dialog = ref<HTMLDialogElement>()
const history = shallowRef<AppointmentHistory>()
const page = ref(1)
const retry = ref(0)
const loading = ref(true)
const failed = ref(false)

const actions: Record<string, string> = {
  APPOINTMENT_CREATE: '创建预约', APPOINTMENT_CANCEL: '取消预约',
  APPOINTMENT_CHECK_IN: '办理签到', APPOINTMENT_RESCHEDULE: '改约',
  APPOINTMENT_INTERRUPTED: '办理停诊', APPOINTMENT_ARRANGED: '登记停诊安排',
  APPOINTMENT_VISITED: '登记已就诊', APPOINTMENT_VISIT_REVIEW: '复核就诊结果',
}
const operators: Record<string, string> = { PATIENT: '患者本人', ADMIN: '管理员', SYSTEM: '系统' }
const results: Record<string, string> = { SUCCEEDED: '成功', REJECTED: '未成功', FAILED: '处理失败' }
const timestamp = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium',
})

onMounted(() => dialog.value?.showModal())
watch([page, retry], ([current], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  failed.value = false
  void readAppointmentHistory(props.appointmentNo, current, request.signal).then(data => {
    if (!request.signal.aborted) history.value = data
  }).catch(() => {
    if (!request.signal.aborted) failed.value = true
  }).finally(() => {
    if (!request.signal.aborted) loading.value = false
  })
}, { immediate: true })
</script>

<template>
  <dialog ref="dialog" class="appointment-history" aria-labelledby="appointment-history-title" @close="emit('close')">
    <header>
      <div>
        <h2 id="appointment-history-title">预约操作记录</h2>
        <p>{{ appointmentNo }}</p>
      </div>
      <button class="admin-button" type="button" autofocus @click="dialog?.close()">关闭</button>
    </header>
    <div class="appointment-history-content">
      <div v-if="history?.originalAppointmentNo || history?.replacementAppointmentNo" class="catalog-actions history-relations">
        <button v-if="history.originalAppointmentNo" class="admin-button" type="button"
          @click="emit('inspect', history.originalAppointmentNo)">查看改约前的预约</button>
        <button v-if="history.replacementAppointmentNo" class="admin-button" type="button"
          @click="emit('inspect', history.replacementAppointmentNo)">查看新预约</button>
      </div>
      <p v-if="loading" role="status">正在读取操作记录…</p>
      <div v-else-if="failed" class="admin-form-error" role="alert">
        操作记录加载失败。 <button class="admin-button" type="button" @click="retry++">重试</button>
      </div>
      <template v-else-if="history?.events.items.length">
        <ol class="history-events">
          <li v-for="(event, index) in history.events.items" :key="index">
            <div class="history-entry-heading">
              <strong>{{ actions[event.action] ?? event.action }}</strong>
              <span class="catalog-status" :data-active="event.result === 'SUCCEEDED'">{{ results[event.result] ?? event.result }}</span>
            </div>
            <p><time :datetime="event.time">{{ timestamp.format(new Date(event.time)) }}</time>
              · {{ event.operatorName || operators[event.operatorType] || event.operatorType }}</p>
            <p v-if="event.reason">{{ event.reason }}</p>
          </li>
        </ol>
        <nav v-if="history.events.totalPages > 1" aria-label="预约操作记录分页" class="catalog-actions">
          <button class="admin-button" type="button" :disabled="history.events.page <= 1"
            @click="page = history.events.page - 1">上一页</button>
          <span>{{ history.events.page }} / {{ history.events.totalPages }}</span>
          <button class="admin-button" type="button" :disabled="history.events.page >= history.events.totalPages"
            @click="page = history.events.page + 1">下一页</button>
        </nav>
      </template>
      <p v-else>这笔预约暂无操作记录。</p>
    </div>
  </dialog>
</template>

<style scoped>
.appointment-history {
  color: var(--admin-ink);
  background: white;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
  width: min(540px, calc(100vw - 24px));
  max-height: min(690px, calc(100dvh - 24px));
  padding: 0;
  box-shadow: 0 20px 60px #142c4b26;
}
.appointment-history::backdrop { background: #18294355; }
.appointment-history header, .history-entry-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.appointment-history header { position: sticky; top: 0; background: white; border-bottom: 1px solid var(--admin-border); padding: 16px 20px; }
.appointment-history header h2, .appointment-history p { margin: 0; }
.appointment-history header p { color: var(--admin-muted); overflow-wrap: anywhere; }
.appointment-history-content { padding: 16px 20px; }
.history-relations { margin-bottom: 14px; }
.history-events { margin: 0 0 10px; padding: 0; list-style: none; }
.history-events li { padding: 12px 0; border-bottom: 1px solid var(--admin-border); }
.history-events p { margin-top: 4px; color: var(--admin-muted); }
</style>
