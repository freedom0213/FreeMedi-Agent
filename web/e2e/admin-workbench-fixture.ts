export function emptyWorkbenchResponse(url: URL) {
  const path = url.pathname
  if (path === '/api/v1/admin/appointments/interruption-pending/count') return { count: 0 }
  if (path === '/api/v1/admin/knowledge/documents' || path === '/api/v1/admin/knowledge/library') return []
  if (path === '/api/v1/admin/observability/appointments') return {
    from: url.searchParams.get('from'), to: url.searchParams.get('to'), openSchedules: 0,
    totals: { booked: 0, portal: 0, agent: 0, remaining: 0, occupied: 0 },
    trend: [], departments: [], doctors: [],
  }
  if (path === '/api/v1/admin/observability/model-calls') return {
    totals: { attempts: 0, succeeded: 0, failed: 0, retries: 0, averageMs: null, p95Ms: null },
    options: [], models: [], trend: [], reasons: [],
  }
  return undefined
}
