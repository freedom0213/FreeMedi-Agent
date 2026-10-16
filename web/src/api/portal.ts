import type {
  AppointmentRecord,
  AvailableSlot,
  BookingResult,
  Department,
  Doctor,
  SlotSearchResponse,
} from '../types/portal'
import { http } from './http'

export async function listDepartments(): Promise<Department[]> {
  const { data } = await http.get<Department[]>('/departments')
  return data
}

export async function listDoctors(departmentId: number): Promise<Doctor[]> {
  const { data } = await http.get<Doctor[]>(`/departments/${departmentId}/doctors`)
  return data
}

/**
 * Ordinary registration search: strict on purpose. The backend never substitutes another date or
 * period, so an empty list means there really is nothing for the chosen filters.
 */
export async function searchSlots(filters: {
  departmentId?: number
  doctorId?: number
  date?: string
  period?: string
}): Promise<SlotSearchResponse> {
  const { data } = await http.get<SlotSearchResponse>('/slots', { params: filters })
  return data
}

export async function bookSlot(slotId: number, idempotencyKey: string): Promise<BookingResult> {
  const { data } = await http.post<BookingResult>('/appointments', { slotId, idempotencyKey })
  return data
}

export async function listMyAppointments(): Promise<AppointmentRecord[]> {
  const { data } = await http.get<AppointmentRecord[]>('/appointments/mine')
  return data
}

export async function getAppointment(appointmentNo: string): Promise<AppointmentRecord> {
  const { data } = await http.get<AppointmentRecord>(`/appointments/${appointmentNo}`)
  return data
}

export function periodLabel(period: string): string {
  return ({ MORNING: '上午', AFTERNOON: '下午', EVENING: '晚上' } as Record<string, string>)[period] ?? period
}

export function formatAppointmentDate(date: string | undefined): string {
  if (!date) return '以预约方案为准'
  const value = new Date(`${date}T00:00:00`)
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
    .format(value)
}

export function channelLabel(bookedVia: string): string {
  return bookedVia === 'PORTAL' ? '自助挂号' : '智能导诊'
}

export function slotLabel(slot: AvailableSlot): string {
  return `${slot.departmentName} · ${slot.doctorName} · ${formatAppointmentDate(slot.appointmentDate)} ${slot.timeRange}`
}
