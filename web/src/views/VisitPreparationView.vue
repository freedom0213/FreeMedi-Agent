<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, CalendarDays, ChevronRight, CircleHelp, Clock3, FileText,
  LoaderCircle, Send, TicketCheck,
} from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import KnowledgeSources from '../components/KnowledgeSources.vue'
import { getTask, streamMessage } from '../api/agent'
import { channelLabel, formatAppointmentDate, openVisitConsultation } from '../api/portal'
import type { KnowledgeReply, TaskSnapshot, TaskTurn, VisitAppointment } from '../types/agent'
import { readPending, writePending } from '../stores/pending'

interface MessageIntent { requestId: string; text: string; retryRequired?: boolean }
interface DisplayedTurn { key: string; turn: TaskTurn }
const messageKey = (id: string) => `freemedi-visit-message:${id}`

const route = useRoute()
const router = useRouter()
const appointmentNo = computed(() => String(route.params.appointmentNo ?? ''))
const appointmentDetail = computed(() => ({
  name: 'appointments',
  query: { appointmentNo: appointmentNo.value },
}))
const task = ref<TaskSnapshot | null>(null)
const taskId = ref('')
const loading = ref(false)
const loadAttempt = ref(0)
const sending = ref(false)
const question = ref('')
const pendingQuestion = ref('')
const partialReply = ref('')
const loadError = ref('')
const unresolved = ref<{ text: string; message: string } | null>(null)
const appointment = computed(() => task.value?.facts.visitAppointment as VisitAppointment | undefined)
const sources = computed(() => task.value?.facts.knowledgeReplies as Record<string, KnowledgeReply> | undefined)
const activeQuestion = computed(() => pendingQuestion.value || unresolved.value?.text)
const displayedTurns = computed(() => {
  const history: DisplayedTurn[] = []
  for (const turn of task.value?.turns ?? []) {
    if (!turn.assistantMessage && turn.userMessage === activeQuestion.value) continue
    const previous = history.at(-1)
    // The API keeps every attempt. A retry of the same failed question replaces its visible reply.
    if (previous && turn.assistantMessage && isUnavailable(previous.turn) && previous.turn.userMessage === turn.userMessage) {
      previous.turn = turn
    } else history.push({ key: turn.clientRequestId, turn })
  }
  return history
})
const visibleRetry = computed(() => {
  const last = displayedTurns.value.at(-1)
  return last && isUnavailable(last.turn) ? last : undefined
})
const updatingLastTurn = computed(() => !!visibleRetry.value && visibleRetry.value.turn.userMessage === activeQuestion.value)
const examples = [
  { text: '这次去要带什么？', icon: FileText },
  { text: '建议什么时候到院？', icon: Clock3 },
  { text: '到院后如何取号？', icon: TicketCheck },
]

watch([appointmentNo, loadAttempt], async ([number], _, onCleanup) => {
  let current = true
  onCleanup(() => { current = false })
  task.value = null
  taskId.value = ''
  sending.value = false
  question.value = ''
  partialReply.value = ''
  pendingQuestion.value = ''
  loadError.value = ''
  unresolved.value = null
  loading.value = true
  try {
    const id = await openVisitConsultation(number)
    const snapshot = await getTask(id)
    if (current) {
      taskId.value = id
      task.value = snapshot
      const pending = readPending<MessageIntent>(messageKey(id))
      if (pending) {
        const saved = snapshot.turns.find(turn => turn.clientRequestId === pending.requestId && !!turn.assistantMessage)
        if (saved) {
          writePending(messageKey(id), isUnavailable(saved) ? { ...pending, retryRequired: true } : null)
        } else {
          unresolved.value = { text: pending.text, message: '上次查询的结果尚未确认，可以重试原问题。' }
        }
      }
    }
  } catch {
    if (current) loadError.value = '这笔预约暂时无法打开咨询。'
  } finally {
    if (current) loading.value = false
  }
}, { immediate: true })

function isUnavailable(turn: TaskTurn) {
  return sources.value?.[turn.turnNo]?.status === 'UNAVAILABLE'
}

function returnToAppointment(event: MouseEvent) {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  const previous = router.options.history.state.back
  if (typeof previous === 'string') {
    const detail = router.resolve(previous)
    if (detail.name === 'appointments' && detail.query.appointmentNo === appointmentNo.value) {
      router.back()
      return
    }
  }
  void router.replace(appointmentDetail.value)
}

async function ask(text = question.value) {
  const id = taskId.value
  const message = text.trim()
  if (!id || !message || sending.value) return
  const pending = readPending<MessageIntent>(messageKey(id))
  const original = pending?.text === message && !pending.retryRequired
    ? pending : { text: message, requestId: crypto.randomUUID() }
  writePending(messageKey(id), original)
  if (question.value.trim() === message) question.value = ''
  pendingQuestion.value = message
  partialReply.value = ''
  unresolved.value = null
  sending.value = true
  try {
    const result = await streamMessage(id, message, {
      onDelta: text => { if (taskId.value === id) partialReply.value += text },
      onEvent: () => {},
    }, original.requestId)
    if (result.events.some(event => event.type === 'MESSAGE_IN_PROGRESS')) {
      if (taskId.value === id) unresolved.value = { text: message, message: result.message }
      return
    }
    if (taskId.value === id) {
      task.value = result.task
      const submitted = result.task.turns.find(turn => turn.clientRequestId === original.requestId)
      const needsRetry = !!submitted && isUnavailable(submitted)
      if (readPending<MessageIntent>(messageKey(id))?.requestId === original.requestId) {
        writePending(messageKey(id), needsRetry ? { ...original, retryRequired: true } : null)
      }
    }
  } catch {
    if (taskId.value === id) {
      unresolved.value = { text: message, message: '这次查询未能完成，结果尚未确认。' }
    }
  } finally {
    if (taskId.value === id) {
      sending.value = false
      pendingQuestion.value = ''
      partialReply.value = ''
    }
  }
}
</script>

<template>
  <AppShell class="visit-frame">
    <div class="visit-page page-wrap">
      <a class="visit-back" :href="router.resolve(appointmentDetail).href" @click="returnToAppointment">
        <ArrowLeft :size="16" aria-hidden="true" />返回原预约
      </a>
      <h1>就诊准备</h1>

      <p v-if="loading" class="visit-notice" role="status">
        <LoaderCircle :size="17" class="spin" aria-hidden="true" />正在读取本次预约…
      </p>
      <div v-else-if="!appointment" class="visit-notice visit-error visit-recovery">
        <p role="alert">
          <CircleHelp :size="17" aria-hidden="true" />{{ loadError || '暂时无法读取本次预约。' }}
        </p>
        <button class="button-secondary" type="button" @click="loadAttempt++">重试加载</button>
      </div>

      <div v-else class="visit-layout">
        <aside class="visit-summary" aria-labelledby="visit-summary-title">
          <header>
            <h2 id="visit-summary-title"><CalendarDays :size="17" aria-hidden="true" />本次预约</h2>
            <time :datetime="appointment.appointmentDate">{{ formatAppointmentDate(appointment.appointmentDate) }}</time>
            <p>{{ appointment.timeRange }}</p>
          </header>
          <dl>
            <div><dt>科室</dt><dd>{{ appointment.departmentName }}</dd></div>
            <div><dt>医生</dt><dd>{{ appointment.doctorName }}</dd></div>
            <div><dt>预约编号</dt><dd class="visit-number">{{ appointment.appointmentNo }}</dd></div>
            <div><dt>预约方式</dt><dd>{{ channelLabel(appointment.bookedVia) }}</dd></div>
          </dl>
        </aside>

        <section class="visit-dialog" aria-labelledby="visit-prompts-title">
          <header class="visit-prompts">
            <h2 id="visit-prompts-title">想了解这次就诊的什么？</h2>
            <div class="visit-prompt-list">
              <button v-for="{ text, icon } in examples" :key="text" type="button"
                :aria-disabled="sending" @click="ask(text)">
                <component :is="icon" :size="17" class="visit-prompt-icon" aria-hidden="true" />
                <span>{{ text }}</span>
                <ChevronRight :size="15" class="visit-prompt-arrow" aria-hidden="true" />
              </button>
            </div>
          </header>

          <div class="visit-turns" aria-live="polite" aria-relevant="additions text">
            <article v-for="{ key, turn } in displayedTurns" :key="key" class="visit-turn">
              <p class="visit-question">{{ turn.userMessage }}</p>
              <div v-if="isUnavailable(turn)" class="visit-notice visit-error visit-recovery">
                <p v-if="updatingLastTurn && visibleRetry?.key === key && sending" role="status">
                  <LoaderCircle :size="16" class="spin" aria-hidden="true" />正在重新查询资料…
                </p>
                <p v-else :role="visibleRetry?.key === key ? 'alert' : undefined">
                  <CircleHelp :size="17" aria-hidden="true" />
                  {{ updatingLastTurn && visibleRetry?.key === key && unresolved ? unresolved.message : turn.assistantMessage }}
                </p>
                <button v-if="visibleRetry?.key === key && (!activeQuestion || updatingLastTurn)"
                  class="button-secondary" type="button"
                  :aria-disabled="sending" @click="ask(turn.userMessage)">重试查询</button>
              </div>
              <div v-else-if="turn.assistantMessage" class="visit-reply">
                <p class="visit-reply-text">{{ turn.assistantMessage }}</p>
                <KnowledgeSources :sources="sources?.[turn.turnNo]?.sources ?? []" />
              </div>
            </article>
          </div>
          <article v-if="activeQuestion && !updatingLastTurn" class="visit-turn visit-pending">
            <p class="visit-question">{{ activeQuestion }}</p>
            <div v-if="unresolved" class="visit-notice visit-error visit-recovery">
              <p role="alert"><CircleHelp :size="17" aria-hidden="true" />{{ unresolved.message }}</p>
              <button class="button-secondary" type="button" @click="ask(unresolved.text)">重试查询</button>
            </div>
            <div v-else class="visit-reply">
              <p v-if="partialReply" class="visit-reply-text">{{ partialReply }}</p>
              <p v-else class="visit-searching" role="status">
                <LoaderCircle :size="16" class="spin" aria-hidden="true" />正在查询资料…
              </p>
            </div>
          </article>

          <form class="visit-composer" @submit.prevent="ask()">
            <label for="visit-question">就诊准备问题</label>
            <textarea id="visit-question" v-model="question" rows="2" maxlength="1500"
              :disabled="sending" placeholder="例如，这次就诊需要带哪些证件？" />
            <div class="visit-composer-footer">
              <p>检查和用药要求，请以医生或检查科室的说明为准。</p>
              <button type="submit" class="button-primary" :disabled="sending || !question.trim()">
                <Send :size="15" aria-hidden="true" />发送
              </button>
            </div>
          </form>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.visit-frame {
  min-width: 0;
  color: #233651;
  background: radial-gradient(ellipse at 85% 10%, #f1ebf4, transparent 58%), linear-gradient(140deg, #dce7f1, #e5e7f0 77%);
}
.visit-frame :deep(.site-header), .visit-frame :deep(.site-footer) {
  background: #f8fafc;
  border-color: #ccdbe8;
}
.visit-frame :deep(.site-footer) { margin-top: 0; }
.visit-page { width: min(1080px, calc(100% - 48px)); padding-block: clamp(22px, 4vh, 46px); }
.visit-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 38px;
  margin-bottom: 4px;
  color: var(--brand-plum);
  font-size: 14px;
  font-weight: 700;
}
.visit-back:hover { text-decoration: underline; text-underline-offset: 3px; }
.visit-page h1 { margin: 0 0 20px; font-size: clamp(27px, 3vw, 38px); letter-spacing: -.025em; }
.visit-page h2 { margin: 0; font-size: 17px; }
.visit-layout {
  display: grid;
  grid-template-areas: "dialog summary";
  grid-template-columns: minmax(0, 1fr) minmax(248px, .4fr);
  align-items: start;
  gap: 16px;
}
.visit-dialog, .visit-summary {
  min-width: 0;
  border: 1px solid #dbe3ee;
  border-radius: 15px;
  background: var(--brand-paper);
  box-shadow: 0 8px 20px #283f5b12;
}
.visit-dialog { grid-area: dialog; }
.visit-summary { grid-area: summary; }
.visit-summary header {
  padding: 16px 18px;
  border-radius: 14px 14px 0 0;
  background: linear-gradient(115deg, #eee4f3, #faebe8);
  color: var(--brand-plum-dark);
}
.visit-summary h2 { display: flex; align-items: center; gap: 7px; margin-bottom: 12px; }
.visit-summary time { font-size: 13px; font-weight: 700; }
.visit-summary header p {
  margin: 3px 0 0;
  font-size: clamp(24px, 3vw, 32px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.12;
}
.visit-summary dl { display: grid; gap: 8px; margin: 0; padding: 14px 18px; }
.visit-summary dl div { display: grid; grid-template-columns: 4.5em minmax(0, 1fr); align-items: baseline; gap: 6px; }
.visit-summary dt { color: var(--brand-muted); font-size: 13px; }
.visit-summary dd { margin: 0; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.visit-number { font-variant-numeric: tabular-nums; }
.visit-prompts { padding: 18px; border-radius: 14px 14px 0 0; background: #eef4fa; }
.visit-prompt-list { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.visit-prompt-list button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  padding: 6px 9px;
  border: 1px solid #ccdbea;
  border-radius: 9px;
  background: #fff;
  color: #2a4463;
  text-align: left;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}
.visit-prompt-list button:hover:not([aria-disabled="true"]) { background: #e3eef9; border-color: #a5bcd5; }
.visit-prompt-list button[aria-disabled="true"] { opacity: .62; cursor: wait; }
.visit-prompt-icon, .visit-prompt-arrow { flex: none; color: #375c89; }
.visit-prompt-arrow { margin-left: auto; }
.visit-turns:not(:empty) { display: grid; gap: 15px; padding: 16px 18px 0; }
.visit-turn { display: grid; gap: 8px; }
.visit-pending { padding: 16px 18px 0; }
.visit-question, .visit-reply-text, .visit-searching { margin: 0; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.visit-question {
  justify-self: end;
  max-width: 90%;
  padding: 8px 12px;
  border-radius: 10px 10px 3px 10px;
  background: var(--brand-lilac);
  color: var(--brand-plum-dark);
  font-size: 14px;
}
.visit-reply {
  min-width: 0;
  padding: 12px 14px;
  border: 1px solid #d8e7e2;
  border-radius: 3px 10px 10px;
  background: #f0f7f4;
  color: #223e36;
  font-size: 14px;
}
.visit-reply-text { max-width: 68ch; }
.visit-searching { display: flex; align-items: center; gap: 7px; }
.visit-reply :deep(.knowledge-sources) { margin-top: 12px; padding-top: 9px; border-top: 1px solid #d3e3dd; }
.visit-reply :deep(.sources-label), .visit-reply :deep(.sources-label span) { color: #3d6155; }
.visit-reply :deep(.source) { border-color: #cdded8; border-radius: 8px; background: #fff; }
.visit-reply :deep(.source summary) {
  display: block;
  position: relative;
  min-height: 42px;
  padding: 11px 34px 10px 11px;
  border-radius: 7px;
  color: #284d42;
  font-size: 13px;
  font-weight: 700;
  list-style: none;
}
.visit-reply :deep(.source summary::-webkit-details-marker) { display: none; }
.visit-reply :deep(.source summary::after) {
  content: '';
  position: absolute;
  top: 17px;
  right: 14px;
  width: 7px;
  height: 7px;
  border-right: 2px solid currentColor;
  border-bottom: 2px solid currentColor;
  transform: rotate(45deg);
}
.visit-reply :deep(.source[open] summary::after) { transform: translateY(3px) rotate(225deg); }
.visit-reply :deep(.source summary:hover) { background: #e8f3ed; }
.visit-reply :deep(.source summary:focus-visible) { outline: 2px solid var(--brand-plum); outline-offset: -2px; }
.visit-reply :deep(.source-body p.source-meta) { color: #495e56; overflow-wrap: anywhere; }
.visit-reply :deep(.source blockquote) { color: #304943; font-size: 13px; }
.visit-composer { display: grid; gap: 7px; padding: 16px 18px 18px; }
.visit-composer label { font-size: 13px; font-weight: 700; }
.visit-composer textarea {
  width: 100%;
  padding: 9px 10px;
  border: 1px solid #cfdae7;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  font-size: 16px;
  resize: vertical;
}
.visit-composer textarea::placeholder { color: #5d697a; font-size: 14px; }
.visit-composer-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px 12px; }
.visit-composer-footer p { margin: 0; color: var(--brand-muted); font-size: 12px; line-height: 1.5; }
.visit-composer-footer button { flex: none; }
.visit-notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid #dce6ef;
  border-radius: 9px;
  background: var(--brand-paper);
  font-size: 14px;
}
.visit-notice svg, .visit-searching svg { flex: none; }
.visit-error { border-color: #edceca; background: #fff4f2; color: #863c39; }
.visit-recovery { align-items: center; justify-content: space-between; flex-wrap: wrap; }
.visit-recovery p { display: flex; align-items: flex-start; gap: 8px; flex: 1 1 215px; margin: 0; overflow-wrap: anywhere; }
.visit-recovery button[aria-disabled="true"] { opacity: .62; cursor: wait; }

@media (max-width: 720px) {
  .visit-page { width: calc(100% - 24px); padding-block: 14px 22px; }
  .visit-layout { grid-template-areas: "summary" "dialog"; grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .visit-summary dl { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 12px; }
  .visit-summary dl div { display: block; }
  .visit-summary dd { margin-top: 2px; }
  .visit-prompts, .visit-composer { padding: 14px; }
  .visit-turns:not(:empty), .visit-pending { padding-inline: 14px; }
}
@media (max-width: 440px) {
  .visit-prompt-list { display: grid; gap: 6px; }
}
</style>
