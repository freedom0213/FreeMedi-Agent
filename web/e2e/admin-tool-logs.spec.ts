import { expect, test } from '@playwright/test'
import type { AgentTaskSummary, AgentTraceEntry } from '../src/api/admin'

test('按任务定位并查看失败、重试、耗时和后续步骤', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const recordedAt = '2026-11-20T01:30:00Z'
  const task: AgentTaskSummary = {
    taskId: 'TRACE-001', taskType: 'APPOINTMENT_BOOKING', status: 'COMPLETED', turnCount: 1, updatedAt: recordedAt,
  }
  const tool: AgentTraceEntry = {
    sequence: 1, recordedAt, type: 'READ_TOOL_ATTEMPT', turnNo: 1, stepNo: 1,
    attempt: 1, outcome: 'FAILED', reason: 'TOOL_TIMEOUT', durationMs: 5000, tool: 'findAvailableSlots', resultCount: null,
  }
  const unexpected: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/admin/auth/session') {
      return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    }
    if (url.pathname === '/api/v1/admin/agent/tasks') return route.fulfill({ json: [task] })
    if (url.pathname === '/api/v1/admin/agent/tasks/TRACE-001/trace') {
      if (url.searchParams.get('after') === '2') {
        return route.fulfill({ json: { task, nextAfter: null, entries: [
          { ...tool, sequence: 3, type: 'APPOINTMENT_VERIFY', attempt: 1, tool: null, outcome: 'SUCCEEDED', reason: null, durationMs: 8 },
        ] } })
      }
      return route.fulfill({ json: { task, nextAfter: 2, entries: [
        tool, { ...tool, sequence: 2, attempt: 2, outcome: 'SUCCEEDED', reason: null, durationMs: 27, resultCount: 3 },
      ] } })
    }
    if (url.pathname === '/api/v1/admin/agent/tasks/MISSING/trace') return route.fulfill({ status: 404 })
    unexpected.push(url.pathname)
    return route.fulfill({ status: 500 })
  })
  await page.goto('/admin')
  await page.getByRole('link', { name: '工具调用日志' }).click()
  const rows = page.getByRole('list', { name: '按时间排列的调用记录' })
  await expect(rows.getByText('TOOL_TIMEOUT')).toBeVisible()
  await expect(rows.getByText('5000 ms')).toBeVisible()
  await expect(rows.getByText('第 2 次 · 重试')).toBeVisible()
  await expect(rows.getByText('27 ms')).toBeVisible()
  await page.getByRole('button', { name: '加载后续记录' }).click()
  await expect(rows.getByText('核验预约')).toBeVisible()
  const search = page.getByRole('searchbox', { name: '按任务编号定位' })
  await search.fill('MISSING')
  await page.getByRole('button', { name: '查找任务' }).click()
  await expect(page.getByRole('alert')).toHaveText('找不到这条任务，请核对任务编号。')
  await search.fill('TRACE-001')
  await page.getByRole('button', { name: '查找任务' }).click()
  await expect(rows.getByText('第 2 次 · 重试')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  expect(unexpected).toEqual([])
})
