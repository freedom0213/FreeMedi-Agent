import axios from 'axios'
import { shallowRef } from 'vue'
import { announceSessionChange } from './auth'

interface AdminSession { adminId: string; displayName: string }
interface CsrfResponse { headerName: string; token: string; adminId: string | null }
type SessionState = 'signed-in' | 'signed-out' | 'forbidden' | 'unavailable'
type ArchiveResult = 'archived' | 'conflict' | 'session' | 'unknown'
export type VersionStatus = 'PUBLISHED' | 'ARCHIVED' | 'DRAFT'
export type IndexStatus = 'INDEXED' | 'PENDING' | 'FAILED'
export type DocumentType = 'APPOINTMENT_RULE' | 'VISIT_NOTICE' | 'DEPARTMENT_INTRO' | 'DOCTOR_PROFILE' | 'PREPARATION'

export interface VersionOption {
  version: number
  status: VersionStatus
  publishedAt: string | null
}

export interface DocumentVersion extends VersionOption {
  docNo: string
  title: string
  docType: DocumentType
  source: string
  indexed: number
  pending: number
  failed: number
}

export interface DocumentRow {
  document: DocumentVersion
  versionCount: number
  draftVersion?: number | null
}

export interface DocumentDetail {
  document: DocumentVersion
  versions: VersionOption[]
  chunks: {
    chunkNo: number
    heading: string | null
    content: string
    charCount: number
    embeddingStatus: IndexStatus
    retrievable: boolean
    failureReason: string | null
  }[]
}

export interface AgentTaskSummary {
  taskId: string
  taskType: string
  status: string
  turnCount: number
  updatedAt: string
}

export interface AgentTraceEntry {
  sequence: number
  recordedAt: string
  type: string
  turnNo: number
  stepNo: number
  attempt: number
  outcome: string
  reason: string | null
  durationMs: number | null
  tool: string | null
  resultCount: number | null
  modelVersion?: string | null
}

export interface AgentTracePage {
  task: AgentTaskSummary
  entries: AgentTraceEntry[]
  nextAfter: number | null
}

export interface RegisteredTool {
  name: string
  enabled?: boolean
  timeoutMs?: number | null
  defaultTimeoutMs?: number
  revision?: number
  updatedAt?: string | null
  updatedBy?: string | null
  description: string
  parameters: {
    name: string
    type: 'STRING' | 'INTEGER' | 'BOOLEAN'
    required: boolean
    description: string
  }[]
}

export interface AgentModelConfiguration {
  mode: 'DEEPSEEK' | 'DEMO' | 'COMPATIBLE' | 'UNKNOWN'
  active?: ModelRevision | null
  activatedAt?: string | null
  environmentBaseUrl?: string | null
  revision?: number
  secretStorageReady?: boolean
  models?: ModelRevision[]
  callPolicy: {
    modelTimeoutMs: number
    toolTimeoutMs: number
    requestTimeoutMs: number
    maxAttempts: number
    retryableReasons: string[]
  }
  provider: {
    modelName: string | null
    sdkMaxRetries: number
    maxOutputCorrections: number
  } | null
}

export interface ModelRevision {
  id: number
  profileId: string
  revision: number
  label: string
  baseUrl: string
  modelName: string
  probeCode: string | null
  probeMs: number | null
  testedAt: string | null
}

export interface ModelDraft {
  profileId: string | null
  expectedId: number
  label: string
  baseUrl: string
  modelName: string
  apiKey: string
}

export type AgentTimeouts = Pick<AgentModelConfiguration['callPolicy'],
  'modelTimeoutMs' | 'toolTimeoutMs' | 'requestTimeoutMs'>

export interface KnowledgeImportPreview {
  docNo: string
  title: string
  docType: DocumentType
  source: string
  departmentId: string | null
  doctorId: string | null
  disposition: 'NEW_DOCUMENT' | 'NEW_VERSION' | 'DUPLICATE'
  proposedVersion: number | null
  published: Pick<VersionOption, 'version' | 'status'> | null
  duplicate: Pick<VersionOption, 'version' | 'status'> | null
  chunks: { chunkNo: number; heading: string | null; content: string; charCount: number }[]
  contentHash: string
  latestVersion: number
}

type ImportPreviewResult =
  | { kind: 'ready'; preview: KnowledgeImportPreview }
  | { kind: 'issue'; message: string; signIn?: boolean }

export const maxMarkdownBytes = 256 * 1024

type DraftResult<T> = { kind: 'ready'; data: T } | { kind: 'issue'; message: string; signIn?: boolean }

const adminHttp = axios.create({ baseURL: '/api/v1/admin', timeout: 10_000 })
export const adminSession = shallowRef<AdminSession | null>(null)

export function adminHttpStatus(error: unknown) {
  return axios.isAxiosError(error) ? error.response?.status : undefined
}

export function adminLoginTarget(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('/')) return '/admin'
  try {
    const target = new URL(value, window.location.origin)
    const adminPath = /^\/admin(?:\/tool-logs|\/tool-registry|\/model-config|\/knowledge(?:\/import\/preview|\/[^/]+)?)?\/?$/.test(target.pathname)
    if (target.origin === window.location.origin && adminPath) {
      return target.pathname + target.search + target.hash
    }
  } catch {
    // An invalid URL falls back to the workbench.
  }
  return '/admin'
}

export async function readAdminSession(): Promise<SessionState> {
  try {
    const { data } = await adminHttp.get<AdminSession>('/auth/session')
    adminSession.value = data
    return 'signed-in'
  } catch (error) {
    adminSession.value = null
    const status = adminHttpStatus(error)
    if (status === 401) return 'signed-out'
    if (status === 403) return 'forbidden'
    return 'unavailable'
  }
}

export async function adminLogin(username: string, password: string, next: string) {
  const { data } = await adminHttp.get<CsrfResponse>('/auth/csrf')
  await adminHttp.post('/auth/login', new URLSearchParams({ username, password }), {
    headers: { [data.headerName]: data.token },
  })
  announceSessionChange()
  window.location.replace(next)
}

export async function adminLogout() {
  const headers = await adminCsrfHeaders()
  if (headers) {
    await adminHttp.post('/auth/logout', undefined, { headers })
    announceSessionChange()
  }
  window.location.replace('/admin/login')
}

async function adminCsrfHeaders(signal?: AbortSignal) {
  const id = adminSession.value?.adminId
  if (!id) return null
  const { data } = await adminHttp.get<CsrfResponse>('/auth/csrf', { signal })
  return data.adminId === id && adminSession.value?.adminId === id ? { [data.headerName]: data.token } : null
}

export async function readDocuments(signal: AbortSignal) {
  const { data } = await adminHttp.get<DocumentRow[]>('/knowledge/documents', { signal })
  return data
}

export async function readDocument(docNo: string, version: string | undefined, signal: AbortSignal) {
  const { data } = await adminHttp.get<DocumentDetail>('/knowledge/documents/' + encodeURIComponent(docNo), {
    params: { version }, signal,
  })
  return data
}

export async function readRecentAgentTasks(signal: AbortSignal) {
  const { data } = await adminHttp.get<AgentTaskSummary[]>('/agent/tasks', { signal })
  return data
}

export async function readRegisteredTools(signal: AbortSignal) {
  const { data } = await adminHttp.get<RegisteredTool[]>('/agent/tools', { signal })
  return data
}

export async function readAgentModelConfiguration(signal: AbortSignal) {
  const { data } = await adminHttp.get<AgentModelConfiguration>('/agent/model-config', { signal })
  return data
}

async function writeAgentSettings<T>(method: 'post' | 'put', url: string, data: unknown, timeout = 10_000) {
  const headers = await adminCsrfHeaders()
  if (!headers) throw new Error('管理员会话已失效，请重新登录。')
  return (await adminHttp.request<T>({ method, url, data, headers, timeout })).data
}

export function agentSettingsError(error: unknown) {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 401 || status === 403) return '管理员会话已失效，请重新登录。'
    const detail = error.response?.data?.error
    if (typeof detail === 'string' && /^[\u4e00-\u9fff]/u.test(detail)) return detail
    if (status === 409) return '配置已更新或尚未通过测试。请重新加载后核对。'
    if (status === 429) return '已有模型正在测试，请稍后重试。'
    if (status === 503) return '配置暂不可用，请检查服务端密钥设置后重试。'
    return '未能确认操作结果，请重新加载核对当前状态。'
  }
  return error instanceof Error ? error.message : '操作失败，请稍后重试。'
}

export const saveAgentModel = (draft: ModelDraft) =>
  writeAgentSettings<AgentModelConfiguration>('post', '/agent/model-config/models', draft)
export const testAgentModel = (id: number) =>
  writeAgentSettings<AgentModelConfiguration>('post', '/agent/model-config/models/' + id + '/test', undefined, 45_000)
export const activateAgentModel = (id: number | null, expectedRevision: number) =>
  writeAgentSettings<AgentModelConfiguration>('post', '/agent/model-config/activate', { id, expectedRevision })
export const updateAgentTimeouts = (timeout: AgentTimeouts, expectedRevision: number) =>
  writeAgentSettings<AgentModelConfiguration>('put', '/agent/model-config/policy', { ...timeout, expectedRevision })
export const updateRegisteredTool = (name: string, enabled: boolean, timeoutMs: number | null, expectedRevision: number) =>
  writeAgentSettings<RegisteredTool[]>('put', '/agent/tools/' + encodeURIComponent(name), { enabled, timeoutMs, expectedRevision })

export async function readAdminAgentTrace(taskId: string, after: number, signal: AbortSignal) {
  const { data } = await adminHttp.get<AgentTracePage>('/agent/tasks/' + encodeURIComponent(taskId) + '/trace', {
    signal, params: { after },
  })
  return data
}

export async function archiveDocument(docNo: string, version: number): Promise<ArchiveResult> {
  try {
    const headers = await adminCsrfHeaders()
    if (!headers) return 'session'
    await adminHttp.post('/knowledge/documents/' + encodeURIComponent(docNo) + '/archive', { version }, { headers })
    return 'archived'
  } catch (error) {
    const status = adminHttpStatus(error)
    if (status === 401 || status === 403) return 'session'
    if (status === 404 || status === 409) return 'conflict'
    return 'unknown'
  }
}

export async function previewMarkdown(file: File, signal: AbortSignal): Promise<ImportPreviewResult> {
  const signedOut = { kind: 'issue', message: '管理员会话已失效，请重新登录后再选文件。', signIn: true } as const
  try {
    const headers = await adminCsrfHeaders(signal)
    if (!headers) return signedOut
    const { data } = await adminHttp.post<KnowledgeImportPreview>('/knowledge/import-preview', file, {
      headers: { ...headers, 'Content-Type': 'text/markdown; charset=UTF-8' }, signal,
    })
    return { kind: 'ready', preview: data }
  } catch (error) {
    const status = adminHttpStatus(error)
    if (status === 401 || status === 403) return signedOut
    if (status === 413) return { kind: 'issue', message: '文件超过 256 KiB，请缩小文件后再预览。' }
    if (status === 400 && axios.isAxiosError<{ error?: string }>(error) && error.response?.data?.error) {
      return { kind: 'issue', message: error.response.data.error }
    }
    return { kind: 'issue', message: '暂时无法完成解析。文件仍已选中，可以重试。' }
  }
}

async function postDraft<T>(path: string, body: unknown, config: {
  headers?: Record<string, string>; signal?: AbortSignal; timeout?: number
} = {}): Promise<DraftResult<T>> {
  const signedOut = { kind: 'issue', message: '管理员会话已失效，请重新登录。', signIn: true } as const
  try {
    const headers = await adminCsrfHeaders(config.signal)
    if (!headers) return signedOut
    const response = await adminHttp.post<T>(path, body, { ...config, headers: { ...config.headers, ...headers } })
    return { kind: 'ready', data: response.data }
  } catch (error) {
    const status = adminHttpStatus(error)
    if (status === 401 || status === 403) return signedOut
    if (axios.isAxiosError<{ error?: string }>(error) && typeof error.response?.data?.error === 'string') {
      return { kind: 'issue', message: error.response.data.error }
    }
    return { kind: 'issue', message: '暂时无法确认操作结果，请重新读取版本状态。' }
  }
}

export function saveKnowledgeDraft(file: File, preview: KnowledgeImportPreview, signal: AbortSignal) {
  return postDraft<{ docNo: string; version: number }>('/knowledge/import-drafts', file, {
    signal,
    headers: {
      'Content-Type': 'text/markdown; charset=UTF-8',
      'X-Preview-Hash': preview.contentHash,
      'X-Expected-Latest-Version': String(preview.latestVersion),
      'X-Expected-Published-Version': String(preview.published?.version ?? 0),
    },
  })
}

function draftPath(document: DocumentVersion) {
  return '/knowledge/documents/' + encodeURIComponent(document.docNo) + '/versions/' + document.version
}

export function indexKnowledgeDraft(document: DocumentVersion, signal: AbortSignal) {
  return postDraft<{ finished: boolean; failed: boolean }>(draftPath(document) + '/index', undefined,
    { signal, timeout: 45_000 })
}

export function publishKnowledgeDraft(document: DocumentVersion, expectedPublishedVersion: number) {
  return postDraft<void>(draftPath(document) + '/publish', { expectedPublishedVersion }, { timeout: 30_000 })
}
