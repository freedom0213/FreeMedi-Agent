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
  let signed = false
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
    if (url.pathname === '/api/v1/admin/appointments') {
      expect(url.searchParams.get('scheduleId')).toBe('1')
      expect(url.searchParams.get('doctorId')).toBe('3')
      expect(url.searchParams.get('from')).toBe(rows[0].scheduleDate)
      return route.fulfill({ json: { page: 1, totalCount: 1, totalPages: 1, items: [{
        scheduleId: 1, appointment: { appointmentNo: 'APT-READ-ONLY', appointmentDate: rows[0].scheduleDate,
          timeRange: '09:00-09:15', departmentName: '康复科', doctorName: '周医生', status: signed ? 'CHECKED_IN' : 'BOOKED',
          canCheckIn: !signed, checkedInAt: signed ? '2026-11-23T01:00:00Z' : null,
          bookedVia: 'PORTAL', bookedAt: '2026-11-23T03:00:00Z' },
      }] } })
    }
    expect(request.headers()['x-csrf-token']).toBe('fixture')
    if (url.pathname === '/api/v1/admin/appointments/APT-READ-ONLY/check-in') {
      expect(request.method()).toBe('POST')
      signed = true
      return route.fulfill({ json: { success: true, code: null, message: '签到成功。' } })
    }
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
  await page.getByRole('link', { name: '查看预约', exact: true }).click()
  await expect(page.getByRole('heading', { name: '预约明细' })).toBeVisible()
  await expect(page.getByLabel('医生', { exact: true })).toHaveValue('3')
  await expect(page.getByRole('row', { name: /APT-READ-ONLY.*自助挂号.*已预约/ })).toBeVisible()
  await page.getByRole('button', { name: '办理签到' }).click()
  await expect(page.getByRole('group', { name: '核对签到预约' })).toContainText('APT-READ-ONLY')
  await page.getByRole('button', { name: '确认签到' }).click()
  await expect(page.getByRole('row', { name: /APT-READ-ONLY.*已签到/ })).toBeVisible()
  await expect(page.getByRole('button', { name: '办理签到' })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})

test('预约明细可以筛选已过期，过期未签到的记录显示签到已截止', async ({ page }) => {
  await page.route('**/api/v1/admin/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/options')) return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/appointments')) {
      const status = url.searchParams.get('status')
      return route.fulfill({ json: { items: status === 'BOOKED' ? [] : [{ scheduleId: 1, appointment: {
        appointmentNo: 'APT-EXPIRED', appointmentDate: '2026-11-24', timeRange: '14:30-15:00',
        departmentName: '消化内科', doctorName: '孙医生', status: 'EXPIRED', canCheckIn: false,
        checkInOpensAt: '2026-11-24T06:00:00Z', bookedVia: 'AGENT', bookedAt: '2026-11-24T06:06:00Z',
      } }], page: 1, totalPages: 1 } })
    }
    throw new Error('Unexpected request: ' + url.pathname)
  })
  await page.goto('/admin/appointments')
  const row = page.getByRole('row', { name: /APT-EXPIRED/ })
  await expect(row).toContainText('已过期')
  await expect(row).toContainText('签到已截止')
  await expect(row.getByRole('button', { name: '办理签到' })).toHaveCount(0)
  await page.getByLabel('预约状态').selectOption('EXPIRED')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page).toHaveURL(/status=EXPIRED/)
  await expect(row).toBeVisible()
})

test('预约明细选科室后才加载医生，医生选项重试成功后清除提示', async ({ page }) => {
  const requestedDepartments: (string | null)[] = []
  await page.route('**/api/v1/admin/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/departments/options')) return route.fulfill({ json: [{ id: 2, name: '呼吸内科' }] })
    if (url.pathname.endsWith('/doctors/options')) {
      requestedDepartments.push(url.searchParams.get('departmentId'))
      if (requestedDepartments.length === 1) return route.fulfill({ status: 503 })
      return route.fulfill({ json: [{ id: 3, name: '陈医生' }] })
    }
    if (url.pathname.endsWith('/appointments')) return route.fulfill({ json: { items: [], page: 1, totalPages: 0 } })
    throw new Error('Unexpected request: ' + url.pathname)
  })

  await page.goto('/admin/appointments')
  const doctor = page.getByLabel('医生', { exact: true })
  await expect(page.getByText('没有符合条件的预约记录。')).toBeVisible()
  await expect(doctor).toBeDisabled()
  await expect(doctor).toContainText('请先选择科室')
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(requestedDepartments).toEqual([])

  await page.getByLabel('科室', { exact: true }).selectOption('2')
  const error = page.getByRole('alert').filter({ hasText: '医生选项加载失败' })
  await expect(error).toBeVisible()
  await expect(doctor).toBeDisabled()
  await error.getByRole('button', { name: '重试' }).click()
  await expect(doctor).toBeEnabled()
  await expect(error).toHaveCount(0)
  await doctor.selectOption('3')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page).toHaveURL(/departmentId=2&doctorId=3/)

  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(doctor).toBeDisabled()
  await expect(doctor).toContainText('请先选择科室')
  expect(requestedDepartments).toEqual(['2', '2'])
})

test('管理员从预约操作记录核对改约结果和关联预约', async ({ page }) => {
  await page.route('**/api/v1/admin/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/departments/options')) return route.fulfill({ json: [] })
    if (url.pathname.endsWith('/appointments')) {
      const appointmentNo = url.searchParams.get('appointmentNo') || 'APT-ORIGINAL'
      return route.fulfill({ json: { page: 1, totalPages: 1, items: [{ scheduleId: 1, appointment: {
        appointmentNo, appointmentDate: '2026-11-25', timeRange: '09:00–09:15', departmentName: '心内科',
        doctorName: '林医生', status: appointmentNo === 'APT-ORIGINAL' ? 'RESCHEDULED' : 'BOOKED',
        bookedVia: 'PORTAL', bookedAt: '2026-11-23T01:00:00Z',
      } }] } })
    }
    if (url.pathname.endsWith('/operations')) {
      const original = url.pathname.includes('APT-ORIGINAL')
      return route.fulfill({ json: {
        appointmentNo: original ? 'APT-ORIGINAL' : 'APT-NEW',
        originalAppointmentNo: original ? null : 'APT-ORIGINAL',
        replacementAppointmentNo: original ? 'APT-NEW' : null,
        events: { page: 1, totalPages: 1, items: [{
          time: '2026-11-23T02:00:00Z', action: 'APPOINTMENT_RESCHEDULE',
          operatorType: 'PATIENT', result: 'SUCCEEDED', reason: null,
        }] },
      } })
    }
    throw new Error('Unexpected request: ' + url.pathname)
  })
  await page.goto('/admin/appointments')
  await page.getByRole('row', { name: /APT-ORIGINAL.*已改约/ }).getByRole('button', { name: '操作记录' }).click()
  const dialog = page.getByRole('dialog', { name: '预约操作记录' })
  await expect(dialog).toContainText('患者本人')
  await expect(dialog).toContainText('成功')
  await dialog.getByRole('button', { name: '查看新预约' }).click()
  await expect(page).toHaveURL(/appointmentNo=APT-NEW/)
  await expect(dialog).toContainText('APT-NEW')
  await expect(dialog.getByRole('button', { name: '查看改约前的预约' })).toBeVisible()
  await dialog.getByRole('button', { name: '关闭' }).click()
  await expect(page.getByRole('row', { name: /APT-NEW.*已预约/ })).toBeVisible()
})
