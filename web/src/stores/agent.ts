import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import { checkAppointmentConfirmation, confirmAppointment, createTask, getTask, replayEvents, selectSlot, streamMessage, updateRequirements } from '../api/agent'
import { patientSession } from '../api/auth'
import type { AgentEvent, AgentResult, CandidateBatch, ConfirmationIntent, RequirementChanges, Requirements, TaskSnapshot } from '../types/agent'
import { readPending, writePending } from './pending'

interface MessageIntent { taskId: string; requestId: string; text: string; retryRequired?: boolean }

export const useAgentStore = defineStore('agent', () => {
  const storageKey = `freemedi-agent-task-id:${patientSession.value!.patientId}`
  const confirmationKey = storageKey + ':confirmation'
  const messageKey = storageKey + ':message'
  const taskId = ref(sessionStorage.getItem(storageKey))
  const task = ref<TaskSnapshot | null>(null)
  const events = ref<AgentEvent[]>([])
  const busy = ref(false)
  const error = ref('')
  const streamingReply = ref('')
  const pendingConfirmation = ref(readPending<ConfirmationIntent>(confirmationKey))
  const pendingMessage = ref(readPending<MessageIntent>(messageKey))
  const canRetryConfirmation = ref(false)
  const confirmationNotice = ref('')
  const restoreFailed = ref(false)
  let stopReplay: (() => void) | undefined

  const plan = computed(() => unwrap(task.value?.activePlan))
  const toolResult = computed(() => unwrap(task.value?.lastToolResult))
  const requirements = computed(() => task.value?.facts.requirements as Requirements | undefined)
  const candidateBatch = computed(() => task.value?.facts.candidateBatch as CandidateBatch | undefined)
  const recommendedSlot = computed(() => {
    const draft = plan.value?.draft
    if (!draft) return undefined
    return (candidateBatch.value?.slots ?? toolResult.value?.data?.slots)?.find((slot) => slot.slotId === draft.slotId)
  })

  function setTaskId(id: string | null) {
    taskId.value = id
    if (id) sessionStorage.setItem(storageKey, id)
    else sessionStorage.removeItem(storageKey)
  }

  function appendEvents(incoming: AgentEvent[]) {
    const seen = new Set(events.value.map((event) => `${event.type}:${event.summary}`))
    events.value = [...events.value, ...incoming.filter((event) => !seen.has(`${event.type}:${event.summary}`))]
  }

  function watchEvents(id: string) {
    stopReplay?.()
    stopReplay = replayEvents(id, (event) => {
      if (event.type !== 'STREAM_COMPLETED') appendEvents([{ type: event.type, summary: event.summary }])
    })
  }

  async function restore() {
    restoreFailed.value = false
    if (!taskId.value && pendingConfirmation.value) setTaskId(pendingConfirmation.value.taskId)
    if (!taskId.value) return
    try {
      task.value = await getTask(taskId.value)
      syncConfirmation(task.value)
      if (!pendingMessage.value?.retryRequired && pendingMessage.value?.taskId === taskId.value && task.value.turns.some(turn =>
        turn.clientRequestId === pendingMessage.value?.requestId && !!turn.assistantMessage)) {
        setPendingMessage(null)
      }
      watchEvents(taskId.value)
    } catch (failure) {
      task.value = null
      const status = axios.isAxiosError(failure) ? failure.response?.status : undefined
      if (status && [400, 403, 404].includes(status) && !pendingConfirmation.value) {
        setTaskId(null)
        setPendingMessage(null)
      } else restoreFailed.value = true
    }
  }

  async function submitMessage(message: string) {
    error.value = ''
    busy.value = true
    streamingReply.value = ''
    try {
      const id = await ensureTask()
      const messageIntent = pendingMessage.value?.taskId === id && pendingMessage.value.text === message
        && !pendingMessage.value.retryRequired
        ? pendingMessage.value
        : { taskId: id, requestId: crypto.randomUUID(), text: message }
      setPendingMessage(messageIntent)
      const result = await streamMessage(id, message, {
        onDelta: (text) => { streamingReply.value += text },
        onEvent: (event) => appendEvents([event]),
      }, messageIntent.requestId)
      applyResult(result)
      if (result.events.some(event => event.type === 'MESSAGE_IN_PROGRESS')) {
        error.value = result.message
        throw new Error(result.message)
      }
      const needsRetry = result.events.some(event => ['MODEL_RETRY_REQUIRED', 'TOOL_RETRY_REQUIRED'].includes(event.type))
      setPendingMessage(needsRetry ? { ...messageIntent, retryRequired: true } : null)
      streamingReply.value = ''
      watchEvents(taskId.value!)
      return result
    } finally {
      streamingReply.value = ''
      busy.value = false
    }
  }

  async function ensureTask() {
    if (taskId.value) return taskId.value
    const created = await createTask('智能导诊与预约')
    setTaskId(created.taskId)
    events.value = []
    return created.taskId
  }

  async function saveRequirements(changes: RequirementChanges) {
    busy.value = true
    error.value = ''
    try {
      const id = await ensureTask()
      const result = await updateRequirements(id, changes)
      if (result.events.some(event => event.type === 'TOOL_RETRY_REQUIRED')) appendEvents(result.events)
      else applyResult(result)
      watchEvents(id)
      return result
    } finally {
      busy.value = false
    }
  }

  async function chooseCandidate(slotId: number) {
    if (!taskId.value || !candidateBatch.value) throw new Error('请先查找号源')
    busy.value = true
    error.value = ''
    try {
      const result = await selectSlot(taskId.value, candidateBatch.value.id, slotId)
      applyResult(result)
      watchEvents(taskId.value)
      return result
    } finally {
      busy.value = false
    }
  }

  async function confirmPlan() {
    if (!taskId.value || !plan.value || pendingConfirmation.value || busy.value) {
      throw new Error('当前没有可确认的预约方案')
    }
    setPendingConfirmation({
      taskId: taskId.value,
      planId: planIdOf(plan.value.id),
      planVersion: plan.value.version,
      idempotencyKey: crypto.randomUUID(),
    })
    return runConfirmation('submit')
  }

  async function runConfirmation(action: 'submit' | 'check' | 'retry') {
    const original = pendingConfirmation.value
    if (!original || busy.value || (action === 'retry' && (!canRetryConfirmation.value || !original.idempotencyKey))) return
    error.value = ''
    busy.value = true
    canRetryConfirmation.value = false
    try {
      const result = action === 'check' ? await checkAppointmentConfirmation(original)
        : await confirmAppointment(original.taskId, original.planId, original.planVersion,
          original.idempotencyKey, action === 'retry')
      applyResult(result)
      confirmationNotice.value = result.success ? '' : result.message
      if (result.success || !['NOT_FOUND', 'OUTCOME_UNKNOWN', 'VERIFY_PENDING'].includes(result.code ?? '')) {
        setPendingConfirmation(null)
      }
      syncConfirmation(result.task)
      canRetryConfirmation.value = result.code === 'NOT_FOUND' && pendingConfirmation.value?.idempotencyKey === original.idempotencyKey
      watchEvents(original.taskId)
      return result
    } catch {
      confirmationNotice.value = '提交结果暂未确定，请核验原预约。'
    } finally {
      busy.value = false
    }
  }

  function syncConfirmation(snapshot: TaskSnapshot) {
    const attempt = snapshot.facts.confirmationAttempt as
      { planId?: string; version?: number; idempotencyKey?: string; message?: string; code?: string } | undefined
    const selected = unwrap(snapshot.activePlan)
    const recovering = snapshot.status === 'EXECUTING' || snapshot.status === 'VERIFYING'
      || (snapshot.status === 'FAILED' && selected?.status === 'EXECUTED')
    if (recovering && selected && taskId.value) {
      const planId = planIdOf(selected.id)
      setPendingConfirmation({
        taskId: taskId.value, planId, planVersion: selected.version,
        idempotencyKey: attempt?.planId === planId ? attempt.idempotencyKey ?? '' : '',
      })
    } else if (snapshot.status === 'COMPLETED' ||
      (attempt?.code && pendingConfirmation.value?.idempotencyKey === attempt.idempotencyKey)) {
      setPendingConfirmation(null)
    }
    if (snapshot.status === 'SEARCHING' && attempt?.message) confirmationNotice.value = attempt.message
  }

  function setPendingConfirmation(intent: ConfirmationIntent | null) {
    pendingConfirmation.value = intent
    writePending(confirmationKey, intent)
  }

  function setPendingMessage(intent: MessageIntent | null) {
    pendingMessage.value = intent
    writePending(messageKey, intent)
  }

  function applyResult(result: AgentResult) {
    task.value = result.task
    appendEvents(result.events)
  }

  function startNewTask() {
    if (pendingConfirmation.value) return
    stopReplay?.()
    stopReplay = undefined
    setTaskId(null)
    task.value = null
    events.value = []
    error.value = ''
    streamingReply.value = ''
    confirmationNotice.value = ''
    restoreFailed.value = false
    setPendingMessage(null)
  }

  function stopWatching() {
    stopReplay?.()
    stopReplay = undefined
  }

  return {
    taskId, task, events, busy, error, streamingReply, plan, toolResult, recommendedSlot,
    requirements, candidateBatch, restore, submitMessage, saveRequirements, chooseCandidate,
    confirmPlan, runConfirmation, pendingConfirmation, pendingMessage, canRetryConfirmation,
    confirmationNotice, restoreFailed, startNewTask, stopWatching,
  }
})

function unwrap<T>(value: T | { present: boolean; value?: T } | null | undefined): T | undefined {
  if (value && typeof value === 'object' && 'present' in value) {
    return value.present ? value.value : undefined
  }
  return value ?? undefined
}

function planIdOf(value: { value: string } | string): string {
  return typeof value === 'string' ? value : value.value
}
