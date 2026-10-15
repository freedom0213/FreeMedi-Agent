import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { confirmAppointment, createTask, getTask, replayEvents, streamMessage } from '../api/agent'
import type { AgentEvent, AgentResult, TaskSnapshot } from '../types/agent'

const STORAGE_KEY = 'freemedi-agent-task-id'

export const useAgentStore = defineStore('agent', () => {
  const taskId = ref(sessionStorage.getItem(STORAGE_KEY))
  const task = ref<TaskSnapshot | null>(null)
  const events = ref<AgentEvent[]>([])
  const busy = ref(false)
  const error = ref('')
  const streamingReply = ref('')
  let stopReplay: (() => void) | undefined

  const plan = computed(() => unwrap(task.value?.activePlan))
  const toolResult = computed(() => unwrap(task.value?.lastToolResult))
  const recommendedSlot = computed(() => {
    const draft = plan.value?.draft
    if (!draft) return undefined
    return toolResult.value?.data?.slots?.find((slot) => Number(slot.slotId) === Number(draft.slotId))
  })

  function setTaskId(id: string | null) {
    taskId.value = id
    if (id) sessionStorage.setItem(STORAGE_KEY, id)
    else sessionStorage.removeItem(STORAGE_KEY)
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
    if (!taskId.value) return
    try {
      task.value = await getTask(taskId.value)
      watchEvents(taskId.value)
    } catch {
      task.value = null
      setTaskId(null)
    }
  }

  async function submitMessage(message: string) {
    error.value = ''
    busy.value = true
    streamingReply.value = ''
    try {
      if (!taskId.value) {
        const created = await createTask('智能导诊与预约')
        setTaskId(created.taskId)
        events.value = []
      }
      const result = await streamMessage(taskId.value!, message, {
        onDelta: (text) => { streamingReply.value += text },
        onEvent: (event) => appendEvents([event]),
      })
      applyResult(result)
      streamingReply.value = ''
      watchEvents(taskId.value!)
      return result
    } finally {
      busy.value = false
    }
  }

  async function confirmPlan() {
    if (!taskId.value || !plan.value) throw new Error('当前没有可确认的预约方案')
    error.value = ''
    busy.value = true
    try {
      const result = await confirmAppointment(
        taskId.value,
        planIdOf(plan.value.id),
        plan.value.version,
        crypto.randomUUID(),
      )
      applyResult(result)
      watchEvents(taskId.value)
      return result
    } finally {
      busy.value = false
    }
  }

  function applyResult(result: AgentResult) {
    task.value = result.task
    appendEvents(result.events)
  }

  function startNewTask() {
    stopReplay?.()
    stopReplay = undefined
    setTaskId(null)
    task.value = null
    events.value = []
    error.value = ''
    streamingReply.value = ''
  }

  function stopWatching() {
    stopReplay?.()
    stopReplay = undefined
  }

  return {
    taskId, task, events, busy, error, streamingReply, plan, toolResult, recommendedSlot,
    restore, submitMessage, confirmPlan, startNewTask, stopWatching,
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
