export function readPending<T>(key: string): T | null {
  try {
    return JSON.parse(sessionStorage.getItem(key) ?? 'null') as T | null
  } catch {
    return null
  }
}

export function writePending<T>(key: string, value: T | null) {
  if (value === null) sessionStorage.removeItem(key)
  else sessionStorage.setItem(key, JSON.stringify(value))
}

export function rescheduleSubmissionKey(patientId: string, appointmentNo: string) {
  return `freemedi-reschedule:${patientId}:${appointmentNo}`
}
