const dayFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
})

export function hospitalDay(offset = 0) {
  return dayFormat.format(new Date(Date.now() + offset * 86_400_000))
}

export function queryDate(value: unknown) {
  if (typeof value !== 'string' || !/^[1-9]\d{3}-\d{2}-\d{2}$/.test(value)) return ''
  const parsed = new Date(value)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : ''
}

export function queryCatalogId(value: unknown) {
  const id = typeof value === 'string' && /^[1-9]\d*$/.test(value) ? Number(value) : NaN
  return Number.isSafeInteger(id) ? id : undefined
}
