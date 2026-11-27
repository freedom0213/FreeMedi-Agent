import { expect, test } from '@playwright/test'
import type { HospitalSetup } from '../src/api/admin'
import { emptyWorkbenchResponse } from './admin-workbench-fixture'

test('用本次填写的患者查号范围检查号源并完成首次启用', async ({ page }) => {
  const base = '/api/v1/admin/setup'
  const defaults = { scheduleDays: 90, patientDays: 28, cancellationCutoff: '23:00', checkInLead: 30 }
  const setup: HospitalSetup = {
    profile: { name: '', contact: '', activatedAt: null },
    rules: { ...defaults }, defaults, timezone: 'Asia/Shanghai', required: true,
    checks: { departments: 1, doctors: 1, schedules: 0 },
  }
  let failExtendedCheck = false
  const unexpected: string[] = []
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const workbench = emptyWorkbenchResponse(url)
    if (workbench) return route.fulfill({ json: workbench })
    if (path.endsWith('/auth/session')) {
      return route.fulfill({ json: { adminId: '200', displayName: '系统管理员',
        grants: [{ role: 'SYSTEM_ADMIN', departmentId: null }] } })
    }
    if (path.endsWith('/auth/csrf')) {
      return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    }
    if (path === base && request.method() === 'GET') return route.fulfill({ json: setup })
    if (path === base + '/checks' && request.method() === 'GET') {
      const days = Number(url.searchParams.get('patientDays'))
      expect([28, 60]).toContain(days)
      if (days === 60 && failExtendedCheck) {
        failExtendedCheck = false
        return route.fulfill({ status: 503 })
      }
      return route.fulfill({ json: { ...setup.checks, schedules: days >= 40 ? 1 : 0 } })
    }
    if (path === base + '/activate' && request.method() === 'PUT') {
      const { name, contact, ...rules } = request.postDataJSON()
      expect({ name, contact, ...rules }).toEqual({
        ...defaults, patientDays: 60, name: '验收医院', contact: '0000-000000',
      })
      setup.profile = { name, contact, activatedAt: '2026-11-27T04:00:00Z' }
      setup.rules = rules
      setup.checks.schedules = 1
      setup.required = false
      return route.fulfill({ json: setup })
    }
    unexpected.push(path)
    return route.fulfill({ status: 500 })
  })

  await page.goto('/admin/setup')
  await page.getByLabel('医院名称').fill('验收医院')
  await page.getByLabel('门诊咨询电话').fill('0000-000000')
  const review = page.getByRole('button', { name: '检查与启用', exact: true })
  const activate = page.getByRole('button', { name: '确认启用', exact: true })
  await review.click()
  await expect(page.getByText('有可用号源的开放排班 0 场')).toBeVisible()
  await expect(activate).toBeDisabled()

  const setWindow = async (days: string) => {
    await page.getByRole('button', { name: '业务规则', exact: true }).click()
    await page.getByLabel('患者查号范围（含当天）').fill(days)
    await review.click()
  }
  await setWindow('60')
  await expect(page.getByText('有可用号源的开放排班 1 场')).toBeVisible()
  await expect(activate).toBeEnabled()

  await setWindow('28')
  await expect(page.getByText('有可用号源的开放排班 0 场')).toBeVisible()
  await expect(activate).toBeDisabled()
  failExtendedCheck = true
  await setWindow('60')
  const retry = page.getByRole('button', { name: '重试检查' })
  await expect(retry).toBeVisible()
  await expect(activate).toBeDisabled()
  await retry.click()
  await expect(activate).toBeEnabled()
  await activate.click()
  await expect(page.getByText('医院资料和业务规则已生效。')).toBeVisible()
  expect(unexpected).toEqual([])
})
