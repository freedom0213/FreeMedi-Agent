import axios from 'axios'
import type { AppointmentRecord, LifecycleResult } from '../types/portal'
import { shallowRef } from 'vue'

export type AdminRole = 'SUPER_ADMIN' | 'SYSTEM_ADMIN' | 'SCHEDULER' | 'TRIAGE' | 'KNOWLEDGE' | 'VISIT_SUPERVISOR'
export interface AdminGrant { role: AdminRole; departmentId: number | null }
interface AdminSession {
  adminId: string; displayName: string; grants?: AdminGrant[]; scheduleRules?: HospitalRules | null; mustChangePassword?: boolean
}
export const adminRoleLabels: Record<AdminRole, string> = {
  SUPER_ADMIN: '超级管理员', SYSTEM_ADMIN: '系统管理员', SCHEDULER: '排班员',
  TRIAGE: '分诊员', KNOWLEDGE: '知识库管理员',
  VISIT_SUPERVISOR: '就诊复核负责人',
}
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

export interface KnowledgeLibraryGroup {
  docType: DocumentType; documents: number; versions: number; published: number
  drafts: number; archived: number; indexed: number; pending: number; failed: number
}
export interface KnowledgeIndexSummary {
  model: string; dimensions: number; provider: 'LOCAL' | 'API'; store: 'QDRANT' | 'MEMORY' | 'DISABLED'
  collection: string | null; pointCount: number | null; status: 'REACHABLE' | 'UNAVAILABLE' | 'DISABLED'
}
export type VectorState = 'READY' | 'MISSING' | 'UNVERIFIED' | 'NOT_INDEXED'
export interface KnowledgeFragment {
  id: number; chunkNo: number; heading: string | null; content: string | null; charCount: number
  embeddingStatus: IndexStatus; failureReason: string | null; indexedAt: string | null
  docNo: string; title: string; version: number; docType: DocumentType; source: string; documentStatus: VersionStatus
  vectorState: VectorState | null; vectorModel: string | null; vectorDimensions: number | null
}
export interface FragmentFilters { docType?: DocumentType; status?: VersionStatus; embedding?: IndexStatus; q?: string; page: number }
export interface KnowledgeProbe {
  indexAvailable: boolean; unavailableReason: string | null
  hits: { id: number; docNo: string; title: string; version: number; heading: string | null; content: string;
    source: string; chunkNo: number | null; score: number }[]
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
  promptVersion?: string | null
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
export function adminHas(role: AdminRole, departmentId?: number) {
  const session = adminSession.value
  return !!session && (!session.grants || session.grants.some(grant => grant.role === 'SUPER_ADMIN' ||
    (grant.role === role && (departmentId === undefined || grant.departmentId === departmentId))))
}

export function canOpenAdmin(name: unknown) {
  if (adminHas('SUPER_ADMIN') || name === 'admin-home' || name === 'admin-password') return true
  if (name === 'admin-schedules') return adminHas('SCHEDULER')
  if (name === 'admin-appointments') return adminHas('SCHEDULER') || adminHas('TRIAGE')
  if (name === 'admin-today-triage') return adminHas('TRIAGE')
  if (name === 'admin-visit-review') return adminHas('VISIT_SUPERVISOR')
  if (name === 'admin-document' || (typeof name === 'string' && name.startsWith('admin-knowledge'))) return adminHas('KNOWLEDGE')
  return adminHas('SYSTEM_ADMIN') && ['admin-departments', 'admin-doctors', 'admin-model-config',
    'admin-prompt-templates', 'admin-tool-registry', 'admin-staff', 'admin-setup'].includes(String(name))
}
const sessionChanges = new BroadcastChannel('freemedi-admin-session')
sessionChanges.onmessage = () => {
  if (/^\/admin(?:\/|$)/.test(window.location.pathname)) window.location.reload()
}

export function adminHttpStatus(error: unknown) {
  return axios.isAxiosError(error) ? error.response?.status : undefined
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

export async function adminLogin(username: string, password: string) {
  const { data } = await adminHttp.get<CsrfResponse>('/auth/csrf')
  await adminHttp.post('/auth/login', new URLSearchParams({ username, password }), {
    headers: { [data.headerName]: data.token },
  })
  sessionChanges.postMessage('changed')
  window.location.replace('/admin')
}

export async function adminLogout() {
  const headers = await adminCsrfHeaders()
  if (headers) {
    await adminHttp.post('/auth/logout', undefined, { headers })
    sessionChanges.postMessage('changed')
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

export async function readKnowledgeLibrary(signal: AbortSignal) {
  return (await adminHttp.get<KnowledgeLibraryGroup[]>('/knowledge/library', { signal })).data
}
export async function readKnowledgeIndexSummary(signal: AbortSignal) {
  return (await adminHttp.get<KnowledgeIndexSummary>('/knowledge/index/summary', { signal })).data
}
export async function readKnowledgeFragments(filters: FragmentFilters, vectors: boolean, signal: AbortSignal) {
  return (await adminHttp.get<{ items: KnowledgeFragment[]; page: number; hasNext?: boolean; totalPages?: number }>(
    '/knowledge/chunks', { params: { ...filters, vectors }, signal })).data
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

export const probeKnowledge = (query: string, docType: DocumentType | undefined, topK: number) =>
  writeAgentSettings<KnowledgeProbe>('post', '/knowledge/index/probe', { query, docType, topK }, 40_000)
export const retryKnowledgeIndex = (chunkId: number) =>
  writeAgentSettings<{ status: 'INDEXED' | 'ALREADY_READY' }>('post', '/knowledge/chunks/' + chunkId + '/retry-index', undefined, 45_000)

export function agentSettingsError(error: unknown) {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    if (status === 401) return '管理员会话已失效，请重新登录。'
    if (status === 403) return '当前岗位没有权限执行此操作。'
    const detail = error.response?.data?.error ?? error.response?.data?.message
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

export interface PromptGuidance { version: string; triage: string; selection: string }
export type PromptDraft = Omit<PromptGuidance, 'version'> & { note: string }
export interface PromptTrialOutput {
  passed: boolean; decision: string | null; reply: string | null; tool: string | null; arguments: string | null; failure: string | null
}
export interface PromptTrialReport {
  baselineVersion: string; modelVersion: string; modelLabel: string
  cases: { key: string; label: string; input: string; expectation: string; baseline: PromptTrialOutput; candidate: PromptTrialOutput }[]
}
export interface PromptVersion {
  id: number; prompt: PromptGuidance; note: string; createdBy: string; createdAt: string; testedAt: string | null
  trialPassed: boolean | null; canActivate: boolean; report: PromptTrialReport | null
}
export interface PromptTemplateView {
  revision: number; activeId: number | null; activatedAt: string | null; effectiveVersion: string
  trialAvailable: boolean; runningTrial: number | null; builtIn: PromptGuidance; basePrompt: string; versions: PromptVersion[]
}
const promptTemplates = '/agent/prompt-templates'
export async function readPromptTemplates(signal: AbortSignal) {
  return (await adminHttp.get<PromptTemplateView>(promptTemplates, { signal })).data
}
export const savePromptTemplate = (draft: PromptDraft) =>
  writeAgentSettings<PromptTemplateView>('post', promptTemplates + '/versions', draft)
export const trialPromptTemplate = (id: number) =>
  writeAgentSettings<PromptTemplateView>('post', promptTemplates + '/versions/' + id + '/trial', undefined, 120_000)
export const activatePromptTemplate = (id: number | null, expectedRevision: number) =>
  writeAgentSettings<PromptTemplateView>('post', promptTemplates + '/activate', { id, expectedRevision })
export const updateAgentTimeouts = (timeout: AgentTimeouts, expectedRevision: number) =>
  writeAgentSettings<AgentModelConfiguration>('put', '/agent/model-config/policy', { ...timeout, expectedRevision })
export const updateRegisteredTool = (name: string, enabled: boolean, timeoutMs: number | null, expectedRevision: number) =>
  writeAgentSettings<RegisteredTool[]>('put', '/agent/tools/' + encodeURIComponent(name), { enabled, timeoutMs, expectedRevision })

type CatalogStatus = 'ACTIVE' | 'DISABLED'
export type ClinicPeriod = 'MORNING' | 'AFTERNOON' | 'EVENING'
export type CatalogKind = 'departments' | 'doctors'
export type CatalogListKind = CatalogKind | 'schedules' | 'appointments'
export interface CatalogOption { id: number; name: string; status: CatalogStatus }
export interface DepartmentRow extends CatalogOption { code: string; description: string | null }
export interface DoctorRow extends CatalogOption {
  departmentId: number; departmentName: string; title: string | null; specialty: string | null; introduction: string | null
  importCode?: string
}
export interface ScheduleRow {
  id: number; departmentId: number; departmentName: string; doctorId: number; doctorName: string
  scheduleDate: string; clinicPeriod: ClinicPeriod; status: 'OPEN' | 'CLOSED'
  slots: number; remaining: number; booked: number; interrupted?: boolean
}
export interface ScheduleDetail {
  schedule: ScheduleRow
  slots: { number: number; startAt: string; endAt: string; status: 'AVAILABLE' | 'RESERVED' | 'CLOSED' }[]
}
export interface ScheduleInput {
  departmentId: number; doctorId: number; date: string; period: ClinicPeriod; start: string; minutes: number; count: number
}
type CatalogPage<T> = { items: T[]; page: number; hasNext: boolean; totalPages?: number }
export interface AdminAppointmentRow {
  scheduleId: number; appointment: AppointmentRecord; patientName?: string | null; checkInAllowed?: boolean
  visitCompletionAllowed?: boolean; visitReviewAllowed?: boolean; visitReviewId?: number | null
}
export type VisitDesk = 'TODAY' | 'REVIEW'
type CatalogFilters = { q?: string; status?: string; departmentId?: number; doctorId?: number; from?: string; to?: string;
  appointmentNo?: string; scheduleId?: number; channel?: string; pendingArrangement?: string; visitDesk?: VisitDesk; page: number }
const catalogPath = '/catalog/'
export async function readCatalog<T>(kind: CatalogListKind, params: CatalogFilters, signal: AbortSignal) {
  const path = kind === 'appointments' ? '/appointments' : catalogPath + kind
  return (await adminHttp.get<CatalogPage<T>>(path, { params, signal })).data
}
export async function readPendingArrangements(signal: AbortSignal) {
  return (await adminHttp.get<{ count: number }>('/appointments/interruption-pending/count', { signal })).data
}
export async function readCatalogOptions(kind: CatalogKind, signal: AbortSignal, departmentId?: number) {
  return (await adminHttp.get<CatalogOption[]>(catalogPath + kind + '/options', { params: { departmentId }, signal })).data
}
export function saveCatalogEntry(kind: CatalogKind, id: number | undefined, data: unknown) {
  return writeAgentSettings<void>(id === undefined ? 'post' : 'put', catalogPath + kind + (id === undefined ? '' : '/' + id), data)
}
export function setCatalogActive(kind: CatalogKind, id: number, active: boolean) {
  return writeAgentSettings<void>('put', catalogPath + kind + '/' + id + '/status', { active })
}
export async function readScheduleDetail(id: number, signal: AbortSignal) {
  return (await adminHttp.get<ScheduleDetail>(catalogPath + 'schedules/' + id, { signal })).data
}
export const createSchedule = (input: ScheduleInput) => writeAgentSettings<void>('post', catalogPath + 'schedules', input)
export const checkInAppointment = (number: string) =>
  writeAgentSettings<LifecycleResult>('post', '/appointments/' + encodeURIComponent(number) + '/check-in', undefined)
export type VisitReviewOutcome = 'CONFIRMED' | 'UNCONFIRMED'
export const completeVisit = (number: string) =>
  writeAgentSettings<void>('post', '/appointments/' + encodeURIComponent(number) + '/visit-completion', undefined)
export const reviewVisit = (number: string, outcome: VisitReviewOutcome, reason: string, expectedReviewId: number | null) =>
  writeAgentSettings<void>('post', '/appointments/' + encodeURIComponent(number) + '/visit-review',
    { outcome, reason, expectedReviewId })

interface StaffInput { username: string; displayName: string; password: string; grants: AdminGrant[] }
export interface StaffMember {
  id: number; username: string; displayName: string; grants: AdminGrant[]
  status: 'ACTIVE' | 'DISABLED'; temporaryPasswordExpiresAt: string | null
}
export interface TemporaryPassword { password: string; expiresAt: string }
export interface StaffSecurityEvent {
  id: number; operator: string; action: 'DISABLED' | 'ENABLED' | 'PASSWORD_RESET' | 'PASSWORD_CHANGED'; occurredAt: string
}
export interface StaffSecurityHistory { events: StaffSecurityEvent[]; nextBefore: number | null }
export const readStaff = async (signal: AbortSignal) => (await adminHttp.get<StaffMember[]>('/staff', { signal })).data
export const saveStaff = (id: number | undefined, input: StaffInput) => id === undefined
  ? writeAgentSettings<void>('post', '/staff', input)
  : writeAgentSettings<void>('put', '/staff/' + id + '/grants', { grants: input.grants })
export const setStaffActive = (id: number, active: boolean) => writeAgentSettings<void>('put', '/staff/' + id + '/status', { active })
export const resetStaffPassword = (id: number) => writeAgentSettings<TemporaryPassword>('post', '/staff/' + id + '/password-reset', undefined)
export const readStaffSecurityHistory = async (id: number, before: number | undefined, signal: AbortSignal) =>
  (await adminHttp.get<StaffSecurityHistory>('/staff/' + id + '/security-history', { params: { before }, signal })).data

export async function changeAdminPassword(currentPassword: string, newPassword: string) {
  await writeAgentSettings<void>('post', '/auth/password', { currentPassword, newPassword })
  adminSession.value = null
  sessionChanges.postMessage('changed')
  window.location.replace('/admin/login?reason=password-changed')
}

export interface ScheduleInterruptionPreview {
  token: string; firstStart: string; appointments: { appointmentNo: string; timeRange: string }[]
}
export interface ScheduleInterruptionResult { reason: string; stoppedAt: string; affected: number }
const interruptionPath = (id: number) => '/catalog/schedules/' + id + '/interruption'
export const previewInterruption = async (id: number) =>
  (await adminHttp.get<ScheduleInterruptionPreview>(interruptionPath(id) + '/preview')).data
export const readInterruption = async (id: number) =>
  (await adminHttp.get<ScheduleInterruptionResult>(interruptionPath(id))).data
export const confirmInterruption = (id: number, requestId: string, token: string, reason: string) =>
  writeAgentSettings<ScheduleInterruptionResult>('post', interruptionPath(id), { requestId, token, reason })
export const arrangementLabels = { SUBSTITUTE: '已另行安排', FOLLOW_UP: '需联系院方', REBOOK: '建议重新挂号' }
export type ArrangementOutcome = keyof typeof arrangementLabels
export const saveArrangement = (number: string, outcome: ArrangementOutcome, note: string) =>
  writeAgentSettings<void>('put', '/appointments/' + encodeURIComponent(number) + '/arrangement', { outcome, note })

export interface HospitalRules { scheduleDays: number; patientDays: number; cancellationCutoff: string; checkInLead: number }
export interface HospitalSetup {
  profile: { name: string; contact: string; activatedAt: string | null }
  rules: HospitalRules; defaults: HospitalRules; timezone: string; required: boolean
  checks: { departments: number; doctors: number; schedules: number }
}
export type ImportKind = 'DEPARTMENTS' | 'DOCTORS' | 'SCHEDULES'
export interface CatalogImportPreview {
  token: string; rows: { line: number; key: string; state: 'CREATE' | 'SKIP' | 'ERROR'; message: string }[]
}
export const readHospitalSetup = async (signal?: AbortSignal) =>
  (await adminHttp.get<HospitalSetup>('/setup', { signal })).data
export const activateHospitalSetup = (settings: HospitalRules & { name: string; contact: string }) =>
  writeAgentSettings<HospitalSetup>('put', '/setup/activate', settings)
export const previewCatalogImport = (kind: ImportKind, csv: string) =>
  writeAgentSettings<CatalogImportPreview>('post', '/setup/import/preview', { kind, csv })
export const commitCatalogImport = (kind: ImportKind, csv: string, token: string) =>
  writeAgentSettings<number>('post', '/setup/import/commit', { kind, csv, token })
export interface AppointmentOperation {
  time: string
  action: string
  operatorType: string
  operatorName: string | null
  result: string
  reason: string | null
}
export interface AppointmentHistory {
  appointmentNo: string
  originalAppointmentNo: string | null
  replacementAppointmentNo: string | null
  events: { items: AppointmentOperation[]; page: number; totalPages: number }
}
export async function readAppointmentHistory(number: string, page: number, signal: AbortSignal) {
  return (await adminHttp.get<AppointmentHistory>('/appointments/' + encodeURIComponent(number) + '/operations',
    { params: { page }, signal })).data
}
export const closeSchedule = (id: number) => writeAgentSettings<void>('post', catalogPath + 'schedules/' + id + '/close', undefined)

export interface AppointmentCounts { booked: number; portal: number; agent: number; remaining: number; occupied: number }
export interface AppointmentOperationsFilters { from: string; to: string; departmentId?: number }
export interface AppointmentOperations {
  from: string
  to: string
  totals: AppointmentCounts
  openSchedules: number
  trend: (Pick<AppointmentCounts, 'booked' | 'portal' | 'agent'> & { date: string })[]
  departments: { departmentId: number; departmentName: string; counts: AppointmentCounts }[]
  doctors: { doctorId: number; doctorName: string; departmentId: number; departmentName: string; counts: AppointmentCounts }[]
}
export async function readAppointmentOperations(params: AppointmentOperationsFilters, signal: AbortSignal) {
  return (await adminHttp.get<AppointmentOperations>('/observability/appointments', { params, signal })).data
}

export interface ModelCallFilters { from: string; to: string; model?: string }
export interface ObservedModel { key: string; mode: string; version: string; label: string | null; modelName: string | null }
export interface ModelCallMetrics {
  attempts: number; succeeded: number; failed: number; retries: number; averageMs: number | null; p95Ms: number | null
}
export interface ModelCallOverview {
  totals: ModelCallMetrics
  options: ObservedModel[]
  models: { model: ObservedModel; metrics: ModelCallMetrics }[]
  trend: { date: string; succeeded: number; failed: number }[]
  reasons: { code: string | null; attempts: number }[]
}
export type ModelCallOutcome = 'SUCCEEDED' | 'FAILED'
export interface ModelCallAttempt {
  recordedAt: string; taskId: string; sequence: number; modelKey: string; attempt: number | null;
  outcome: ModelCallOutcome; reason: string | null; durationMs: number | null
}
export interface ModelCallPage { items: ModelCallAttempt[]; nextBefore: string | null }
export async function readModelCallOverview(params: ModelCallFilters, signal: AbortSignal) {
  return (await adminHttp.get<ModelCallOverview>('/observability/model-calls', { params, signal })).data
}
export async function readModelCallAttempts(params: ModelCallFilters & { outcome?: ModelCallOutcome; before?: string }, signal: AbortSignal) {
  return (await adminHttp.get<ModelCallPage>('/observability/model-calls/attempts', { params, signal })).data
}

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
