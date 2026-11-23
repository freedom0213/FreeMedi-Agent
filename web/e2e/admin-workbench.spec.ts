import { expect, test } from '@playwright/test'
import type { KnowledgeFragment } from '../src/api/admin'

const root = '/api/v1/admin'
const session = { adminId: '200', displayName: '资料管理员' }
const baseFragment: KnowledgeFragment = {
  id: 1, chunkNo: 1, heading: '测试片段', content: '测试正文', charCount: 4,
  embeddingStatus: 'FAILED', failureReason: '索引服务不可用', indexedAt: null,
  docNo: 'DOC-01', title: '预约说明', version: 1, docType: 'APPOINTMENT_RULE',
  source: '测试', documentStatus: 'PUBLISHED', vectorState: 'NOT_INDEXED', vectorModel: null, vectorDimensions: null,
}

test('工作台摘要可定位到待发布、失败片段、今日排班和 AI 失败，区块可单独重试', async ({ page }) => {
  let failed = 1
  let visitAvailable = false
  const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const totals = { booked: 2, remaining: 5, occupied: 2, portal: 2, agent: 0 }
  const document = { docNo: 'DOC-01', title: '预约说明', docType: 'APPOINTMENT_RULE', source: '测试',
    version: 1, status: 'PUBLISHED', indexed: 1, pending: 0, failed: 1, publishedAt: null }
  const model = { key: 'external:environment', mode: 'external', version: 'environment', label: null, modelName: null }
  const taskId = 'WORKBENCH-TASK'
  const record = { recordedAt: day + 'T02:30:00Z', taskId, sequence: 2, modelKey: model.key,
    attempt: 1, outcome: 'FAILED', reason: 'MODEL_TRANSIENT', durationMs: 32 }
  const task = { taskId, taskType: 'APPOINTMENT_BOOKING', status: 'NEED_MORE_INFO', turnCount: 1, updatedAt: record.recordedAt }

  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path === root + '/auth/session') return route.fulfill({ json: session })
    if (path === root + '/auth/csrf') return route.fulfill({ json: { ...session, headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (path === root + '/knowledge/documents') return route.fulfill({ json: [
      { document, versionCount: 2, draftVersion: 2 },
      { document: { ...document, docNo: 'DOC-02', title: '已发布资料' }, versionCount: 1, draftVersion: null },
    ] })
    if (path === root + '/knowledge/library') return route.fulfill({ json: [{ docType: 'APPOINTMENT_RULE', failed }] })
    if (path === root + '/knowledge/index/summary') return route.fulfill({ json: {
      model: 'fixture', dimensions: 10, store: 'MEMORY', collection: null, pointCount: 1, status: 'REACHABLE',
    } })
    if (path === root + '/knowledge/chunks') {
      expect(url.searchParams.get('status')).toBe('PUBLISHED')
      expect(url.searchParams.get('embedding')).toBe('FAILED')
      return route.fulfill({ json: { items: failed ? [baseFragment] : [], page: 1, hasNext: false, totalPages: failed } })
    }
    if (path === root + '/knowledge/chunks/1/retry-index') {
      failed = 0
      return route.fulfill({ json: { status: 'INDEXED' } })
    }
    if (path === root + '/catalog/departments/options') return route.fulfill({ json: [] })
    if (path === root + '/catalog/schedules') {
      expect(Object.fromEntries(url.searchParams)).toMatchObject({ from: day, to: day, status: 'OPEN' })
      return route.fulfill({ json: { items: [], page: 1, hasNext: false, totalPages: 0 } })
    }
    if (path === root + '/agent/tasks') return route.fulfill({ json: [task] })
    if (path === root + '/agent/tasks/' + taskId + '/trace') return route.fulfill({ json: { task, entries: [], nextAfter: null } })

    expect(url.searchParams.get('from')).toBe(day)
    expect(url.searchParams.get('to')).toBe(day)
    if (path === root + '/observability/appointments') {
      if (!visitAvailable) return route.fulfill({ status: 503 })
      return route.fulfill({ json: { from: day, to: day, totals, openSchedules: 3,
        trend: [{ date: day, ...totals }], departments: [], doctors: [] } })
    }
    if (path === root + '/observability/model-calls') return route.fulfill({ json: {
      totals: { attempts: 1, succeeded: 0, failed: 1, retries: 0, averageMs: 32, p95Ms: 32 },
      options: [model], models: [], trend: [{ date: day, succeeded: 0, failed: 1 }],
      reasons: [{ code: 'MODEL_TRANSIENT', attempts: 1 }],
    } })
    if (path === root + '/observability/model-calls/attempts') {
      expect(url.searchParams.get('outcome')).toBe('FAILED')
      return route.fulfill({ json: { items: [record], nextBefore: null } })
    }
    return route.fulfill({ status: 500 })
  })

  await page.goto('/admin')
  await expect(page.getByRole('button', { name: '重试今日门诊' })).toBeVisible()
  await expect(page.getByRole('link', { name: /待发布文档.*1 份/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /今日失败尝试.*1 次/ })).toBeVisible()
  await expect(page.getByText('模型服务暂时不可用')).toBeVisible()
  visitAvailable = true
  await page.getByRole('button', { name: '重试今日门诊' }).click()
  await expect(page.getByRole('link', { name: /开放排班.*3 场/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /有效预约.*2 笔/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /可预约余号.*5 个/ })).toHaveAttribute('href', `/admin/visit-operations?from=${day}&to=${day}`)
  totals.booked++
  totals.remaining--
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('link', { name: /有效预约.*3 笔/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /可预约余号.*4 个/ })).toBeVisible()

  await page.getByRole('link', { name: /待发布文档/ }).click()
  await expect(page).toHaveURL(/\/admin\/knowledge\?status=DRAFT$/)
  await expect(page.getByRole('row', { name: /已发布资料/ })).toHaveCount(0)
  await expect(page.getByRole('row', { name: /预约说明/ }).getByRole('link').first()).toHaveAttribute('href', '/admin/knowledge/DOC-01?version=2')
  await page.getByRole('link', { name: '工作台首页' }).first().click()
  await page.getByRole('link', { name: /索引失败片段/ }).click()
  await expect(page).toHaveURL(/status=PUBLISHED&embedding=FAILED#knowledge-fragment-list$/)
  await page.getByRole('button', { name: '重试索引' }).click()
  await expect(page.getByText('该片段的向量索引已建立。')).toBeVisible()
  await page.getByRole('link', { name: '工作台首页' }).first().click()
  await expect(page.getByRole('link', { name: /索引失败片段.*0 段/ })).toBeVisible()
  await page.getByRole('link', { name: /开放排班/ }).click()
  await expect(page).toHaveURL(url => url.pathname === '/admin/schedules'
    && url.searchParams.get('from') === day && url.searchParams.get('status') === 'OPEN')
  await page.getByRole('link', { name: '工作台首页' }).first().click()
  await page.getByRole('link', { name: /今日失败尝试/ }).click()
  await expect(page).toHaveURL(url => url.pathname === '/admin/model-calls'
    && url.searchParams.get('from') === day && url.searchParams.get('outcome') === 'FAILED')
  await expect(page.getByRole('combobox', { name: '结果', exact: true })).toHaveValue('FAILED')
  await page.getByRole('link', { name: taskId }).click()
  await expect(page).toHaveURL(/\/admin\/agent-timeline\?taskId=WORKBENCH-TASK$/)
})

test('长表格滚动时菜单、顶部导航和分页保留在视口，页码可直接跳转', async ({ page }) => {
  await page.setViewportSize({ width: 1180, height: 720 })
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname === root + '/auth/session') return route.fulfill({ json: session })
    const selected = Number(url.searchParams.get('page') ?? '1')
    expect(url.pathname).toBe(root + '/knowledge/chunks')
    expect(url.searchParams.get('status')).toBe('PUBLISHED')
    const items = Array.from({ length: 20 }, (_, index) => ({ ...baseFragment, id: selected * 100 + index,
      chunkNo: index + 1, heading: `第 ${selected} 页片段 ${index + 1}`, embeddingStatus: 'INDEXED' }))
    return route.fulfill({ json: { items, page: selected, totalPages: 8, hasNext: selected < 8 } })
  })
  await page.goto('/admin/knowledge-chunks?status=PUBLISHED')
  const body = page.getByRole('main', { name: '管理端内容' })
  const nav = page.getByRole('navigation', { name: '当前位置' })
  const menu = page.getByRole('navigation', { name: '管理菜单' })
  const logout = page.getByRole('button', { name: '退出系统' })
  const pagination = page.getByRole('navigation', { name: '表格分页' })
  await expect(page.getByRole('rowheader', { name: /^第 1 页片段 1 片段/ })).toBeVisible()
  await expect(pagination).toBeInViewport()
  const positions = await Promise.all([nav.boundingBox(), menu.boundingBox(), logout.boundingBox()])
  await body.evaluate(element => { element.scrollTop = 530 })
  await expect.poll(() => body.evaluate(element => element.scrollTop)).toBeGreaterThan(200)
  await expect(pagination).toBeInViewport()
  expect(await Promise.all([nav.boundingBox(), menu.boundingBox(), logout.boundingBox()])).toEqual(positions)
  expect(await page.evaluate(() => window.scrollY)).toBe(0)
  await pagination.getByRole('button', { name: '第 3 页', exact: true }).click()
  await expect(page.getByRole('rowheader', { name: /^第 3 页片段 1 片段/ })).toBeVisible()
  await expect(pagination.getByRole('button', { name: '第 3 页', exact: true })).toHaveAttribute('aria-current', 'page')
  await pagination.getByRole('button', { name: '上一页' }).click()
  await expect(page.getByRole('rowheader', { name: /^第 2 页片段 1 片段/ })).toBeVisible()
})
