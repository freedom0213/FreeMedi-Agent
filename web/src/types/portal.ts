export interface Department {
  id: number
  code: string
  name: string
  description: string | null
}

export interface Doctor {
  id: number
  departmentId: number
  name: string
  title: string | null
  specialty: string | null
  introduction: string | null
}

export interface AvailableSlot {
  slotId: number
  departmentId: number
  departmentCode: string
  departmentName: string
  doctorId: number
  doctorName: string
  doctorTitle: string | null
  appointmentDate: string
  clinicPeriod: string
  timeRange: string
  cancellationDeadline?: string
}

export interface LifecycleResult {
  success: boolean
  code: string | null
  message: string
}

export interface SlotSearchResponse {
  slots: AvailableSlot[]
  count: number
  searchStartDate: string
  searchEndDate: string
  page: number
  totalCount: number
  totalPages: number
}

export type BookingOutcome = 'CREATED' | 'EXISTING' | 'REJECTED'

export interface BookingResult {
  success: boolean
  outcome: BookingOutcome | null
  appointmentNo: string | null
  code: string | null
  message: string
}

export interface AppointmentRecord {
  appointmentNo: string
  status: string
  bookedVia: string
  bookedAt: string
  cancellationDeadline?: string
  checkInOpensAt?: string
  checkedInAt?: string | null
  cancelledAt?: string | null
  canCancel?: boolean
  canCheckIn?: boolean
  originalAppointmentNo?: string | null
  replacementAppointmentNo?: string | null
  slotId: number
  appointmentDate: string
  clinicPeriod: string
  timeRange: string
  departmentId: number
  departmentName: string
  doctorId: number
  doctorName: string
  doctorTitle: string | null
}

export interface AppointmentPage {
  items: AppointmentRecord[]
  page: number
  totalCount: number
  totalPages: number
}

export interface AppointmentOverview {
  next: AppointmentRecord | null
  upcoming: AppointmentPage
  history: AppointmentPage
}
