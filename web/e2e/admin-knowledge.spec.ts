import { expect, test, type Page } from '@playwright/test'
import type { DocumentDetail, DocumentVersion, KnowledgeImportPreview } from '../src/api/admin'

const currentVersion: DocumentVersion = {
  docNo: 'RULE-001', title: '预约挂号规则', docType: 'APPOINTMENT_RULE', version: 2,
  status: 'PUBLISHED', source: '演示医院门诊服务台', publishedAt: '2026-11-18T00:00:00Z',
  indexed: 1, pending: 0, failed: 1,
}
const previous: DocumentVersion = { ...currentVersion, version: 1, status: 'ARCHIVED', failed: 0 }
const versions = [currentVersion, previous]
const ready = {
  chunkNo: 1, heading: '取消与改约', content: '可在就诊开始前取消预约。', charCount: 12,
  embeddingStatus: 'INDEXED', retrievable: true, failureReason: null,
} satisfies DocumentDetail['chunks'][number]
const failed = {
  chunkNo: 2, heading: '预约凭证', content: '报到时请出示预约凭证。', charCount: 11,
  embeddingStatus: 'FAILED', retrievable: false, failureReason: '向量生成或写入失败，请查看索引任务日志。',
} satisfies DocumentDetail['chunks'][number]
const importPreview = {
  docNo: 'RULE-001',
  title: '预约挂号规则',
  docType: 'APPOINTMENT_RULE',
  source: '门诊服务台',
  departmentId: null,
  doctorId: null,
  disposition: 'NEW_VERSION',
  proposedVersion: 3,
  published: { version: 2, status: 'PUBLISHED' },
  duplicate: null,
  contentHash: 'mock-preview-hash',
  latestVersion: 2,
  chunks: [
    { chunkNo: 1, heading: '就诊时间', content: '请按预约时间到院。', charCount: 9 },
    { chunkNo: 2, heading: '预约凭证', content: '报到时请出示预约凭证。', charCount: 11 },
  ],
} satisfies KnowledgeImportPreview

async function mockAdminApi(page: Page, initialRole: 'patient' | 'admin' | 'anonymous', options: {
  failList?: boolean
  archive?: 'success' | 'conflict' | 'lost-response'
  preview?: boolean
  publish?: boolean
} = {}) {
  let role = initialRole
  let failList = options.failList ?? false
  let versionTwo = currentVersion
  let availableVersions = versions
  let draftFailedOnce = false
  const unexpected: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    const path = url.pathname
    if (path === '/api/v1/admin/auth/session' && method === 'GET') {
      if (role !== 'admin') return route.fulfill({ status: role === 'patient' ? 403 : 401 })
      return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    }
    if (path === '/api/v1/admin/auth/csrf' && method === 'GET') {
      return route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'mock-token', adminId: role === 'admin' ? '200' : null } })
    }
    if (path === '/api/v1/admin/auth/login' && method === 'POST') {
      const form = new URLSearchParams(route.request().postData() || '')
      if (route.request().headers()['x-csrf-token'] !== 'mock-token') return route.fulfill({ status: 403 })
      if (form.get('username') !== 'test-admin' || form.get('password') !== 'test-password') return route.fulfill({ status: 401 })
      role = 'admin'
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/admin/auth/logout' && method === 'POST' && role === 'admin') {
      role = 'anonymous'
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/admin/knowledge/import-preview' && method === 'POST' && role === 'admin' && options.preview) {
      if (route.request().headers()['x-csrf-token'] !== 'mock-token') return route.fulfill({ status: 403 })
      const content = route.request().postData() ?? ''
      if (!content.includes('source:')) {
        return route.fulfill({ status: 400, json: { error: 'Markdown 文件 缺少必填元信息：source' } })
      }
      if (content.includes('重复内容样例')) {
        return route.fulfill({ json: {
          ...importPreview, proposedVersion: null, disposition: 'DUPLICATE', duplicate: { version: 1, status: 'ARCHIVED' },
        } })
      }
      return route.fulfill({ json: importPreview })
    }
    if (path === '/api/v1/admin/knowledge/import-drafts' && method === 'POST' && role === 'admin' && options.publish) {
      const headers = route.request().headers()
      if (headers['x-csrf-token'] !== 'mock-token' || headers['x-preview-hash'] !== 'mock-preview-hash'
        || headers['x-expected-latest-version'] !== '2' || headers['x-expected-published-version'] !== '2') {
        return route.fulfill({ status: 400 })
      }
      availableVersions = [{ ...currentVersion, version: 3, status: 'DRAFT', indexed: 0, failed: 0, pending: 2 }, ...versions]
      return route.fulfill({ json: { docNo: 'RULE-001', version: 3 } })
    }
    if (path === '/api/v1/admin/knowledge/documents/RULE-001/versions/3/index' && method === 'POST' && options.publish) {
      const draft = availableVersions[0]!
      if (!draftFailedOnce) {
        draftFailedOnce = true
        availableVersions[0] = { ...draft, failed: 1, pending: 1 }
        return route.fulfill({ json: { finished: false, failed: true } })
      }
      const indexed = draft.indexed + 1
      availableVersions[0] = { ...draft, indexed, pending: 2 - indexed, failed: 0 }
      return route.fulfill({ json: { finished: indexed === 2, failed: false } })
    }
    if (path === '/api/v1/admin/knowledge/documents/RULE-001/versions/3/publish' && method === 'POST' && options.publish) {
      if (route.request().postDataJSON().expectedPublishedVersion !== 2 || availableVersions[0]?.indexed !== 2) {
        return route.fulfill({ status: 409 })
      }
      availableVersions[0] = { ...availableVersions[0], status: 'PUBLISHED' }
      availableVersions[1] = { ...currentVersion, status: 'ARCHIVED' }
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/admin/knowledge/documents' && method === 'GET' && role === 'admin') {
      if (failList) {
        failList = false
        return route.fulfill({ status: 503 })
      }
      return route.fulfill({ json: [{ document: availableVersions[0], versionCount: availableVersions.length }] })
    }
    if (path === '/api/v1/admin/knowledge/documents/RULE-001/archive' && method === 'POST' && role === 'admin' && options.archive) {
      if (route.request().headers()['x-csrf-token'] !== 'mock-token') return route.fulfill({ status: 403 })
      if (route.request().postDataJSON().version !== 2) return route.fulfill({ status: 400 })
      versionTwo = { ...currentVersion, status: 'ARCHIVED' }
      availableVersions = [versionTwo, previous]
      if (options.archive === 'conflict') {
        availableVersions = [{ ...currentVersion, version: 3 }, ...availableVersions]
        return route.fulfill({ status: 409 })
      }
      if (options.archive === 'lost-response') return route.abort()
      return route.fulfill({ status: 204 })
    }
    if (path === '/api/v1/admin/knowledge/documents/RULE-001' && method === 'GET' && role === 'admin') {
      const selected = url.searchParams.has('version')
        ? availableVersions.find(item => String(item.version) === url.searchParams.get('version'))
        : availableVersions[0]
      if (!selected) return route.fulfill({ status: 404 })
      const chunks: DocumentDetail['chunks'] = [{ ...ready, retrievable: selected.status === 'PUBLISHED' }]
      if (selected.version > 1) chunks.push(failed)
      return route.fulfill({ json: { document: selected, versions: availableVersions, chunks } })
    }
    unexpected.push(method + ' ' + path)
    return route.fulfill({ status: 500 })
  })
  return unexpected
}

async function fillLogin(page: Page, username = 'test-admin') {
  await page.getByLabel('管理员账号').fill(username)
  await page.getByLabel('密码').fill('test-password')
  await page.getByRole('button', { name: '登录管理端' }).click()
}

test('管理员从独立登录进入工作台，核对可引用、索引失败和归档片段', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'anonymous')
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/admin\/login/)
  await fillLogin(page)
  await expect(page.getByRole('heading', { name: '欢迎，资料管理员' })).toBeVisible()
  await page.getByRole('link', { name: '查看院内资料' }).click()
  const search = page.getByRole('searchbox', { name: '搜索资料标题或编号' })
  await search.fill('不存在的资料')
  await expect(page.getByText('没有找到符合搜索条件的资料。')).toBeVisible()
  await search.fill('RULE-001')
  await expect(page.getByText('1 段可参与检索')).toBeVisible()
  await page.getByRole('link', { name: '查看版本 预约挂号规则' }).click()
  const chunk = page.getByRole('article', { name: '所选片段' })
  await expect(chunk.getByText('可参与检索', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: /预约凭证/ }).click()
  await expect(chunk.getByText('暂不可引用 · 索引失败')).toBeVisible()
  await expect(chunk.getByText('索引失败原因')).toBeVisible()
  await page.getByRole('combobox', { name: '查看版本' }).selectOption('1')
  await expect(page).toHaveURL(/version=1/)
  await expect(chunk.getByText('不可引用 · 版本已归档')).toBeVisible()
  await expect(page.getByRole('button', { name: /取消与改约.*曾索引/ })).toBeVisible()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '管理员登录' })).toBeVisible()
  expect(unexpected).toEqual([])
})

test('患者会话被拒绝，管理员登录后回到原本的管理端深链接', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'patient')
  await page.goto('/admin/knowledge/RULE-001?version=1')
  await expect(page.getByText(/当前是患者会话/)).toBeVisible()
  await fillLogin(page, 'test-patient')
  await expect(page.getByRole('alert')).toHaveText('登录失败，请核对管理员账号和密码。')
  await fillLogin(page)
  await expect(page).toHaveURL(/\/admin\/knowledge\/RULE-001\?version=1$/)
  await expect(page.getByText('此版本已归档，患者新问答不可引用')).toBeVisible()
  expect(unexpected).toEqual([])
})

test('窄屏可恢复列表加载失败，并从不存在的版本回到默认版本', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const unexpected = await mockAdminApi(page, 'admin', { failList: true })
  await page.goto('/admin/knowledge')
  await expect(page.getByText('资料列表加载失败。')).toBeVisible()
  await page.getByRole('button', { name: '重新加载资料' }).click()
  await expect(page.getByRole('row', { name: /预约挂号规则/ })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)

  await page.goto('/admin/knowledge/RULE-001?version=99')
  await expect(page.getByRole('alert')).toHaveText('找不到这份资料或所选版本。')
  await page.getByRole('link', { name: '查看资料默认版本' }).click()
  await expect(page.getByRole('heading', { name: '预约挂号规则' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  expect(unexpected).toEqual([])
})

test('窄屏确认单列出下架影响，取消保留状态，确认后归档指定版本', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const unexpected = await mockAdminApi(page, 'admin', { archive: 'success' })
  await page.goto('/admin/knowledge/RULE-001')
  const trigger = page.getByRole('button', { name: '下架资料' })
  const dialog = page.getByRole('dialog', { name: '确认下架资料' })
  await trigger.click()
  await expect(dialog.getByText('预约挂号规则 RULE-001')).toBeVisible()
  await expect(dialog.getByText('v2', { exact: true })).toBeVisible()
  await expect(dialog.getByText('1 段已索引片段将退出新问答检索')).toBeVisible()
  await expect(dialog.getByText(/历史回答仍可展开原有来源/)).toBeVisible()
  await expect(dialog.getByRole('button', { name: '取消' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()
  await trigger.click()
  const archive = page.waitForRequest(request => request.url().endsWith('/archive') && request.method() === 'POST')
  await dialog.getByRole('button', { name: '确认下架' }).click()
  expect((await archive).postDataJSON()).toEqual({ version: 2 })
  await expect(page.getByRole('status')).toHaveText('v2 已下架。历史回答仍保留原有来源。')
  await expect(page.getByRole('article', { name: '所选片段' }).getByText('不可引用 · 版本已归档')).toBeVisible()
  await expect(trigger).toHaveCount(0)
  expect(unexpected).toEqual([])
})

test('版本改变时提示重新核对，不把新发布版本一起下架', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'admin', { archive: 'conflict' })
  await page.goto('/admin/knowledge/RULE-001?version=2')
  await page.getByRole('button', { name: '下架资料' }).click()
  await page.getByRole('button', { name: '确认下架' }).click()
  await expect(page.getByRole('alert')).toHaveText('发布状态已变化。请查看当前版本并重新确认。')
  await page.getByRole('button', { name: '查看当前版本' }).click()
  await expect(page.getByRole('combobox', { name: '查看版本' })).toHaveValue('3')
  await expect(page.getByRole('button', { name: '下架资料' })).toBeVisible()
  expect(unexpected).toEqual([])
})

test('下架响应丢失时核验原版本，只读状态就能恢复', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'admin', { archive: 'lost-response' })
  let writes = 0
  page.on('request', request => {
    if (request.url().endsWith('/archive') && request.method() === 'POST') writes++
  })
  await page.goto('/admin/knowledge/RULE-001')
  await page.getByRole('button', { name: '下架资料' }).click()
  await page.getByRole('button', { name: '确认下架' }).click()
  await expect(page.getByRole('alert')).toHaveText('暂时无法确认下架结果，请先核验此版本的状态。')
  await page.getByRole('button', { name: '核验下架结果' }).click()
  await expect(page.getByText('此版本已归档，患者新问答不可引用')).toBeVisible()
  expect(writes).toBe(1)
  expect(unexpected).toEqual([])
})

test('从资料列表选择 Markdown，核对预计版本和展开片段', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'admin', { preview: true })
  await page.goto('/admin/knowledge')
  await page.getByRole('link', { name: '导入预览' }).click()
  const upload = page.getByLabel('选择 Markdown 文件')
  const markdown = '---\ndoc_no: RULE-001\ntitle: 预约挂号规则\ntype: APPOINTMENT_RULE\nsource: 门诊服务台\n---\n正文'
  const inspect = page.getByRole('button', { name: '解析并预览' })
  await expect(inspect).toBeDisabled()
  await upload.setInputFiles({ name: 'new-version.md', mimeType: 'text/markdown', buffer: Buffer.from(markdown) })
  await expect(page.getByRole('heading', { name: '切分预览' })).toHaveCount(0)
  const request = page.waitForRequest(value => value.url().endsWith('/import-preview') && value.method() === 'POST')
  await inspect.click()
  expect((await request).postData()).toBe(markdown)
  await expect(page.getByText('新版本', { exact: true })).toBeVisible()
  await expect(page.getByText('v3', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'v2', exact: true })).toHaveAttribute('href', '/admin/knowledge/RULE-001?version=2')
  const chunk = page.locator('details').filter({ hasText: '预约凭证' })
  await expect(chunk.getByText('报到时请出示预约凭证。')).toBeHidden()
  await chunk.locator('summary').click()
  await expect(chunk.getByText('报到时请出示预约凭证。')).toBeVisible()
  expect(unexpected).toEqual([])
})

test('窄屏预览提示字段错误，换文件后能看到重复内容对应的归档版本', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const unexpected = await mockAdminApi(page, 'admin', { preview: true })
  await page.goto('/admin/knowledge/import/preview')
  const upload = page.getByLabel('选择 Markdown 文件')
  const inspect = page.getByRole('button', { name: '解析并预览' })
  await upload.setInputFiles({ name: 'invalid.md', mimeType: 'text/markdown', buffer: Buffer.from('缺少来源') })
  await inspect.click()
  await expect(page.getByRole('alert')).toHaveText(/缺少必填元信息：source/)
  await upload.setInputFiles({
    name: 'duplicate.md', mimeType: 'text/markdown', buffer: Buffer.from('source: 门诊服务台\n重复内容样例'),
  })
  await expect(page.getByRole('alert')).toHaveCount(0)
  await inspect.click()
  await expect(page.getByText('重复内容', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'v1 · 已归档' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  expect(unexpected).toEqual([])
})

test('草稿索引失败后能重试，确认单核对新旧版本并发布', async ({ page }) => {
  const unexpected = await mockAdminApi(page, 'admin', { preview: true, publish: true })
  await page.goto('/admin/knowledge/import/preview')
  await page.getByLabel('选择 Markdown 文件').setInputFiles({
    name: 'edited.md', mimeType: 'text/markdown', buffer: Buffer.from('source: 门诊服务台'),
  })
  await page.getByRole('button', { name: '解析并预览' }).click()
  await page.getByRole('button', { name: '保存为草稿' }).click()
  await expect(page).toHaveURL(/RULE-001\?version=3/)
  await page.getByRole('button', { name: '建立索引' }).click()
  await expect(page.getByRole('alert')).toHaveText('索引未完成。请检查失败片段后重试。')
  await expect(page.getByRole('button', { name: '发布新版' })).toHaveCount(0)
  await page.getByRole('button', { name: '重试索引' }).click()
  await page.getByRole('button', { name: '发布新版' }).click()
  const dialog = page.getByRole('dialog', { name: '确认发布新版' })
  await expect(dialog.getByText('v3 · 2 段已索引')).toBeVisible()
  await expect(dialog.getByText('v2', { exact: true })).toBeVisible()
  await dialog.getByRole('button', { name: '确认发布' }).click()
  await expect(page.getByText('v3 已发布。患者的新问答可引用新版。')).toBeVisible()
  await expect(page.getByRole('combobox', { name: '查看版本' }).locator('option[value="2"]')).toHaveText('v2 · 已归档')
  expect(unexpected).toEqual([])
})
