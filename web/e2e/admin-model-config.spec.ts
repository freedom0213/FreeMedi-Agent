import { expect, test } from '@playwright/test'
import type { AgentModelConfiguration, ModelRevision } from '../src/api/admin'

test('按后端值展示模型配置，并在演示模式显示实际决策实现', async ({ page }) => {
  const configuration: AgentModelConfiguration = {
    mode: 'DEEPSEEK',
    provider: { modelName: 'browser-fixture-model', sdkMaxRetries: 0, maxOutputCorrections: 1 },
    callPolicy: {
      modelTimeoutMs: 12400, toolTimeoutMs: 3600, requestTimeoutMs: 27400, maxAttempts: 2,
      retryableReasons: ['MODEL_TIMEOUT', 'TOOL_TRANSIENT', 'KNOWLEDGE_INDEX_UNAVAILABLE'],
    },
  }
  let demo = false
  let fail = true
  const unexpected: string[] = []
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/v1/admin/auth/session') return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (path === '/api/v1/admin/agent/model-config' && route.request().method() === 'GET') {
      if (fail) {
        fail = false
        return route.fulfill({ status: 503 })
      }
      return route.fulfill({ json: demo ? { ...configuration, mode: 'DEMO', provider: null } : configuration })
    }
    unexpected.push(path)
    return route.fulfill({ status: 500 })
  })
  await page.goto('/admin')
  await page.getByRole('link', { name: 'AI 模型配置' }).click()
  await expect(page.getByRole('alert').getByText('模型配置加载失败。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重新加载' }).click()
  const model = page.getByRole('region', { name: '当前导诊决策模型' })
  await expect(model.getByText('browser-fixture-model')).toBeVisible()
  const timeouts = page.getByRole('region', { name: '超时限制' })
  for (const value of ['12.4 秒', '3.6 秒', '27.4 秒']) await expect(timeouts.getByText(value, { exact: true })).toBeVisible()
  const retries = page.getByRole('region', { name: '重试规则' })
  await expect(retries.getByText('最多 1 次重试（含首次共 2 次尝试）')).toBeVisible()
  await expect(retries.getByText('0 次', { exact: true })).toBeVisible()
  await expect(retries.getByText('院内资料索引暂时不可用')).toBeVisible()
  await expect(page.getByText(/API Key/i)).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  demo = true
  await page.reload()
  await expect(model.getByText('本地演示规则')).toBeVisible()
  await expect(model.getByText('模型名称', { exact: true })).toHaveCount(0)
  await expect(model.getByText('browser-fixture-model')).toHaveCount(0)
  await expect(retries.getByText('供应商 SDK 自动重试')).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('在窄屏保存第二套配置、测试后手动启用，并可切回启动配置', async ({ page }) => {
  const configuration: AgentModelConfiguration = {
    mode: 'DEEPSEEK', secretStorageReady: true, revision: 0, active: null,
    provider: { modelName: 'initial-model', sdkMaxRetries: 0, maxOutputCorrections: 1 },
    callPolicy: { modelTimeoutMs: 15000, toolTimeoutMs: 5000, requestTimeoutMs: 45000, maxAttempts: 2, retryableReasons: [] },
    models: [],
  }
  const alternate: ModelRevision = {
    id: 1, profileId: 'fixture', revision: 1, label: '备用配置', baseUrl: 'https://models.example.test/v1',
    modelName: 'alternate-model', probeCode: null, probeMs: null, testedAt: null,
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/**', async route => {
    const method = route.request().method()
    const path = new URL(route.request().url()).pathname
    const base = '/api/v1/admin/agent/model-config'
    if (path.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (path.endsWith('/auth/csrf')) return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (path === base && method === 'GET') return route.fulfill({ json: configuration })
    expect(route.request().headers()['x-csrf-token']).toBe('fixture')
    if (path === base + '/models' && method === 'POST') {
      expect(route.request().postDataJSON()).toMatchObject({ label: alternate.label, modelName: alternate.modelName, apiKey: 'fixture-key' })
      configuration.models = [alternate]
    } else if (path === base + '/models/1/test') {
      alternate.probeCode = 'PASSED'
      alternate.testedAt = '2026-11-23T05:00:00Z'
      alternate.probeMs = 200
    } else if (path === base + '/activate') {
      const change = route.request().postDataJSON()
      expect(change.expectedRevision).toBe(configuration.revision)
      configuration.active = change.id === 1 ? alternate : null
      configuration.mode = configuration.active ? 'COMPATIBLE' : 'DEEPSEEK'
      configuration.provider!.modelName = configuration.active?.modelName ?? 'initial-model'
      configuration.revision = change.expectedRevision + 1
    } else {
      throw new Error('Unexpected path: ' + path)
    }
    return route.fulfill({ json: configuration })
  })
  await page.goto('/admin/model-config')
  const current = page.getByRole('region', { name: '当前导诊决策模型' })
  await page.getByRole('button', { name: '新增配置' }).click()
  await page.getByLabel('配置名称').fill(alternate.label)
  await page.getByLabel('模型名称').fill(alternate.modelName)
  await page.getByLabel('接口地址').fill(alternate.baseUrl)
  await page.getByLabel('API Key').fill('fixture-key')
  await page.getByRole('button', { name: '保存版本' }).click()
  const enable = page.getByRole('button', { name: '启用', exact: true })
  await expect(enable).toBeDisabled()
  await expect(current.getByText('initial-model')).toBeVisible()
  await page.getByRole('button', { name: '测试', exact: true }).click()
  await expect(enable).toBeEnabled()
  await expect(current.getByText('initial-model')).toBeVisible()
  await enable.click()
  await page.getByRole('button', { name: '确认切换' }).click()
  await expect(current.getByText('alternate-model')).toBeVisible()
  await expect(page.getByLabel('API Key')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.getByRole('button', { name: '恢复启动配置' }).click()
  await page.getByRole('button', { name: '确认切换' }).click()
  await expect(current.getByText('initial-model')).toBeVisible()
})
