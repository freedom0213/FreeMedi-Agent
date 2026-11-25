import { expect, test } from '@playwright/test'
import type { DocumentDetail, KnowledgeFragment, KnowledgeIndexSummary } from '../src/api/admin'

test('知识库可按类型钻取，区分缺失向量、恢复索引并从试搜定位片段', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const index: KnowledgeIndexSummary = { model: 'local-embedder', dimensions: 512, provider: 'LOCAL', store: 'QDRANT',
    collection: 'fixtures', pointCount: 1, status: 'REACHABLE' }
  const row: KnowledgeFragment = { id: 22, chunkNo: 2, heading: '到院证件', content: '请带预约凭证。', charCount: 7,
    embeddingStatus: 'INDEXED', failureReason: null, indexedAt: '2026-11-23T01:00:00Z', docNo: 'VISIT-01',
    title: '门诊指南', version: 2, docType: 'VISIT_NOTICE', source: '门诊服务台', documentStatus: 'PUBLISHED',
    vectorState: 'MISSING', vectorModel: null, vectorDimensions: null }
  const document: DocumentDetail = {
    document: { docNo: row.docNo, title: row.title, version: 2, docType: row.docType, source: row.source,
      status: 'PUBLISHED', publishedAt: row.indexedAt, indexed: 2, pending: 0, failed: 0 },
    versions: [{ version: 2, status: 'PUBLISHED', publishedAt: row.indexedAt }],
    chunks: [{ chunkNo: 1, heading: '首段', content: '第一段正文。', charCount: 6,
      embeddingStatus: 'INDEXED', retrievable: true, failureReason: null },
    { chunkNo: 2, heading: row.heading, content: row.content!, charCount: row.charCount,
      embeddingStatus: 'INDEXED', retrievable: true, failureReason: null }],
  }
  let outage = false
  let retried = 0
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const base = '/api/v1/admin/knowledge'
    const { pathname } = url
    if (pathname.endsWith('/auth/session')) return route.fulfill({ json: { adminId: '200', displayName: '资料管理员' } })
    if (pathname.endsWith('/auth/csrf')) return route.fulfill({ json: { adminId: '200', headerName: 'X-CSRF-TOKEN', token: 'fixture' } })
    if (pathname === base + '/library') return route.fulfill({ json: [{ docType: row.docType, documents: 1, versions: 2,
      published: 1, drafts: 0, archived: 1, indexed: 2, pending: 0, failed: 0 }] })
    if (pathname === base + '/index/summary') return route.fulfill({ json: index })
    if (pathname === base + '/chunks') {
      const vectors = url.searchParams.get('vectors') === 'true'
      expect(url.searchParams.get('docType')).toBe(row.docType)
      return route.fulfill({ json: { items: [vectors ? { ...row, content: null } : { ...row, vectorState: null }],
        page: 1, hasNext: false } })
    }
    if (pathname === base + '/documents/' + row.docNo) return route.fulfill({ json: document })
    expect(request.headers()['x-csrf-token']).toBe('fixture')
    if (pathname === base + '/chunks/22/retry-index') {
      retried++
      Object.assign(row, { vectorState: 'READY', vectorModel: index.model, vectorDimensions: index.dimensions })
      return route.fulfill({ json: { status: 'INDEXED' } })
    }
    if (pathname === base + '/index/probe') {
      expect(request.postDataJSON()).toMatchObject({ query: '需要带什么？', topK: 5 })
      return route.fulfill({ json: outage ? { indexAvailable: false, unavailableReason: '向量索引当前不可用', hits: [] }
        : { indexAvailable: true, unavailableReason: null, hits: [{ ...row, score: 0.83 }] } })
    }
    throw new Error('Unexpected request: ' + pathname)
  })
  await page.goto('/admin/knowledge-library')
  const category = page.getByRole('row', { name: /就诊须知.*1 份/ })
  await expect(category).toBeVisible()
  await category.getByRole('link', { name: '片段', exact: true }).click()
  await expect(page.locator('.admin-page-content')).toHaveCSS('animation-name', 'route-in')
  await expect(page.getByRole('combobox', { name: '资料类型', exact: true })).toHaveValue('VISIT_NOTICE')
  await page.setViewportSize({ width: 1280, height: 900 })
  const body = page.getByRole('button', { name: '查看正文' })
  await body.hover()
  await expect(page.getByRole('tooltip')).toHaveText(row.content!)
  const fragmentRow = page.locator('.knowledge-fragment-table tbody tr').first()
  const geometry = () => fragmentRow.evaluate(element =>
    Array.from(element.children, child => {
      const { width, height, x, y } = child.getBoundingClientRect()
      return { width, height, x, y }
    }))
  const before = await geometry()
  await body.click()
  await expect(page.getByRole('dialog', { name: '到院证件' })).toContainText(row.content!)
  expect(await geometry()).toEqual(before)
  await page.keyboard.press('Escape')
  await expect(body).toBeFocused()
  await expect(page.getByRole('dialog')).toBeHidden()
  await page.setViewportSize({ width: 390, height: 844 })

  await page.goto('/admin/knowledge-index?type=VISIT_NOTICE')
  await expect(page.getByText('向量缺失', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重试索引' }).click()
  await expect(page.getByText('该片段的向量索引已建立。')).toBeVisible()
  await expect(page.getByText('已核验', { exact: true })).toBeVisible()
  expect(retried).toBe(1)
  await page.getByLabel('检索问题').fill('需要带什么？')
  outage = true
  await page.getByRole('button', { name: '试搜' }).click()
  await expect(page.getByRole('alert')).toHaveText(/向量索引当前不可用/)
  outage = false
  await page.getByRole('button', { name: '试搜' }).click()
  await page.getByText('相似度 0.83').click()
  await expect(page.getByRole('link', { name: '查看来源版本' })).toHaveAttribute('href', '/admin/knowledge/VISIT-01?version=2&chunk=2')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
  await page.getByRole('link', { name: '查看来源版本' }).click()
  await expect(page.getByRole('article', { name: '所选片段' }).getByText('请带预约凭证。')).toBeVisible()
})
