import axios from 'axios'
import { shallowRef } from 'vue'

interface PatientSession {
  patientId: string
  displayName: string
}

interface CsrfResponse {
  headerName: string
  token: string
  patientId: string | null
}

const authHttp = axios.create({ baseURL: '/api/v1/auth', timeout: 10_000 })
const sessionChanges = new BroadcastChannel('freemedi-patient-session')
sessionChanges.onmessage = () => window.location.reload()

export const patientSession = shallowRef<PatientSession | null>(null)

const patientPaths = new Set(['/guide', '/booking', '/appointments'])

export function loginTarget(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/')) return '/'
  try {
    const target = new URL(value, window.location.origin)
    const preparation = /^\/appointments\/[A-Za-z0-9-]+\/preparation$/.test(target.pathname)
    if (target.origin === window.location.origin && (patientPaths.has(target.pathname) || preparation)) {
      return target.pathname + target.search + target.hash
    }
  } catch {
    // Ignore invalid or external return URLs.
  }
  return '/'
}

export function requireLogin() {
  const next = loginTarget(window.location.pathname + window.location.search + window.location.hash)
  window.location.replace(next === '/' ? '/login' : '/login?next=' + encodeURIComponent(next))
}

export async function readSession() {
  try {
    const { data } = await authHttp.get<PatientSession>('/session')
    patientSession.value = data
  } catch (error) {
    patientSession.value = null
    if (!axios.isAxiosError(error) || error.response?.status !== 401) throw error
  }
  return patientSession.value
}

export async function login(username: string, password: string, next: string) {
  const { data } = await authHttp.get<CsrfResponse>('/csrf')
  await authHttp.post('/login', new URLSearchParams({ username, password }), {
    headers: { [data.headerName]: data.token },
  })
  sessionChanges.postMessage('changed')
  window.location.replace(next)
}

export async function patientCsrfHeaders(): Promise<Record<string, string>> {
  const { data } = await authHttp.get<CsrfResponse>('/csrf')
  if (data.patientId !== patientSession.value?.patientId) {
    if (data.patientId === null) requireLogin()
    else window.location.reload()
    throw new Error('患者会话已变化')
  }
  return { [data.headerName]: data.token }
}

export async function logout() {
  await authHttp.post('/logout', undefined, { headers: await patientCsrfHeaders() })
  sessionChanges.postMessage('changed')
  window.location.replace('/login')
}
