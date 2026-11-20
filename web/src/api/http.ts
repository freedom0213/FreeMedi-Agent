import axios from 'axios'
import { patientCsrfHeaders, requireLogin } from './auth'

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use(async (config) => {
  if (config.method === 'post') config.headers.set(await patientCsrfHeaders())
  return config
})

http.interceptors.response.use(undefined, (error) => {
  if (error.response?.status === 401) requireLogin()
  return Promise.reject(error)
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
