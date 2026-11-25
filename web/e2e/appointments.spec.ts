import type { Page } from '@playwright/test'
import { expect, guideNumber, guideRequest, guideTaskId, portalNumber, test, visitAnswer, visitDate, visitQuestion, visitSource } from './fixtures'

const detailUrl = (number: string) => '/appointments?appointmentNo=' + number
const preparationUrl = (number: string) => '/appointments/' + number + '/preparation'

test('普通挂号筛选、换号确认后可以打开原预约和就诊准备', async ({ page, patientApi }) => {
  await page.goto('/')
  await page.getByRole('link', { name: '呼吸内科', exact: true }).click()
  await expect(page.getByRole('heading', { name: '筛选医生和时间' })).toBeVisible()
  await page.getByRole('button', { name: /^周宁/ }).click()
  const date = page.getByLabel('就诊日期')
  await date.fill(visitDate)
  const afternoon = page.getByRole('button', { name: '下午', exact: true })
  await afternoon.click()

  const choices = page.getByRole('button', { name: '选此号源' })
  await expect(choices).toHaveCount(2)
  await choices.first().click()
  await expect(page.getByText('14:00–14:30', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回换号' }).click()
  await expect(date).toHaveValue(visitDate)
  await expect(afternoon).toHaveAttribute('aria-pressed', 'true')
  await choices.last().click()
  await expect(page.getByText('15:00–15:30', { exact: true })).toBeVisible()
  expect(patientApi.requestsFor('POST /appointments')).toHaveLength(0)
  await page.getByRole('button', { name: '确认预约', exact: true }).click()
  await expect(page.getByText('预约已创建', { exact: true })).toBeVisible()
  expect(patientApi.requestsFor('POST /appointments').map(request => request.body.slotId)).toEqual([6102])

  await openPreparationFromSuccess(page, portalNumber, '自助挂号')
  await askAndExpandSource(page)
  await returnToPreviousPage(page, portalNumber, '/booking?departmentId=2002')
  expect(patientApi.requestsFor('POST /appointments/' + portalNumber + '/consultation')).toHaveLength(1)
})

test('直接打开就诊准备时返回同一笔详情，不新增历史记录', async ({ page, patientApi }) => {
  patientApi.seedAppointment()
  await page.goto(preparationUrl(portalNumber))
  await expect(page.getByRole('heading', { name: '就诊准备', exact: true })).toBeVisible()
  const before = await page.evaluate(() => history.length)
  const back = page.getByRole('link', { name: '返回预约详情' })
  await expect(back).toHaveAttribute('href', detailUrl(portalNumber))
  await back.press('Enter')
  await expect(page).toHaveURL(detailUrl(portalNumber))
  await expect(page.getByText(portalNumber, { exact: true })).toBeVisible()
  expect(await page.evaluate(() => history.length)).toBe(before)
})

test('预约列表突出下一笔待就诊，并把取消和已过时间的预约放进历史', async ({ page, patientApi }) => {
  const next = patientApi.seedAppointment()
  patientApi.appointments.unshift(
    { ...next, appointmentNo: 'E2E-LATER', appointmentDate: '2026-11-21', timeRange: '13:30–14:00' },
    { ...next, appointmentNo: 'E2E-PAST', appointmentDate: '2026-11-19', timeRange: '09:00–09:30' },
    { ...next, appointmentNo: 'E2E-CANCELLED', status: 'CANCELLED', timeRange: '13:00–13:30' },
  )
  await page.goto('/appointments')
  const upcoming = page.getByRole('region', { name: '下一次就诊' })
  await expect(upcoming.getByRole('link', { name: '查看详情' })).toHaveAttribute('href', detailUrl(portalNumber))
  await expect(page.getByRole('region', { name: '其他待就诊预约' }).getByRole('link')).toHaveAttribute('href', detailUrl('E2E-LATER'))
  await expect(page.getByRole('region', { name: '历史记录' }).getByRole('link')).toHaveCount(2)

  await upcoming.getByRole('link', { name: '就诊准备' }).click()
  await expect(page.getByRole('heading', { name: '就诊准备', exact: true })).toBeVisible()
  const back = page.getByRole('link', { name: '返回我的预约' })
  await expect(back).toHaveAttribute('href', '/appointments')
  await back.click()
  await expect(page).toHaveURL('/appointments')
  await expect(upcoming).toBeVisible()
})

test('超过二十条预约时仍突出下一次就诊，历史可以翻页并从详情返回原页', async ({ page, patientApi }) => {
  const next = patientApi.seedAppointment()
  patientApi.appointments.push(...Array.from({ length: 23 }, (_, index) => ({
    ...next, appointmentNo: 'HISTORY-' + (index + 1), status: 'CANCELLED',
    appointmentDate: '2026-10-' + String(index + 1).padStart(2, '0'),
  })))
  await page.goto('/appointments')
  await expect(page.getByRole('region', { name: '下一次就诊' }).getByRole('link', { name: '查看详情' })).toHaveAttribute('href', detailUrl(portalNumber))
  const history = page.getByRole('region', { name: '历史记录' })
  await expect(history.getByRole('link')).toHaveCount(20)
  await history.getByRole('button', { name: '下一页' }).click()
  await expect(history.getByRole('link')).toHaveCount(3)
  await expect(history.getByRole('button', { name: '第 2 页' })).toHaveAttribute('aria-current', 'page')
  await history.getByRole('link').last().click()
  await expect(page.getByText('HISTORY-1', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '返回预约列表' }).click()
  await expect(history.getByRole('button', { name: '第 2 页' })).toHaveAttribute('aria-current', 'page')
  await page.getByRole('region', { name: '下一次就诊' }).getByRole('link', { name: '就诊准备' }).click()
  await page.reload()
  const back = page.getByRole('link', { name: '返回我的预约' })
  await expect(back).toHaveAttribute('href', '/appointments?historyPage=2')
  await back.click()
  await expect(history.getByRole('button', { name: '第 2 页' })).toHaveAttribute('aria-current', 'page')
})

test('患者核对后取消，原记录移入历史；已签到预约无取消入口', async ({ page, patientApi }) => {
  const original = patientApi.seedAppointment()
  Object.assign(original, { canCancel: true, cancellationDeadline: '2026-11-19T15:00:00Z' })
  await page.goto(detailUrl(portalNumber))
  await expect(page.getByText(/线上取消截止至/)).toBeVisible()
  await page.getByRole('button', { name: '取消预约', exact: true }).click()
  await expect(page.getByRole('group', { name: '核对取消预约' })).toContainText('呼吸内科')
  await page.getByRole('button', { name: '确认取消预约' }).click()
  await expect(page.getByText('已取消', { exact: true })).toBeVisible()
  await expect(page.getByText('取消时间', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '返回预约列表' }).click()
  await expect(page.getByRole('region', { name: '历史记录' }).getByRole('link')).toHaveCount(1)

  Object.assign(original, { status: 'CHECKED_IN', checkedInAt: '2026-11-20T05:40:00Z', cancelledAt: null })
  await page.goto(detailUrl(portalNumber))
  await expect(page.getByText('签到时间', { exact: true })).toBeVisible()
  await expect(page.getByText(/请在诊区候诊/)).toBeVisible()
  await expect(page.getByRole('button', { name: '取消预约', exact: true })).toHaveCount(0)
})

test('改约抢号失败后可重新选号，响应丢失后用原请求核验', async ({ page, patientApi }) => {
  const original = patientApi.seedAppointment()
  Object.assign(original, { canCancel: true, cancellationDeadline: '2026-11-19T15:00:00Z' })
  const replacement = 'E2E-REPLACEMENT'
  const requests: Array<{ slotId: number; idempotencyKey: string }> = []
  await page.route('**/api/v1/appointments/' + portalNumber + '/reschedule', async route => {
    const request = route.request().postDataJSON()
    requests.push(request)
    if (requests.length === 1) return route.fulfill({ json: {
      outcome: 'REJECTED', success: false, code: 'SLOT_UNAVAILABLE', message: '该时段已被预约，本次改约未变更原预约。',
    } })
    patientApi.appointments.push({
      ...original, appointmentNo: replacement, originalAppointmentNo: portalNumber, timeRange: '15:00–15:30',
    })
    Object.assign(original, { status: 'RESCHEDULED', canCancel: false, replacementAppointmentNo: replacement })
    return route.abort('failed')
  })
  await page.route('**/api/v1/appointments/' + portalNumber + '/reschedule/status', route => {
    expect(route.request().postDataJSON()).toEqual(requests.at(-1))
    return route.fulfill({ json: { outcome: 'EXISTING', appointmentNo: replacement, success: true } })
  })
  await page.goto(detailUrl(portalNumber))
  await page.getByRole('link', { name: '改约', exact: true }).click()
  await expect(page.getByRole('heading', { name: '选择新的就诊时间' })).toBeVisible()
  await expect(page.getByRole('button', { name: '不限医生' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '更换科室' })).toHaveCount(0)
  const slots = page.locator('.booking-slot')
  await slots.filter({ hasText: '09:00–09:30' }).getByRole('button', { name: '选此号源' }).click()
  await expect(page.getByText('原时段', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '核对改约信息' })).toBeVisible()
  await page.getByRole('button', { name: '确认改约', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('本次改约未变更原预约')
  expect(original.status).toBe('BOOKED')
  await slots.filter({ hasText: '15:00–15:30' }).getByRole('button', { name: '选此号源' }).click()
  await page.getByRole('button', { name: '确认改约', exact: true }).click()
  await expect(page.getByText('提交结果暂未确定，请先核验这次改约。')).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '核验这次改约' }).click()
  await expect(page.getByText('改约成功', { exact: true })).toBeVisible()
  expect(requests).toHaveLength(2)
  await page.getByRole('link', { name: '查看新预约' }).click()
  await expect(page).toHaveURL(detailUrl(replacement))
  await page.getByRole('link', { name: '查看改约前的预约' }).click()
  await expect(page.getByText('已改约', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '查看新预约' })).toHaveAttribute('href', detailUrl(replacement))
})

test.describe('320 像素触屏', () => {
  test.use({ viewport: { width: 320, height: 780 }, isMobile: true, hasTouch: true })

  test('智能导诊换号、确认、查看预约，返回后可恢复会话', async ({ page, patientApi }) => {
    await page.goto('/')
    await page.getByRole('textbox', { name: '描述就诊需求' }).fill(guideRequest)
    await page.getByRole('button', { name: '开始智能导诊' }).click()
    await expect(page.getByRole('heading', { name: '选择就诊时间' })).toBeVisible()
    const choices = page.getByRole('button', { name: '选择此号源' })
    await choices.first().click()
    await expect(page.getByRole('heading', { name: '预约确认单' })).toBeVisible()
    await page.getByRole('button', { name: '返回换时间' }).click()
    await choices.last().click()
    await page.getByRole('button', { name: '确认预约', exact: true }).tap()
    await expect(page.getByText('预约已创建并核验', { exact: true })).toBeVisible()
    expect(patientApi.appointments.map(record => record.slotId)).toEqual([6102])
    await expectTouchArea(page)

    await openPreparationFromSuccess(page, guideNumber, '智能导诊')
    await askAndExpandSource(page)
    patientApi.failNext('GET /tasks/' + guideTaskId)
    await returnToPreviousPage(page, guideNumber, '/guide')
    await expect(page.getByRole('alert').filter({ hasText: '暂时无法恢复预约任务' })).toBeVisible()
    await page.getByRole('button', { name: '重新读取任务' }).tap()
    await expect(page.getByText('预约已创建并核验', { exact: true })).toBeVisible()
    expect(patientApi.requestsFor('POST /tasks/' + guideTaskId + '/confirm')).toHaveLength(1)
  })

  test('普通挂号的查看原预约也有相同的触屏点击区域', async ({ page, patientApi }) => {
    await page.goto('/booking?departmentId=2002')
    await page.getByRole('button', { name: '选此号源' }).first().tap()
    await page.getByRole('button', { name: '确认预约', exact: true }).tap()
    await expect(page.getByText('预约已创建', { exact: true })).toBeVisible()
    await expectTouchArea(page)
    await page.getByRole('link', { name: '查看原预约' }).tap()
    await expect(page.getByText(portalNumber, { exact: true })).toBeVisible()
    expect(patientApi.requestsFor('POST /appointments')).toHaveLength(1)
  })
})

test('号源分页能继续查更晚的日期，换筛选后回到第一页', async ({ page, patientApi }) => {
  patientApi.seedAdditionalSlots(19)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/')
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '预约挂号' }).click()
  await expect(page.locator('.patient-page-content')).toHaveCSS('animation-name', 'none')
  await page.getByRole('button', { name: /呼吸内科/ }).click()
  await expect(page.getByText('查询范围内共 22 个号源')).toBeVisible()
  const pager = page.getByRole('navigation', { name: '号源分页' })
  await expect(pager.getByRole('button', { name: '上一页' })).toBeDisabled()
  const bar = await pager.boundingBox()
  expect(bar!.y + bar!.height).toBeLessThanOrEqual(page.viewportSize()!.height)
  await pager.getByRole('button', { name: '下一页' }).click()
  await expect(pager.getByRole('button', { name: '第 2 页' })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('button', { name: '选此号源' })).toHaveCount(10)
  await expect(page.getByText('之后的排班和号源未纳入本次查询。', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '上午', exact: true }).click()
  await expect(page.getByRole('button', { name: '选此号源' })).toHaveCount(1)
  expect(patientApi.requestsFor('GET /slots').at(-1)?.query).toMatchObject({ page: '1', period: 'MORNING' })
  await page.getByRole('button', { name: '更换科室' }).click()
  await expect(page.getByRole('heading', { name: '选择就诊科室' })).toBeFocused()
})

test('新建导诊后仍能从账号历史找回原对话，清空标签页状态也能回看', async ({ page, patientApi }) => {
  await page.goto('/')
  await page.getByRole('textbox', { name: '描述就诊需求' }).fill(guideRequest)
  await page.getByRole('button', { name: '开始智能导诊' }).click()
  const sidebar = page.getByRole('complementary', { name: '会话', exact: true })
  await expect(sidebar.getByRole('button', { name: new RegExp(guideRequest) })).toBeVisible()
  await sidebar.getByRole('button', { name: '新建会话' }).click()
  await expect(sidebar.getByText('尚未开始会话')).toBeVisible()
  await page.evaluate(() => sessionStorage.clear())
  await page.reload()
  await sidebar.getByRole('button', { name: new RegExp(guideRequest) }).click()
  await expect(page.getByText('已经按呼吸内科、明天下午查到两个可预约时段。', { exact: true })).toBeVisible()
  expect(patientApi.requestsFor('GET /tasks/' + guideTaskId)).toHaveLength(1)
})

async function openPreparationFromSuccess(page: Page, number: string, method: string) {
  await page.getByRole('link', { name: '查看原预约' }).click()
  await expect(page).toHaveURL(detailUrl(number))
  await expect(page.getByText(number, { exact: true })).toBeVisible()
  await expect(page.getByText(method, { exact: true }).last()).toBeVisible()
  await expect(page.getByText(/由门诊分诊台办理签到/)).toBeVisible()
  await page.getByRole('link', { name: '就诊准备', exact: true }).click()
  await expect(page).toHaveURL(preparationUrl(number) + '?from=detail')
  await expect(page.getByRole('complementary', { name: '本次预约' })).toContainText(number)
}

async function askAndExpandSource(page: Page) {
  await page.getByRole('button', { name: visitQuestion }).click()
  await expect(page.getByText(visitAnswer, { exact: true })).toBeVisible()
  const region = page.getByRole('region', { name: '资料来源' })
  await expect(region.getByText(visitSource.content)).toBeHidden()
  await region.getByText(/演示门诊须知.*报到凭证/).press('Enter')
  await expect(region.getByText(visitSource.content)).toBeVisible()
}

async function returnToPreviousPage(page: Page, number: string, previousUrl: string) {
  await page.getByRole('link', { name: '返回预约详情' }).click()
  await expect(page).toHaveURL(detailUrl(number))
  await expect(page.getByText(number, { exact: true })).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(previousUrl)
}

async function expectTouchArea(page: Page) {
  const link = page.getByRole('link', { name: '查看原预约' })
  await expect(link).toBeVisible()
  const metrics = await link.evaluate(element => {
    const rect = element.getBoundingClientRect()
    return {
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      cornerClickable: element.contains(document.elementFromPoint(rect.right - 3, rect.top + 4)),
      overflow: document.documentElement.scrollWidth > innerWidth + 1,
    }
  })
  expect(metrics).toEqual({ width: 136, height: 44, cornerClickable: true, overflow: false })
}
