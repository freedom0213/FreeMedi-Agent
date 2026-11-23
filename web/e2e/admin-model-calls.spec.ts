import { expect, test } from '@playwright/test'
import type { ModelCallAttempt, ModelCallOverview, ObservedModel } from '../src/api/admin'

test('按模型版本排查失败、加载更多记录并跳转到任务时间线', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const date = '2026-11-20'
  const time = `${date}T01:30:00Z`
  const demo: ObservedModel = { key: 'demo:environment', mode: 'demo', version: 'environment', label: null, modelName: null }
  const version = '9064f020-b7a7-4f93-a8e5-845d05eb0621/v2'
  const external: ObservedModel = { key: `external:${version}`, mode: 'external', version, label: '导诊服务', modelName: 'fixture-model' }
  const failed: ModelCallAttempt = { recordedAt: time, taskId: 'TRACE-CALL-001', sequence: 2, modelKey: external.key,
    attempt: 1, outcome: 'FAILED', reason: 'MODEL_TRANSIENT', durationMs: 45 }
  const success: ModelCallAttempt = { ...failed, sequence: 3, attempt: 2, outcome: 'SUCCEEDED', reason: null, durationMs: 14 }
  const fixtureMetrics = { attempts: 2, succeeded: 1, failed: 1, retries: 1, averageMs: 29.5, p95Ms: 45 }
  const demoMetrics = { attempts: 1, succeeded: 1, failed: 0, retries: 0, averageMs: 4, p95Ms: 4 }
  const task = { taskId: failed.taskId, taskType: 'APPOINTMENT_BOOKING', status: 'NEED_MORE_INFO', turnCount: 1, updatedAt: time }

  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/agent/tasks')) return route.fulfill({ json: [task] })
    if (url.pathname.endsWith('/agent/tasks/TRACE-CALL-001/trace')) {
      return route.fulfill({ json: { task, nextAfter: null, entries: [
        { ...failed, type: 'MODEL_DECISION', turnNo: 1, stepNo: 1, tool: null, resultCount: null, modelVersion: version },
      ] } })
    }
    expect(url.searchParams.get('from')).toBe(date)
    expect(url.searchParams.get('to')).toBe(date)
    const selected = url.searchParams.get('model')
    if (url.pathname.endsWith('/observability/model-calls')) {
      const demoRow = { model: demo, metrics: demoMetrics }
      const externalRow = { model: external, metrics: fixtureMetrics }
      const overview: ModelCallOverview = {
        totals: selected ? fixtureMetrics : { ...fixtureMetrics, attempts: 3, succeeded: 2, averageMs: 21 },
        options: [demo, external], models: selected ? [externalRow] : [demoRow, externalRow],
        trend: [{ date, succeeded: selected ? 1 : 2, failed: 1 }], reasons: [{ code: 'MODEL_TRANSIENT', attempts: 1 }],
      }
      return route.fulfill({ json: overview })
    }
    if (url.pathname.endsWith('/model-calls/attempts')) {
      if (url.searchParams.get('outcome') === 'FAILED') {
        expect(selected).toBe(external.key)
        return route.fulfill({ json: { items: [failed], nextBefore: null } })
      }
      if (url.searchParams.has('before')) {
        expect(url.searchParams.get('before')).toBe('100:30')
        return route.fulfill({ json: { items: [failed], nextBefore: null } })
      }
      return route.fulfill({ json: { items: [success], nextBefore: '100:30' } })
    }
    throw new Error(`Unexpected API call: ${url.pathname}`)
  })

  await page.goto(`/admin/model-calls?from=${date}&to=${date}`)
  await expect(page.getByRole('heading', { name: 'AI 调用观测' })).toBeVisible()
  await expect(page.getByRole('row', { name: /本地演示规则.*启动配置/ })).toBeVisible()
  await expect(page.getByRole('row', { name: /导诊服务.*v2.*fixture-model.*2.*50%.*1.*29.5 ms.*45 ms/ })).toBeVisible()
  await expect(page.getByRole('row', { name: /模型服务暂时不可用.*MODEL_TRANSIENT.*1/ })).toBeVisible()
  await page.getByRole('combobox', { name: '模型版本' }).selectOption(external.key)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.getByRole('row', { name: /本地演示规则.*启动配置/ })).toHaveCount(0)
  expect(new URL(page.url()).searchParams.get('model')).toBe(external.key)
  const details = page.getByRole('region', { name: '模型判断记录' })
  await page.getByRole('button', { name: '加载更多明细' }).click()
  await expect(details.getByRole('link', { name: 'TRACE-CALL-001' })).toHaveCount(2)
  await page.getByRole('combobox', { name: '结果', exact: true }).selectOption('FAILED')
  await expect(details.getByRole('link', { name: 'TRACE-CALL-001' })).toHaveCount(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await details.getByRole('link', { name: 'TRACE-CALL-001' }).click()
  await expect(page).toHaveURL(/\/admin\/agent-timeline\?taskId=TRACE-CALL-001$/)
  await expect(page.getByRole('region', { name: '任务执行记录' }).getByText('MODEL_TRANSIENT')).toBeVisible()
})
