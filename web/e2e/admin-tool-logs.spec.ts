import { expect, test } from '@playwright/test'
import { emptyWorkbenchResponse } from './admin-workbench-fixture'
import type { AgentTaskSummary, AgentTraceEntry } from '../src/api/admin'

test('按任务查看日志、跳到执行时间线，并筛选和加载后续步骤', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const recordedAt = '2026-11-20T01:30:00Z'
  const task: AgentTaskSummary = {
    taskId: 'TRACE-001', taskType: 'APPOINTMENT_BOOKING', status: 'COMPLETED', turnCount: 1, updatedAt: recordedAt,
  }
  const tool: AgentTraceEntry = {
    sequence: 2, recordedAt, type: 'READ_TOOL_ATTEMPT', turnNo: 1, stepNo: 2,
    attempt: 1, outcome: 'FAILED', reason: 'TOOL_TIMEOUT', durationMs: 5000, tool: 'findAvailableSlots', resultCount: null,
  }
  const unexpected: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const workbench = emptyWorkbenchResponse(url)
    if (workbench) return route.fulfill({ json: workbench })
    if (url.pathname === '/api/v1/admin/auth/session') {
      return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    }
    if (url.pathname === '/api/v1/admin/agent/tasks') return route.fulfill({ json: [task] })
    if (url.pathname === '/api/v1/admin/agent/tasks/TRACE-001/trace') {
      if (url.searchParams.get('after') === '4') {
        return route.fulfill({ json: { task, nextAfter: null, entries: [
          { ...tool, sequence: 5, type: 'APPOINTMENT_WRITE', tool: null, outcome: 'SUCCEEDED', reason: null, durationMs: 22 },
          { ...tool, sequence: 6, type: 'APPOINTMENT_VERIFY', tool: null, outcome: 'SUCCEEDED', reason: null, durationMs: 8 },
        ] } })
      }
      return route.fulfill({ json: { task, nextAfter: 4, entries: [
        { ...tool, sequence: 1, type: 'MODEL_DECISION', stepNo: 1, tool: null, outcome: 'SUCCEEDED',
          reason: null, durationMs: 96, modelVersion: 'environment' },
        tool, { ...tool, sequence: 3, attempt: 2, outcome: 'SUCCEEDED', reason: null, durationMs: 27, resultCount: 3 },
        { ...tool, sequence: 4, type: 'CONFIRMATION_CLAIMED', tool: null, outcome: 'STARTED', reason: null, durationMs: null },
      ] } })
    }
    if (url.pathname === '/api/v1/admin/agent/tasks/MISSING/trace') return route.fulfill({ status: 404 })
    unexpected.push(url.pathname)
    return route.fulfill({ status: 500 })
  })
  await page.goto('/admin')
  await page.getByRole('button', { name: '展开菜单' }).click()
  await page.getByRole('button', { name: 'Agent 工具中心' }).click()
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

  await page.getByRole('link', { name: '查看执行时间线' }).click()
  await expect(page).toHaveURL(/\/admin\/agent-timeline\?taskId=TRACE-001$/)
  const timeline = page.getByRole('region', { name: '任务执行记录' })
  const events = timeline.getByRole('list', { name: '第 1 轮执行事件' })
  await expect(events.getByRole('listitem')).toHaveCount(4)
  await expect(events.getByRole('listitem').first()).toContainText('模型判断')
  await expect(events.getByText('模型版本 启动配置')).toBeVisible()
  await expect(events.getByText('第 2 次 · 重试')).toBeVisible()
  await expect(events.getByText('接收预约确认')).toBeVisible()
  await timeline.getByRole('button', { name: '预约办理' }).click()
  await expect(events.getByRole('listitem')).toHaveCount(1)
  await timeline.getByRole('button', { name: '加载后续记录' }).click()
  await expect(events.getByRole('listitem')).toHaveCount(3)
  await expect(events.getByText('创建预约')).toBeVisible()
  await expect(events.getByText('核验预约')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.reload()
  await expect(timeline.getByText('TRACE-001', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '展开菜单' }).click()
  await expect(page.getByRole('button', { name: '统计与观测' })).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByRole('link', { name: 'Agent 执行时间线' })).toHaveAttribute('aria-current', 'page')
  await timeline.getByRole('link', { name: '查看工具调用日志' }).click()
  await expect(page).toHaveURL(/\/admin\/tool-logs\?taskId=TRACE-001$/)
  expect(unexpected).toEqual([])
})
