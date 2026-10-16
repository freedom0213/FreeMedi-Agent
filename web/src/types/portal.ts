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
}

export interface SlotSearchResponse {
  slots: AvailableSlot[]
  count: number
  searchEndDate: string
}

export type BookingOutcome = 'CREATED' | 'EXISTING' | 'REJECTED'

export interface BookingResult {
  success: boolean
  outcome: BookingOutcome
  appointmentNo: string | null
  code: string | null
  message: string
}

export interface AppointmentRecord {
  appointmentNo: string
  status: string
  bookedVia: string
  bookedAt: string
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
