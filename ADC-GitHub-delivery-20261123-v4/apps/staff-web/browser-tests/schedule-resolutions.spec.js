import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

test.use({ baseURL: 'https://adc-uc22.invalid', channel: 'msedge', headless: true })

test('activation survives reload and queries the original outcome without reposting', async ({ page }) => {
  let posts = 0
  const reference = 'UC/v1/UC22/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/ACTIVATE/00000000-0000-0000-0000-000000000001'
  const receipt = { ucCode: 'UC22', operationKind: 'ACTIVATE', requestReference: reference, auditPersistence: 'CONFIRMED' }
  await page.route('**/*', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/staff/schedule-resolutions') {
      const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc22-csrf" content="synthetic"></head>') })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(css|js)$/.test(path)) {
      return route.fulfill({ contentType: path.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(new URL('../dist' + path, import.meta.url)) })
    }
    if (path.endsWith('/versions/2')) return route.fulfill({ json: { activationStatus: 'DRAFT', stateToken: 'draft' } })
    if (path.endsWith('/impact-checks')) return route.fulfill({ json: { checkToken: 'impact' } })
    if (path.endsWith('/activations')) {
      posts++
      expect(route.request().postDataJSON()).toEqual({ expectedDraftToken: 'draft', previousImpactCheckToken: 'impact' })
      return route.fulfill({ status: 202, json: { ...receipt, status: 'UNKNOWN', businessOutcome: 'UNKNOWN' } })
    }
    if (path.endsWith('/schedule-commands/' + reference)) {
      expect(route.request().method()).toBe('GET')
      return route.fulfill({ json: { ...receipt, status: 'COMPLETED', businessOutcome: 'SUCCEEDED',
        links: { scheduleRef: { scheduleId: '7', versionNo: 2 } } } })
    }
    return route.abort()
  })
  await page.goto('/staff/schedule-resolutions')
  await page.getByLabel('生效安排 ID').fill('7')
  await page.getByLabel('生效版本').fill('2')
  await page.getByRole('button', { name: '核对待生效版本' }).click()
  await page.getByRole('button', { name: '确认生效' }).click()
  await expect(page.getByRole('button', { name: '查询原提交结果' })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: '查询原提交结果' }).click()
  await expect(page.getByText('排班版本 2 已确认生效。')).toBeVisible()
  expect(posts).toBe(1)
})

test('employee submits UC03 proof, then a stale UC04 correction requires a fresh read', async ({ page }) => {
  const requests = []
  await page.route('**/*', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/staff/schedule-resolutions') {
      const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc22-csrf" content="synthetic"></head>') })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(css|js)$/.test(path)) {
      return route.fulfill({ contentType: path.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(new URL('../dist' + path, import.meta.url)) })
    }
    if (path.endsWith('/impact-checks')) {
      expect(route.request().method()).toBe('POST')
      expect(route.request().headers()['x-csrf-token']).toBe('synthetic')
      return route.fulfill({ json: { items: [
      { appointmentId: '8', observedStateToken: 'synthetic-token', handlingStatus: 'REQUIRES_HANDLING' },
      ] } })
    }
    if (path.endsWith('/resolution-histories')) return route.fulfill({ json: requests.length ? [
      { rootResolutionId: '41', originalRecord: { appointment_id: '8', resolution_subject: '初始记录' },
        revisions: [], chainIntegrity: 'VALID', currentHeadReference: '41', expectedResolutionToken: 'head-token',
        currentAssessment: 'STALE', formalOperationFacts: [{ ucCode: 'UC03', auditSuccessEventId: '20',
          appointmentChangeId: '30' }] },
    ] : [] })
    if (path.endsWith('/resolutions')) {
      requests.push(route.request().postDataJSON())
      return requests.length === 1
        ? route.fulfill({ json: { ucCode: 'UC22', operationKind: 'RESOLUTION_SAVE', status: 'COMPLETED',
          businessOutcome: 'SUCCEEDED', auditPersistence: 'CONFIRMED', requestReference: 'synthetic-ref',
          links: { resolutionId: '41' } } })
        : route.fulfill({ status: 409, json: { code: 'RESOLUTION_HEAD_STALE' } })
    }
    return route.abort()
  })
  await page.goto('/staff/schedule-resolutions')
  await page.getByLabel('安排 ID', { exact: true }).fill('7')
  await page.getByLabel('版本', { exact: true }).fill('2')
  await page.getByRole('button', { name: '读取影响和历史' }).click()
  await page.getByRole('combobox', { name: '预约', exact: true }).selectOption('8')
  await page.getByLabel('处理主题').fill('SYNTHETIC change')
  await page.getByLabel('处理依据').fill('SYNTHETIC evidence')
  await page.getByLabel('业务凭证').selectOption('UC03')
  await page.getByLabel('成功审计 ID').fill('20')
  await page.getByLabel('改约历史 ID').fill('30')
  await page.getByRole('button', { name: '保存处理记录' }).click()
  await expect(page.getByRole('status')).toContainText('排班生效须另外确认')
  await expect(page.getByText('当前核验结果 依据已失鲜')).toBeVisible()
  await expect(page.getByText('改约成功 · 审计 20')).toBeVisible()
  expect(requests[0].formalEvidence).toEqual({ ucCode: 'UC03', auditSuccessEventId: '20', appointmentChangeId: '30' })
  await page.getByRole('combobox', { name: '预约', exact: true }).selectOption('8')
  await page.getByLabel('更正记录').selectOption('41')
  await expect(page.getByLabel('业务凭证')).toHaveValue('')
  await page.getByLabel('更正原因').fill('SYNTHETIC correction')
  await page.getByLabel('业务凭证').selectOption('UC04')
  await page.getByLabel('成功审计 ID').fill('21')
  await page.getByLabel('原取消请求引用').fill('synthetic-cancellation')
  await page.getByRole('button', { name: '保存处理记录' }).click()
  await expect(page.getByRole('status')).toContainText('请重新读取')
  expect(requests[1].formalEvidence).toEqual({ ucCode: 'UC04', auditSuccessEventId: '21',
    cancellationBasisReference: 'synthetic-cancellation' })
  expect(requests[1].expectedResolutionToken).toBe('head-token')
})
