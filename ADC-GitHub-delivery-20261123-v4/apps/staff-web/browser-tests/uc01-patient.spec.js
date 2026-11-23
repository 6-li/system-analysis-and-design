import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

test.use({ baseURL: 'https://adc-uc01.invalid', channel: 'msedge', headless: true })

test('staff requires linked evidence from both journals before confirming source registration', async ({ page }) => {
  let writes = 0
  await page.route('**/*', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/staff/patients') {
      const html = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc01-csrf" content="synthetic"></head>') })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(js|css)$/.test(path)) {
      return route.fulfill({ contentType: path.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(new URL('../dist' + path, import.meta.url)) })
    }
    if (path === '/api/v1/staff/patients') {
      writes++
      expect(route.request().postDataJSON().source).toEqual({
        kind: 'MINI_REQUEST', requestId: 'SYNTHETIC-request', expectedRequestVersion: 0, identityMatchBasis: 'SYNTHETIC',
      })
      const result = { sourceKind: 'MINI_REQUEST', patient: { patientId: '91001' },
        sourceAssociation: { requestId: 'SYNTHETIC-request', patientId: '91001', patientAccessGrantedByAssociation: false },
        originalUc01SuccessAudit: { ucCode: 'UC01', role: 'COMMITTED_RESULT', eventId: '21' } }
      if (writes === 1) result.extensionResultEvent = { source: 'MINI_EXTENSION', role: 'COMMITTED_RESULT', eventId: '22' }
      return route.fulfill({ status: 201, json: result })
    }
    return route.abort()
  })
  async function submit() {
    for (const [label, value] of [['申请编号', 'SYNTHETIC-request'], ['申请版本', '0'], ['身份核验依据', 'SYNTHETIC'],
      ['患者姓名', 'SYNTHETIC name'], ['患者识别信息', 'SYNTHETIC identity'], ['联系方式', 'SYNTHETIC contact']]) {
      await page.getByLabel(label).fill(value)
    }
    await page.getByRole('button', { name: '核验并建档' }).click()
  }
  await page.goto('/staff/patients')
  await submit()
  await expect(page.getByRole('status')).toContainText('已建档并关联申请')
  await submit()
  await expect(page.getByRole('alert')).toContainText('待核对请求')
  await expect(page.getByRole('button', { name: '核验并建档' })).toBeDisabled()
  expect(writes).toBe(2)
})
