import type {
  AppointmentRecord,
  AppointmentOverview,
  AvailableSlot,
  BookingResult,
  Department,
  Doctor,
  LifecycleResult,
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
  page?: number
  limit?: number
}): Promise<SlotSearchResponse> {
  const { data } = await http.get<SlotSearchResponse>('/slots', { params: filters })
  return data
}

export async function readSlotWindow() {
  const { data } = await http.get<Pick<SlotSearchResponse, 'searchStartDate' | 'searchEndDate'>>('/slots/window')
  return data
}

export async function bookSlot(slotId: number, idempotencyKey: string): Promise<BookingResult> {
  const { data } = await http.post<BookingResult>('/appointments', { slotId, idempotencyKey })
  return data
}

export async function checkPortalBooking(slotId: number, idempotencyKey: string): Promise<BookingResult> {
  const { data } = await http.post<BookingResult>('/appointments/status', { slotId, idempotencyKey })
  return data
}

export async function rescheduleAppointment(
  appointmentNo: string, slotId: number, idempotencyKey: string, checkOnly: boolean,
): Promise<BookingResult> {
  const path = '/appointments/' + encodeURIComponent(appointmentNo) + '/reschedule'
  const { data } = await http.post<BookingResult>(checkOnly ? path + '/status' : path,
    { slotId, idempotencyKey }, { validateStatus: status => status === 200 || status === 404 })
  return data
}

export async function readAppointmentOverview(upcomingPage: number, historyPage: number): Promise<AppointmentOverview> {
  const { data } = await http.get<AppointmentOverview>('/appointments/mine/overview', { params: { upcomingPage, historyPage } })
  return data
}

export async function getAppointment(appointmentNo: string): Promise<AppointmentRecord> {
  const { data } = await http.get<AppointmentRecord>(`/appointments/${encodeURIComponent(appointmentNo)}`)
  return data
}

export async function cancelAppointment(appointmentNo: string): Promise<LifecycleResult> {
  const { data } = await http.post<LifecycleResult>('/appointments/' + encodeURIComponent(appointmentNo) + '/cancel')
  return data
}

const hospitalDateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

export function formatHospitalDateTime(value: string) {
  return hospitalDateTime.format(new Date(value))
}

export function cancellationNotice(deadline?: string) {
  if (!deadline) return ''
  return Date.parse(deadline) <= Date.now() ? '该预约已过线上取消时间。'
    : '可在 ' + formatHospitalDateTime(deadline) + ' 前线上取消。'
}

export async function openVisitConsultation(appointmentNo: string): Promise<string> {
  const { data } = await http.post<{ taskId: string }>(`/appointments/${encodeURIComponent(appointmentNo)}/consultation`)
  return data.taskId
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
