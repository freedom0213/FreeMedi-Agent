import axios from 'axios'

/** The demo identity is resolved server-side from this header, defaulting to the demo patient. */
export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
})

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const message = error.response?.data?.message ?? error.response?.data?.error
    if (typeof message === 'string' && message.trim()) return message
    if (error.code === 'ECONNABORTED') return '请求等待时间较长，请确认后端服务仍在运行。'
    if (!error.response) return '暂时无法连接服务，请确认后端已启动并完成数据库初始化。'
  }
  return '请求失败，请稍后重试。'
}
