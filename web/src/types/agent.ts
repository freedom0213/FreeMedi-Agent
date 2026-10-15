export type TaskStatus =
  | 'UNDERSTANDING'
  | 'SEARCHING'
  | 'NEED_MORE_INFO'
  | 'PROPOSAL_READY'
  | 'AWAITING_CONFIRMATION'
  | 'EXECUTING'
  | 'VERIFYING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export interface TaskTurn {
  turnNo: number
  clientRequestId: string
  userMessage: string
  assistantMessage: string | null
  status: string
}

export interface PlanDraft {
  patientId: number
  departmentId: number
  doctorId: number
  slotId: number
  appointmentDate: string
  timeRange: string
  expiresAt: string
}

export interface AppointmentPlan {
  id: { value: string } | string
  version: number
  draft: PlanDraft
  status: string
}

export interface SlotResult {
  slotId: number
  departmentId: number
  departmentName: string
  doctorId: number
  doctorName: string
  doctorTitle: string
  appointmentDate: string
  clinicPeriod: string
  timeRange: string
}

export interface ToolResult {
  success: boolean
  data: { slots?: SlotResult[]; [key: string]: unknown }
  errorCode?: string | null
  errorMessage?: string | null
}

export interface TaskSnapshot {
  taskId: { value: string } | string
  patientId: number
  status: TaskStatus
  stepCount: number
  turnCount: number
  facts: Record<string, unknown>
  activePlan?: AppointmentPlan | { present: boolean; value?: AppointmentPlan } | null
  lastToolResult?: ToolResult | { present: boolean; value?: ToolResult } | null
  turns: TaskTurn[]
}

export interface TriageRecommendation {
  departmentId: number
  departmentName: string
  reason: string
  turnNo?: number
}

export interface AgentEvent {
  type: string
  summary: string
}

export interface AgentResult {
  message: string
  success?: boolean
  status: TaskStatus
  events: AgentEvent[]
  task: TaskSnapshot
}

export interface TaskCreated {
  taskId: string
  status: TaskStatus
}
