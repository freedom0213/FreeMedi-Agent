import { expect, test } from '@playwright/test'
import { emptyWorkbenchResponse } from './admin-workbench-fixture'
import type { DepartmentRow, ScheduleRow } from '../src/api/admin'

test('窄屏分组导航、新建科室、预览排班并处理预约冲突', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const department: DepartmentRow = { id: 2, code: 'OPS', name: '康复科', description: null, status: 'ACTIVE' }
  const doctor = { id: 3, name: '周医生', status: 'ACTIVE' }
  const rows: ScheduleRow[] = []
  let created = false
  let closes = 0
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const workbench = emptyWorkbenchResponse(url)
    if (workbench) return route.fulfill({ json: workbench })
    const request = route.request()
    const base = '/api/v1/admin/catalog'
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/auth/csrf')) return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (url.pathname === base + '/departments/options') return route.fulfill({ json: [department] })
    if (url.pathname === base + '/doctors/options') {
      expect(url.searchParams.get('departmentId')).toBe('2')
      return route.fulfill({ json: [doctor] })
    }
    if (url.pathname === base + '/departments' && request.method() === 'GET') {
      return route.fulfill({ json: { items: created ? [department] : [], page: 1, hasNext: false } })
    }
    if (url.pathname === base + '/schedules' && request.method() === 'GET') {
      return route.fulfill({ json: { items: rows, page: 1, hasNext: false } })
    }
    if (url.pathname === base + '/schedules/1') return route.fulfill({ json: {
      schedule: rows[0],
      slots: [{ number: 1, startAt: rows[0].scheduleDate + 'T01:00:00Z', endAt: rows[0].scheduleDate + 'T01:15:00Z', status: 'AVAILABLE' }],
    } })
    expect(request.headers()['x-csrf-token']).toBe('fixture')
    if (url.pathname === base + '/departments' && request.method() === 'POST') {
      expect(request.postDataJSON()).toMatchObject({ code: 'OPS', name: '康复科' })
      created = true
      return route.fulfill({ status: 201 })
    }
    if (url.pathname === base + '/schedules' && request.method() === 'POST') {
      const input = request.postDataJSON()
      expect(input).toMatchObject({ departmentId: 2, doctorId: 3, period: 'MORNING', start: '09:00', minutes: 15, count: 4 })
      rows.push({ id: 1, departmentId: 2, departmentName: '康复科', doctorId: 3, doctorName: '周医生',
        scheduleDate: input.date, clinicPeriod: 'MORNING', slots: 4, remaining: 4, booked: 0, status: 'OPEN' })
      return route.fulfill({ status: 201 })
    }
    if (url.pathname === base + '/schedules/1/close') {
      closes += 1
      Object.assign(rows[0], { booked: 1, remaining: 3 })
      return route.fulfill({ status: 409, json: { error: '已有 1 笔有效预约，无法关闭此排班。' } })
    }
    throw new Error('Unexpected call: ' + url.pathname)
  })

  await page.goto('/admin')
  await page.getByRole('button', { name: '展开菜单' }).click()
  await page.getByRole('button', { name: '基础数据管理' }).click()
  await page.getByRole('link', { name: '科室管理' }).click()
  await page.getByRole('button', { name: '新增科室' }).click()
  await page.getByLabel('科室编码').fill('OPS')
  await page.getByLabel('科室名称', { exact: true }).fill('康复科')
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByRole('row', { name: /康复科.*OPS/ })).toBeVisible()
  await page.getByRole('button', { name: '展开菜单' }).click()
  await page.getByRole('link', { name: '排班管理' }).click()
  await page.getByRole('button', { name: '新增排班' }).click()
  await page.getByLabel('排班科室').selectOption('2')
  await page.getByLabel('出诊医生').selectOption('3')
  await expect(page.getByText('09:45–10:00')).toBeVisible()
  await page.getByRole('button', { name: '保存并开放号源' }).click()
  await expect(page.getByText('排班已开放，并生成 4 个号源。')).toBeVisible()
  await page.getByRole('button', { name: '查看号源' }).click()
  await expect(page.getByText(/1 号 · 09:00–09:15/)).toBeVisible()
  await page.getByRole('button', { name: '关闭排班' }).click()
  await page.getByRole('button', { name: '确认关闭' }).click()
  await expect(page.getByRole('alert')).toHaveText('已有 1 笔有效预约，无法关闭此排班。')
  await expect(page.getByRole('button', { name: '关闭排班' })).toHaveCount(0)
  expect(closes).toBe(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.reload()
  await page.getByRole('button', { name: '展开菜单' }).click()
  await expect(page.getByRole('link', { name: '排班管理' })).toHaveAttribute('aria-current', 'page')
})
