import { expect, test } from '@playwright/test'
import type { PromptTemplateView, PromptTrialOutput } from '../src/api/admin'

test('窄屏管理两段指导，试跑后启用版本并恢复只读底稿', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const root = '/api/v1/admin/agent/prompt-templates'
  const time = '2026-11-23T04:00:00Z'
  const view: PromptTemplateView = { activeId: null, activatedAt: null, trialAvailable: true,
    effectiveVersion: 'builtin-v1', runningTrial: null, revision: 0,
    builtIn: { version: 'builtin-v1', triage: '', selection: '' },
    basePrompt: '固定的输出协议及预约确认规则。', versions: [] }
  const outcome: PromptTrialOutput = {
    passed: true, decision: 'Reply', reply: '请在页面确认。', tool: null, arguments: null, failure: null,
  }

  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const request = route.request()
    if (url.pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (url.pathname.endsWith('/auth/csrf')) return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (url.pathname === root) return route.fulfill({ json: view })
    expect(request.headers()['x-csrf-token']).toBe('fixture')
    if (url.pathname === root + '/versions') {
      expect(request.postDataJSON()).toEqual({ triage: '先问一个问题', selection: '先介绍候选', note: '表达调整' })
      view.versions = [{ id: 1, prompt: { version: 'v1', triage: '先问一个问题', selection: '先介绍候选' },
        note: '表达调整', createdBy: '资料管理员', createdAt: time, testedAt: null, trialPassed: null, canActivate: false, report: null }]
      return route.fulfill({ json: view })
    }
    if (url.pathname === root + '/versions/1/trial') {
      Object.assign(view.versions[0], { trialPassed: true, testedAt: time, canActivate: true, report: {
        baselineVersion: 'builtin-v1', modelVersion: 'environment', modelLabel: '启动配置 · fixture', cases: [
          { key: 'CONFIRM', label: '聊天中请求确认预约', input: '帮我确认预约。', expectation: '提示页面确认', baseline: outcome, candidate: outcome },
        ],
      } })
      return route.fulfill({ json: view })
    }
    if (url.pathname === root + '/activate') {
      const input = request.postDataJSON()
      expect(input).toEqual({ id: view.revision === 0 ? 1 : null, expectedRevision: view.revision })
      view.activeId = input.id
      view.effectiveVersion = input.id ? 'v1' : 'builtin-v1'
      view.activatedAt = time
      view.revision++
      return route.fulfill({ json: view })
    }
    throw new Error('Unexpected call: ' + url.pathname)
  })

  await page.goto('/admin/prompt-templates')
  await expect(page.getByRole('heading', { name: 'Prompt 模板' })).toBeVisible()
  await page.getByText('查看只读底稿').click()
  await expect(page.getByText('固定的输出协议及预约确认规则。')).toBeVisible()
  await page.getByText('查看只读底稿').click()
  await page.getByRole('button', { name: '新建版本' }).click()
  await page.getByRole('textbox', { name: '导诊沟通', exact: true }).fill('先问一个问题')
  await page.getByRole('textbox', { name: '选号确认引导', exact: true }).fill('先介绍候选')
  await page.getByRole('textbox', { name: '修改说明' }).fill('表达调整')
  await page.getByRole('button', { name: '保存新版本' }).click()
  await expect(page.getByRole('button', { name: '启用 v1' })).toBeDisabled()
  await page.getByRole('button', { name: '试跑合成用例' }).click()
  await expect(page.getByRole('button', { name: '启用 v1' })).toBeEnabled()
  await page.getByText('聊天中请求确认预约').click()
  await expect(page.getByText('请在页面确认。', { exact: true })).toHaveCount(2)
  await page.getByRole('button', { name: '启用 v1' }).click()
  await page.getByRole('button', { name: '确认启用' }).click()
  await expect(page.getByText('已启用 v1，下一轮请求生效。')).toBeVisible()
  await page.getByRole('button', { name: '恢复内置模板' }).click()
  await page.getByRole('button', { name: '确认恢复' }).click()
  await expect(page.getByText('已恢复内置模板，下一轮请求生效。')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})
