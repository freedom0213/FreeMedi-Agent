import { expect, test } from '@playwright/test'
import type { AppointmentOperations, ScheduleRow } from '../src/api/admin'

test('运营看板区分预约渠道并带着日期和医生跳转到排班', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const from = '2026-11-26'
  const to = '2026-11-27'
  const department = { id: 2, name: '康复科', status: 'ACTIVE' }
  const doctor = { id: 3, name: '周医生', status: 'ACTIVE' }
  const counts = { booked: 3, portal: 2, agent: 1, remaining: 5, occupied: 3 }
  const dashboard: AppointmentOperations = {
    from, to, totals: counts, openSchedules: 1,
    trend: [{ date: from, booked: 3, portal: 2, agent: 1 }, { date: to, booked: 0, portal: 0, agent: 0 }],
    departments: [{ departmentId: department.id, departmentName: department.name, counts }],
    doctors: [{ doctorId: doctor.id, doctorName: doctor.name, departmentId: department.id, departmentName: department.name, counts }],
  }
  const schedule: ScheduleRow = { id: 11, departmentId: department.id, departmentName: department.name,
    doctorId: doctor.id, doctorName: doctor.name, scheduleDate: from, clinicPeriod: 'MORNING',
    slots: 8, remaining: 5, booked: 3, status: 'OPEN' }
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/departments/options')) return route.fulfill({ json: [department] })
    if (url.pathname.endsWith('/doctors/options')) return route.fulfill({ json: [doctor] })
    if (url.pathname.endsWith('/observability/appointments')) {
      expect(Object.fromEntries(url.searchParams)).toEqual({ from, to, departmentId: '2' })
      return route.fulfill({ json: dashboard })
    }
    if (url.pathname.endsWith('/catalog/schedules')) {
      expect(Object.fromEntries(url.searchParams)).toMatchObject({ from, to, departmentId: '2', doctorId: '3' })
      return route.fulfill({ json: { items: [schedule], page: 1, hasNext: false } })
    }
    throw new Error('Unexpected call: ' + url.pathname)
  })

  await page.goto(`/admin/visit-operations?from=${from}&to=${to}&departmentId=2`)
  await expect(page.getByRole('heading', { name: '就诊运营看板' })).toBeVisible()
  await expect(page.getByRole('row', { name: /康复科.*3.*2.*1.*5.*3/ })).toBeVisible()
  await page.getByText('查看每日数据', { exact: true }).click()
  const daily = page.getByRole('region', { name: '每日预约数据表' })
  await expect(daily.getByRole('row', { name: /2026-11-26.*3.*2.*1/ })).toBeVisible()
  await expect(daily.getByRole('row', { name: /2026-11-27.*0.*0.*0/ })).toBeVisible()
  const dayLink = await daily.getByRole('link', { name: `查看 ${from} 的排班` }).getAttribute('href')
  const dayQuery = new URL(dayLink!, page.url()).searchParams
  expect(Object.fromEntries(dayQuery)).toEqual({ departmentId: '2', from, to: from })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)

  await page.getByRole('button', { name: '按医生' }).click()
  await expect(page.getByRole('row', { name: /周医生.*康复科.*3.*2.*1.*5.*3/ })).toBeVisible()
  await page.getByRole('link', { name: '查看周医生的排班' }).click()
  await expect(page.getByRole('heading', { name: '排班管理' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: '科室', exact: true })).toHaveValue('2')
  await expect(page.getByRole('combobox', { name: '医生', exact: true })).toHaveValue('3')
  await expect(page.getByLabel('出诊开始日期')).toHaveValue(from)
  await expect(page.getByLabel('出诊结束日期')).toHaveValue(to)
  await expect(page.getByRole('row', { name: /周医生.*康复科/ })).toBeVisible()
})
