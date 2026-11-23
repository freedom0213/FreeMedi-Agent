import { expect, test } from '@playwright/test'
import type { RegisteredTool } from '../src/api/admin'

test('从管理菜单查看三个工具、参数和患者确认边界', async ({ page }) => {
  const tools: RegisteredTool[] = [
    {
      name: 'findAvailableSlots', description: '按预约条件查询号源。',
      parameters: [{ name: 'departmentId', type: 'INTEGER', required: false, description: '科室 ID' }],
    },
    { name: 'getAppointmentRules', description: '查询预约系统的技术限制。', parameters: [] },
    {
      name: 'searchHospitalKnowledge', description: '检索院内资料并返回依据。',
      parameters: [{ name: 'query', type: 'STRING', required: true, description: '患者本轮资料问题' }],
    },
  ]
  await page.setViewportSize({ width: 390, height: 844 })
  const unexpected: string[] = []
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/admin/auth/session') return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (path === '/api/v1/admin/agent/tools' && route.request().method() === 'GET') {
      return route.fulfill({ json: tools })
    }
    unexpected.push(path)
    return route.fulfill({ status: 500 })
  })
  await page.goto('/admin')
  await page.getByRole('link', { name: '工具注册表' }).click()
  await expect(page.getByText('创建预约需要患者确认')).toBeVisible()
  await expect(page.getByText(/点击「确认预约」后，后端才会创建并核验预约/)).toBeVisible()
  const registry = page.getByRole('region', { name: '模型可调用的工具' })
  await expect(registry.locator('summary')).toHaveCount(3)
  await expect(registry.getByText('按预约条件查询号源。')).toBeVisible()
  await expect(registry.getByRole('row', { name: /departmentId.*integer.*可选/ })).toBeVisible()
  await registry.locator('summary').filter({ hasText: 'getAppointmentRules' }).click()
  await expect(registry.getByText('查询预约系统的技术限制。')).toBeVisible()
  await expect(registry.getByText('此工具无需参数。')).toBeVisible()
  await registry.locator('summary').filter({ hasText: 'searchHospitalKnowledge' }).click()
  await expect(registry.getByText('检索院内资料并返回依据。')).toBeVisible()
  await expect(registry.getByRole('row', { name: /query.*string.*必填/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  expect(unexpected).toEqual([])
})

test('停用前解释影响，启用后可保存单独超时并恢复继承', async ({ page }) => {
  const tool: RegisteredTool = {
    name: 'findAvailableSlots', description: '按条件查询号源', parameters: [],
    enabled: true, timeoutMs: null, defaultTimeoutMs: 5000, revision: 0,
  }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (path.endsWith('/tools')) return route.fulfill({ json: [tool] })
    expect(path).toBe('/api/v1/admin/agent/tools/findAvailableSlots')
    expect(route.request().method()).toBe('PUT')
    const { enabled, timeoutMs, expectedRevision } = route.request().postDataJSON()
    expect(expectedRevision).toBe(tool.revision)
    Object.assign(tool, { enabled, timeoutMs, revision: expectedRevision + 1 })
    return route.fulfill({ json: [tool] })
  })
  await page.goto('/admin/tool-registry')
  await page.getByRole('button', { name: '停用', exact: true }).click()
  await expect(page.getByText(/仍可使用普通挂号/)).toBeVisible()
  await page.getByRole('button', { name: '取消' }).click()
  await expect(page.getByText('已启用', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '停用', exact: true }).click()
  await page.getByRole('button', { name: '确认停用' }).click()
  await expect(page.getByText('已停用', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '启用', exact: true }).click()
  await expect(page.getByText('已启用', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '编辑超时' }).click()
  await page.getByLabel('继承默认超时').uncheck()
  await page.getByLabel('单次超时（毫秒）').fill('1200')
  await page.getByRole('button', { name: '保存超时' }).click()
  await expect(page.getByText('单次超时：1200 毫秒')).toBeVisible()
  await page.getByRole('button', { name: '编辑超时' }).click()
  await page.getByLabel('继承默认超时').check()
  await page.getByRole('button', { name: '保存超时' }).click()
  await expect(page.getByText(/继承默认 · 5000/)).toBeVisible()
})
