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
  const back = page.getByRole('link', { name: '返回原预约' })
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
  await page.getByRole('link', { name: '返回原预约' }).click()
  await expect(page).toHaveURL(detailUrl(portalNumber))
  await page.goBack()
  await expect(page).toHaveURL('/appointments')
  await expect(upcoming).toBeVisible()
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

async function openPreparationFromSuccess(page: Page, number: string, method: string) {
  await page.getByRole('link', { name: '查看原预约' }).click()
  await expect(page).toHaveURL(detailUrl(number))
  await expect(page.getByText(number, { exact: true })).toBeVisible()
  await expect(page.getByText(method, { exact: true }).last()).toBeVisible()
  await page.getByRole('link', { name: '就诊准备', exact: true }).click()
  await expect(page).toHaveURL(preparationUrl(number))
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
  await page.getByRole('link', { name: '返回原预约' }).click()
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
