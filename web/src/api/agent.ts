import axios from 'axios'
import type { AgentResult, TaskCreated, TaskSnapshot } from '../types/agent'

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
})

export async function createTask(title: string): Promise<TaskCreated> {
  const { data } = await http.post<TaskCreated>('/tasks', { title })
  return data
}

export async function sendMessage(taskId: string, message: string): Promise<AgentResult> {
  const { data } = await http.post<AgentResult>(`/tasks/${taskId}/messages`, { message })
  return data
}

export async function streamMessage(
  taskId: string,
  message: string,
  handlers: {
    onDelta: (text: string) => void
    onEvent: (event: { type: string; summary: string }) => void
  },
): Promise<AgentResult> {
  const response = await fetch(`/api/v1/tasks/${taskId}/messages/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
  if (!response.ok || !response.body) {
    throw new Error(`stream request failed: ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let result: AgentResult | undefined
  const consume = (block: string) => {
    const lines = block.split(/\r?\n/)
    const eventType = lines.find((line) => line.startsWith('event:'))?.slice(6).trim()
    const data = lines.filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trim()).join('\n')
    if (!eventType || !data) return
    let value: unknown = data
    try { value = JSON.parse(data) } catch { /* plain SSE data */ }
    if (eventType === 'MESSAGE_DELTA' && typeof value === 'object' && value && 'text' in value) {
      handlers.onDelta(String((value as { text: unknown }).text))
    } else if (eventType === 'AGENT_EVENT' && typeof value === 'object' && value && 'type' in value) {
      const event = value as { type: string; summary: string }
      handlers.onEvent(event)
    } else if (eventType === 'RESULT') {
      result = value as AgentResult
    } else if (eventType === 'STREAM_ERROR') {
      throw new Error(typeof value === 'object' && value && 'message' in value
        ? String((value as { message: unknown }).message) : 'stream failed')
    }
  }
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done })
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() ?? ''
    blocks.forEach(consume)
    if (done) break
  }
  if (!result) throw new Error('stream ended without a result')
  return result
}

export async function confirmAppointment(
  taskId: string,
  planId: string,
  planVersion: number,
  idempotencyKey: string,
): Promise<AgentResult> {
  const { data } = await http.post<AgentResult>(`/tasks/${taskId}/confirm`, {
    planId,
    planVersion,
    idempotencyKey,
  })
  return data
}

export async function getTask(taskId: string): Promise<TaskSnapshot> {
  const { data } = await http.get<TaskSnapshot>(`/tasks/${taskId}`)
  return data
}

export function replayEvents(taskId: string, onEvent: (event: { type: string; summary: string }) => void) {
  const source = new EventSource(`/api/v1/tasks/${taskId}/events`)
  const eventTypes = [
    'TASK_CREATED', 'TASK_STARTED', 'UNDERSTANDING', 'SEARCHING', 'NEED_MORE_INFO', 'QUESTION_ASKED',
    'PROPOSAL_READY', 'PLAN_PROPOSED', 'CONFIRMATION_REQUIRED', 'AWAITING_CONFIRMATION',
    'EXECUTION_STARTED', 'EXECUTION_VERIFIED', 'TASK_COMPLETED', 'TASK_FAILED',
    'STATE_CHANGED', 'EXECUTING', 'VERIFYING', 'COMPLETED', 'FAILED',
    'READ_TOOL_STARTED', 'READ_TOOL_SUCCEEDED', 'READ_TOOL_FAILED', 'MODEL_RETRY_REQUIRED', 'STREAM_COMPLETED',
  ]

  for (const type of eventTypes) {
    source.addEventListener(type, (event) => {
      const message = event as MessageEvent<string>
      onEvent({ type, summary: message.data })
      if (type === 'STREAM_COMPLETED') source.close()
    })
  }
  source.onerror = () => source.close()
  return () => source.close()
}

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const message = error.response?.data?.message
    if (typeof message === 'string' && message.trim()) return message
    if (error.code === 'ECONNABORTED') return '请求等待时间较长，请确认后端服务仍在运行。'
    if (!error.response) return '暂时无法连接服务，请确认后端已启动并完成数据库初始化。'
  }
  return '请求失败，请稍后重试。'
}
