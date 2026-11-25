import { expect, test as base } from '@playwright/test'
import type { BrowserContext, Route } from '@playwright/test'
import type { CandidateBatch, KnowledgeReply, KnowledgeSource, Requirements, TaskSnapshot } from '../src/types/agent'
import type { AppointmentRecord, AvailableSlot, BookingResult, Department, Doctor } from '../src/types/portal'

export const visitDate = '2026-11-20'
export const guideRequest = '我想挂呼吸内科，帮我查明天下午的号源'
export const visitQuestion = '这次去要带什么？'
export const visitAnswer = '按演示医院门诊须知，可以到门诊服务台出示预约凭证。'
export const unavailableReply = '医院资料库暂时无法查询，请稍后再试。你也可以向门诊服务台核实。'
export const guideTaskId = 'e2e-guide'
export const portalNumber = 'E2E-PORTAL-1'
export const guideNumber = 'E2E-GUIDE-1'

const departments: Department[] = [
  { id: 2002, code: 'RESPIRATORY', name: '呼吸内科', description: '呼吸系统门诊' },
  { id: 2001, code: 'CARDIOLOGY', name: '心内科', description: '心血管门诊' },
]
const doctors: Doctor[] = [
  { id: 3003, departmentId: 2002, name: '周宁', title: '主治医师', specialty: null, introduction: null },
  { id: 3004, departmentId: 2001, name: '陈晓', title: '主任医师', specialty: null, introduction: null },
]
const firstSlot = {
  slotId: 6101, departmentId: 2002, departmentCode: 'RESPIRATORY', departmentName: '呼吸内科',
  doctorId: 3003, doctorName: '周宁', doctorTitle: '主治医师',
  appointmentDate: visitDate, clinicPeriod: 'AFTERNOON', timeRange: '14:00–14:30',
} satisfies AvailableSlot
const slots = [
  { ...firstSlot, slotId: 6100, clinicPeriod: 'MORNING', timeRange: '09:00–09:30' },
  firstSlot,
  { ...firstSlot, slotId: 6102, timeRange: '15:00–15:30' },
]
const guideRequirements: Requirements = {
  departmentId: 2002, departmentName: '呼吸内科', appointmentDate: visitDate, clinicPeriod: 'AFTERNOON',
}
const candidates: CandidateBatch = { id: 'e2e-batch', slots: slots.slice(1), message: '' }
export const visitSource: KnowledgeSource = {
  chunkId: 51, docNo: 'DEMO-51', title: '演示门诊须知', heading: '报到凭证',
  version: 1, source: '演示医院门诊服务台', content: '到门诊服务台报到时，可出示预约凭证。',
  publishedAt: '2026-11-18T00:00:00Z',
}

type Payload = Record<string, unknown>
interface RequestLog {
  key: string
  body: Payload
  query: Record<string, string>
}
interface Consultation {
  task: TaskSnapshot
  replies: Record<string, KnowledgeReply>
}

class PatientApi {
  readonly appointments: AppointmentRecord[] = []
  readonly unexpected: string[] = []
  private readonly requests: RequestLog[] = []
  private readonly failures = new Map<string, number>()
  private readonly bookedIntents = new Map<string, AppointmentRecord>()
  private readonly visits = new Map<string, Consultation>()
  private guide = this.newTask(guideTaskId, 'APPOINTMENT_BOOKING')
  private readonly additionalSlots: AvailableSlot[] = []
  private dropBookingResponse = false
  private unavailableReplies = 0

  constructor(private readonly origin: string) {}

  async install(context: BrowserContext) {
    await context.route('**/*', async route => {
      const url = new URL(route.request().url())
      if (url.origin !== this.origin) return route.abort('blockedbyclient')
      if (!url.pathname.startsWith('/api/')) return route.continue()
      return this.handle(route, url)
    })
  }

  requestsFor(key: string) {
    return this.requests.filter(request => request.key === key)
  }

  failNext(key: string) {
    this.failures.set(key, 503)
  }

  interruptNext(key: string) {
    this.failures.set(key, 0)
  }

  loseNextBookingResponse() {
    this.dropBookingResponse = true
  }

  failVisitQueries(count: number) {
    this.unavailableReplies = count
  }

  seedAdditionalSlots(count: number) {
    for (let index = 0; index < count; ++index) {
      this.additionalSlots.push({ ...firstSlot, slotId: 7000 + index,
        appointmentDate: '2026-11-' + (21 + Math.floor(index / 4)) })
    }
  }

  seedAppointment() {
    return this.book(portalNumber, 'PORTAL', firstSlot)
  }

  private newTask(taskId: string, taskType: TaskSnapshot['taskType']): TaskSnapshot {
    return { taskId, taskType, patientId: 1001, status: 'UNDERSTANDING', stepCount: 0, turnCount: 0, facts: {}, turns: [] }
  }

  private book(appointmentNo: string, bookedVia: string, slot: AvailableSlot) {
    const record: AppointmentRecord = { ...slot, appointmentNo, bookedVia, bookedAt: '2026-11-19T04:30:00Z', status: 'BOOKED' }
    this.appointments.push(record)
    return record
  }

  private portalResult(record: AppointmentRecord, outcome: 'CREATED' | 'EXISTING'): BookingResult {
    return { success: true, outcome, appointmentNo: record.appointmentNo, code: null, message: '预约成功' }
  }

  private async handle(route: Route, url: URL) {
    const request = route.request()
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const method = request.method()
    const key = method + ' ' + path
    const body = method === 'POST' ? (request.postDataJSON() ?? {}) as Payload : {}
    const query = Object.fromEntries(url.searchParams)
    this.requests.push({ key, body, query })
    const json = (value: unknown) => route.fulfill({ json: value })
    const failure = this.failures.get(key)
    if (failure !== undefined) {
      this.failures.delete(key)
      if (failure === 0) return route.abort('failed')
      return route.fulfill({ status: failure, json: { message: '模拟服务暂时不可用。' } })
    }

    switch (key) {
      case 'GET /auth/session': return json({ patientId: '1001', displayName: '前端演示患者' })
      case 'GET /auth/csrf': return json({ patientId: '1001', headerName: 'x-csrf-token', token: 'frontend-e2e' })
      case 'GET /departments': return json(departments)
      case 'GET /slots/window':
        return json({ searchStartDate: '2026-11-19', searchEndDate: '2026-12-16' })
      case 'GET /slots': {
        const matches = [...slots, ...this.additionalSlots].filter(slot => (
          String(slot.departmentId) === query.departmentId
          && (!query.doctorId || String(slot.doctorId) === query.doctorId)
          && (!query.date || slot.appointmentDate === query.date)
          && (!query.period || slot.clinicPeriod === query.period)
          && !this.appointments.some(record => record.slotId === slot.slotId)
        ))
        const size = Number(query.limit || 20)
        const totalPages = Math.ceil(matches.length / size)
        const page = Math.min(Number(query.page || 1), totalPages || 1)
        const entries = matches.slice((page - 1) * size, page * size)
        return json({ slots: entries, count: entries.length, page, totalCount: matches.length, totalPages,
          searchStartDate: '2026-11-19', searchEndDate: '2026-12-16' })
      }
      case 'POST /appointments': {
        const intent = String(body.idempotencyKey)
        const existing = this.bookedIntents.get(intent)
        if (existing) return json(this.portalResult(existing, 'EXISTING'))
        const slot = slots.find(item => item.slotId === body.slotId)
        if (!slot) return this.unknown(route, key)
        const record = this.book('E2E-PORTAL-' + (this.bookedIntents.size + 1), 'PORTAL', slot)
        this.bookedIntents.set(intent, record)
        if (this.dropBookingResponse) {
          this.dropBookingResponse = false
          return route.abort('failed')
        }
        return json(this.portalResult(record, 'CREATED'))
      }
      case 'POST /appointments/status': {
        const record = this.bookedIntents.get(String(body.idempotencyKey))
        if (record && record.slotId === body.slotId) return json(this.portalResult(record, 'EXISTING'))
        return json({ success: false, outcome: null, appointmentNo: null, code: 'NOT_FOUND', message: '尚未查到预约。' })
      }
      case 'GET /appointments/mine': return json(this.appointments)
      case 'GET /appointments/mine/overview': {
        const start = (record: AppointmentRecord) => record.appointmentDate + ' ' + record.timeRange.slice(0, 5)
        const sorted = [...this.appointments].sort((left, right) => start(left).localeCompare(start(right)))
        const isUpcoming = (item: AppointmentRecord) => item.status === 'BOOKED' && start(item) >= '2026-11-19 12:30'
        const upcoming = sorted.filter(isUpcoming)
        const history = sorted.filter(item => !isUpcoming(item)).reverse()
        const paginate = (items: AppointmentRecord[], requested: string | undefined) => {
          const totalPages = Math.ceil(items.length / 20)
          const page = Math.min(Number(requested || 1), totalPages || 1)
          return { items: items.slice((page - 1) * 20, page * 20), totalCount: items.length, totalPages, page }
        }
        return json({ next: upcoming[0] ?? null,
          upcoming: paginate(upcoming.slice(1), query.upcomingPage), history: paginate(history, query.historyPage) })
      }
      case 'GET /tasks':
        return json({ items: this.guide.turns.length ? [{ taskId: guideTaskId, title: this.guide.turns[0]?.userMessage,
          status: this.guide.status, updatedAt: '2026-11-19T04:30:00Z' }] : [], nextCursor: null })
      case 'POST /tasks': return json({ taskId: guideTaskId, status: this.guide.status })
      case 'GET /tasks/' + guideTaskId: return json(this.guide)
      case 'GET /tasks/' + guideTaskId + '/events':
        return route.fulfill({ contentType: 'text/event-stream', body: this.event('STREAM_COMPLETED', {}) })
      case 'POST /tasks/' + guideTaskId + '/messages/stream': {
        const answer = '已经按呼吸内科、明天下午查到两个可预约时段。'
        this.guide.status = 'PROPOSAL_READY'
        this.guide.facts = { requirements: guideRequirements, candidateBatch: candidates }
        this.saveTurn(this.guide, body, answer)
        return this.stream(route, this.guide, answer)
      }
      case 'POST /tasks/' + guideTaskId + '/selection': {
        const slot = candidates.slots.find(item => item.slotId === body.slotId)
        if (!slot || body.batchId !== candidates.id) return this.unknown(route, key)
        this.guide.status = 'AWAITING_CONFIRMATION'
        this.guide.activePlan = {
          id: 'e2e-plan', version: ++this.guide.stepCount, status: 'PROPOSED',
          draft: { ...slot, patientId: 1001, expiresAt: '2026-11-19T06:00:00Z' },
        }
        return json({ status: this.guide.status, task: this.guide, events: [], message: '请确认这笔预约。' })
      }
      case 'POST /tasks/' + guideTaskId + '/confirm': {
        const plan = this.guide.activePlan
        if (!plan || 'present' in plan || body.planId !== plan.id || body.planVersion !== plan.version) {
          return this.unknown(route, key)
        }
        const slot = slots.find(item => item.slotId === plan.draft.slotId)
        if (!slot) return this.unknown(route, key)
        this.book(guideNumber, 'AGENT', slot)
        this.guide.status = 'COMPLETED'
        this.guide.facts.appointmentId = guideNumber
        return json({ success: true, status: this.guide.status, task: this.guide, events: [], message: '预约成功。' })
      }
    }

    const directory = path.match(/^\/departments\/(\d+)\/doctors$/)
    if (method === 'GET' && directory) return json(doctors.filter(doctor => doctor.departmentId === Number(directory[1])))

    const appointment = path.match(/^\/appointments\/([^/]+)(\/(?:consultation|cancel))?$/)
    if (appointment) {
      const record = this.appointments.find(item => item.appointmentNo === appointment[1])
      if (!record) return route.fulfill({ status: 404, json: { message: '找不到这笔预约。' } })
      if (method === 'GET' && !appointment[2]) return json(record)
      if (method === 'POST' && appointment[2] === '/cancel') {
        Object.assign(record, { status: 'CANCELLED', canCancel: false, cancelledAt: '2026-11-19T04:30:00Z' })
        return json({ success: true, code: null, message: '预约已取消。' })
      }
      if (method === 'POST' && appointment[2] === '/consultation') {
        const taskId = 'visit-' + record.appointmentNo
        if (!this.visits.has(taskId)) {
          const task = this.newTask(taskId, 'VISIT_CONSULTATION')
          const replies: Record<string, KnowledgeReply> = {}
          task.facts = { visitAppointment: record, knowledgeReplies: replies }
          this.visits.set(taskId, { task, replies })
        }
        return json({ taskId })
      }
    }

    const consultation = path.match(/^\/tasks\/(visit-[^/]+)(\/messages\/stream)?$/)
    const visit = consultation && this.visits.get(consultation[1])
    if (visit) {
      if (method === 'GET' && !consultation[2]) return json(visit.task)
      if (method === 'POST' && consultation[2]) {
        const prior = visit.task.turns.find(turn => turn.clientRequestId === body.clientRequestId)
        if (prior?.assistantMessage) return this.stream(route, visit.task, prior.assistantMessage)
        const failed = this.unavailableReplies > 0
        if (failed) this.unavailableReplies--
        const answer = failed ? unavailableReply : visitAnswer
        const turnNo = this.saveTurn(visit.task, body, answer)
        visit.replies[turnNo] = { status: failed ? 'UNAVAILABLE' : 'ANSWERED', sources: failed ? [] : [visitSource] }
        const events = failed ? [{ type: 'TOOL_RETRY_REQUIRED', summary: answer }] : []
        return this.stream(route, visit.task, answer, events)
      }
    }
    return this.unknown(route, key)
  }

  private saveTurn(task: TaskSnapshot, body: Payload, answer: string) {
    const turnNo = ++task.turnCount
    task.turns.push({
      turnNo, clientRequestId: String(body.clientRequestId), userMessage: String(body.message),
      assistantMessage: answer, status: 'COMPLETED',
    })
    return turnNo
  }

  private event(type: string, data: unknown) {
    return 'event: ' + type + '\ndata: ' + JSON.stringify(data) + '\n\n'
  }

  private stream(route: Route, task: TaskSnapshot, answer: string, events: { type: string; summary: string }[] = []) {
    return route.fulfill({
      contentType: 'text/event-stream',
      body: this.event('MESSAGE_DELTA', { text: answer }) + this.event('RESULT', {
        status: task.status, task, message: answer, events,
      }),
    })
  }

  private unknown(route: Route, key: string) {
    this.unexpected.push(key)
    return route.fulfill({ status: 501, json: { message: '未配置测试接口。' } })
  }
}

export const test = base.extend<{ patientApi: PatientApi }>({
  patientApi: async ({ context, page, baseURL }, use) => {
    const api = new PatientApi(new URL(baseURL!).origin)
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.clock.setFixedTime(new Date('2026-11-19T04:30:00Z'))
    await api.install(context)
    await use(api)
    expect(api.unexpected, '遇到未配置的应用接口').toEqual([])
    expect(errors, '浏览器抛出页面异常').toEqual([])
  },
})

export { expect }
