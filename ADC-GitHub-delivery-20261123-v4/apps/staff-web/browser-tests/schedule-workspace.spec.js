import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const url = '/staff/schedule-workspace'
async function serveBuiltWorkspace(page, csrf = false) {
  await page.route('**/*', async route => {
    const pathname = new URL(route.request().url()).pathname
    if (pathname === url) {
      const raw = await readFile(new URL('../dist/index.html', import.meta.url), 'utf8')
      const body = csrf ? raw.replace('</head>', '<meta name="adc-uc21-csrf" content="synthetic"></head>') : raw
      return route.fulfill({ contentType: 'text/html', body })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(?:css|js)$/.test(pathname)) {
      const body = await readFile(new URL('../dist' + pathname, import.meta.url))
      return route.fulfill({ contentType: pathname.endsWith('.js') ? 'text/javascript' : 'text/css', body })
    }
    return route.abort()
  })
}
async function fillProposal(page) {
  await page.getByLabel('诊所编号').fill('1')
  await page.getByLabel('牙医编号').fill('7')
  await page.getByLabel('开始时间').fill('2026-11-07T09:00:00+08:00')
  await page.getByLabel('结束时间').fill('2026-11-07T10:00:00+08:00')
}

test('UC21 default workspace keeps writes closed without trusted bootstrap', async ({ page }) => {
  await serveBuiltWorkspace(page)
  await page.goto(url)
  await expect(page.getByRole('button', { name: '保存首次草案' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '登记未完成事项' })).toBeDisabled()
})

test('UC21 pending write is single-flight and unknown survives reload', async ({ page }) => {
  await serveBuiltWorkspace(page, true)
  let requests = 0
  let release
  const delay = new Promise(resolve => { release = resolve })
  await page.route('**/api/v1/staff/schedule-drafts', async route => {
    requests++
    await delay
    await route.fulfill({ status: 202, json: { requestReference: 'synthetic-ref', status: 'UNKNOWN' } })
  })
  await page.goto(url)
  await fillProposal(page)
  const save = page.getByRole('button', { name: '保存首次草案' })
  await save.click()
  await expect(save).toBeDisabled()
  await expect(page.getByRole('button', { name: '登记未完成事项' })).toBeDisabled()
  release()
  await expect(page.getByRole('alert')).toContainText('synthetic-ref')
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('synthetic-ref')
  await expect(save).toBeDisabled()
  expect(requests).toBe(1)
})

test('UC21 stale revision invalidates basis and separately recorded issue shows only issue ID', async ({ page }) => {
  await serveBuiltWorkspace(page, true)
  await page.route('**/api/v1/staff/schedules/80/versions/1', route => route.fulfill({
    json: { ref: { scheduleId: '80', versionNo: 1 }, stateToken: 'synthetic-old', statusLabel: '已生效' },
  }))
  await page.route('**/api/v1/staff/schedules/80/draft-revisions', route => route.fulfill({
    status: 409, json: { code: 'STALE_VERSION', message: '基础版本已变化' },
  }))
  let recorded
  await page.route('**/api/v1/staff/schedule-maintenance-issues', route => {
    recorded = route.request().postDataJSON()
    return route.fulfill({
      json: { requestReference: 'synthetic-issue', status: 'PERSISTED_INCOMPLETE', links: { issueId: '90', originalUcSuccessEventId: '54' } },
    })
  })
  await page.goto(url)
  await fillProposal(page)
  await page.getByLabel('安排编号').fill('80')
  await page.getByLabel('版本号').fill('1')
  await page.getByRole('button', { name: '读取指定版本' }).click()
  await page.getByLabel('调整依据').fill('synthetic-change')
  const revise = page.getByRole('button', { name: '保存修订草案' })
  await revise.click()
  await expect(page.getByRole('status')).toHaveText('基础版本已变化')
  await expect(revise).toBeDisabled()
  await page.getByLabel('未完成原因').fill('synthetic-reason')
  await page.getByRole('button', { name: '登记未完成事项' }).click()
  await expect(page.getByRole('status')).toHaveText('已登记未完成事项 90。')
  expect(recorded.submittedContext.basisRef).toEqual({ scheduleId: '80', versionNo: 1 })
})

test('UC21 revision sends the selected old issue and displays only the saved appointment impact', async ({ page }) => {
  await serveBuiltWorkspace(page, true)
  await page.route('**/api/v1/staff/schedules/80/versions/1', route => route.fulfill({
    json: { ref: { scheduleId: '80', versionNo: 1 }, stateToken: 'synthetic-fresh', statusLabel: '已生效' },
  }))
  let revised
  await page.route('**/api/v1/staff/schedules/80/draft-revisions', route => {
    revised = route.request().postDataJSON()
    return route.fulfill({ json: {
      requestReference: 'synthetic-revision', status: 'COMPLETED', businessOutcome: 'DRAFT',
      links: { scheduleRef: { scheduleId: '80', versionNo: 2 }, originalUcSuccessEventId: '54' },
      appointmentImpact: { appointmentIds: ['9007199254740999'] },
    } })
  })
  await page.goto(url)
  await fillProposal(page)
  await page.getByLabel('安排编号').fill('80')
  await page.getByLabel('版本号').fill('1')
  await page.getByRole('button', { name: '读取指定版本' }).click()
  await page.getByLabel('调整依据').fill('synthetic-change')
  await page.getByLabel('补正事项编号（修订，可选）').fill('91')
  await page.getByRole('button', { name: '保存修订草案' }).click()
  await expect(page.getByRole('status')).toHaveText('已保存待生效草案 80 / 2。')
  await expect(page.getByText('预约 9007199254740999', { exact: true })).toBeVisible()
  expect(revised.previousIssueId).toBe('91')
  expect(revised.basisRef).toEqual({ scheduleId: '80', versionNo: 1 })
})
