import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const origin = 'https://adc-t024.invalid'
const workspace = '/staff/schedule-workspace'
const assets = new URL('../../../.tools/t024-demo-vue/', import.meta.url)
const transcript = JSON.parse(await readFile(
  new URL('../../../backend/target/t024-demo/service-transcript.json', import.meta.url), 'utf8'))

async function serve(page, responses) {
  expect(transcript.scope).toBe('SYNTHETIC_TEST_ONLY')
  const calls = []
  await page.route('**/*', async route => {
    const { origin: target, pathname } = new URL(route.request().url())
    if (target !== origin) return route.abort()
    if (pathname === workspace) {
      const html = await readFile(new URL('index.html', assets), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc21-csrf" content="SYNTHETIC-T024"></head>') })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(?:css|js)$/.test(pathname)) {
      return route.fulfill({ contentType: pathname.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(new URL(pathname.slice(1), assets)) })
    }
    const endpoint = pathname.replace(/^\/api\/v1\/staff\//, '')
    const response = responses[endpoint]
    if (!response) return route.abort()
    calls.push({ endpoint, input: route.request().postDataJSON() })
    return route.fulfill({ status: response.status, json: response.body })
  })
  await page.goto(workspace)
  return calls
}

async function fill(page, data) {
  await page.getByLabel('诊所编号').fill(data.clinicId)
  await page.getByLabel('牙医编号').fill(data.dentistId)
  await page.getByLabel('开始时间').fill(data.startsAt)
  await page.getByLabel('结束时间').fill(data.endsAt)
}

const button = (page, name) => page.getByRole('button', { name, exact: true })
const { scheduleId, versionNo } = transcript.version.body.ref
const revision = `schedules/${scheduleId}/draft-revisions`
const basis = `schedules/${scheduleId}/versions/${versionNo}`

async function selectVersion(page) {
  await page.getByLabel('安排编号').fill(scheduleId)
  await page.getByLabel('版本号').fill(String(versionNo))
  await button(page, '读取指定版本').click()
  await expect(page.getByText(new RegExp(`已读取 ${scheduleId} / ${versionNo} · 已生效`))).toBeVisible()
}

test('shows actual mock-service draft receipts and committed impact without an activation control', async ({ page }) => {
  const calls = await serve(page, { 'schedule-drafts': transcript.create, [basis]: transcript.version,
    [revision]: transcript.revise })
  await fill(page, transcript.createInput)
  await button(page, '保存首次草案').click()
  await expect(page.getByRole('status')).toContainText('已保存待生效草案')
  await expect(page.getByText('授权诊所范围内没有需要复核的预约。')).toBeVisible()
  await fill(page, transcript.reviseInput)
  await selectVersion(page)
  await page.getByLabel('调整依据').fill(transcript.reviseInput.changeBasis)
  await button(page, '保存修订草案').click()
  await expect(page.getByRole('status')).toHaveText(`已保存待生效草案 ${scheduleId} / ${versionNo + 1}。`)
  for (const id of transcript.revise.body.appointmentImpact.appointmentIds) {
    await expect(page.getByText(`预约 ${id}`, { exact: true })).toBeVisible()
  }
  await expect(page.getByRole('button', { name: /生效|激活/ })).toHaveCount(0)
  expect(calls).toEqual([
    { endpoint: 'schedule-drafts', input: transcript.createInput },
    { endpoint: basis, input: null },
    { endpoint: revision, input: transcript.reviseInput },
  ])
})

test('uses captured stale and incomplete receipts without presenting a draft or impact', async ({ page }) => {
  const calls = await serve(page, { [basis]: transcript.version, [revision]: transcript.stale,
    'schedule-maintenance-issues': transcript.issue })
  await fill(page, transcript.reviseInput)
  await selectVersion(page)
  await page.getByLabel('调整依据').fill(transcript.reviseInput.changeBasis)
  const revise = button(page, '保存修订草案')
  await revise.click()
  await expect(page.getByRole('status')).toHaveText(transcript.stale.body.message)
  await expect(revise).toBeDisabled()
  await page.getByLabel('未完成原因').fill(transcript.issueInput.failureReason)
  await button(page, '登记未完成事项').click()
  await expect(page.getByRole('status')).toHaveText(`已登记未完成事项 ${transcript.issue.body.links.issueId}。`)
  await expect(page.getByRole('heading', { name: '本次草案保存的受影响预约' })).toHaveCount(0)
  expect(calls.at(-1).input).toEqual(transcript.issueInput)
})
