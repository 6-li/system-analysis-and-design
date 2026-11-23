import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'

const origin = 'https://adc-t024.invalid'
const workspace = '/staff/schedule-workspace'
const assets = new URL('../../../.tools/t024-demo-vue/', import.meta.url)
const evidence = JSON.parse(await readFile(process.env.ADC_T024_READ_EVIDENCE, 'utf8'))

async function open(page, replies) {
  expect(evidence.scope).toBe('SYNTHETIC_TEST_ONLY')
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== origin) return route.abort()
    if (url.pathname === workspace) {
      return route.fulfill({ contentType: 'text/html', body: await readFile(new URL('index.html', assets), 'utf8') })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.(?:css|js)$/.test(url.pathname)) {
      return route.fulfill({
        contentType: url.pathname.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(new URL(url.pathname.slice(1), assets)),
      })
    }
    const response = replies.get(url.pathname + url.search.replace(/^\?$/, ''))
    if (!response || route.request().method() !== 'GET') return route.abort()
    return route.fulfill({ status: response.status, json: response.body })
  })
  await page.goto(origin + workspace)
}

const api = '/api/v1/staff'
const issuePath = api + '/schedule-maintenance-issues/'
const queryIssue = page => page.getByRole('button', { name: '查询未完成事项', exact: true }).click()
const issueNumber = page => page.getByLabel('事项编号', { exact: true })

test('authorized 001 rows and actual 011 failure reason reach Vue', async ({ page }) => {
  await open(page, new Map([[api + '/schedules', evidence.list], [issuePath + '501', evidence.issue],
    [issuePath + '999999', evidence.missingIssue]]))
  await page.getByRole('button', { name: '查询安排', exact: true }).click()
  await expect(page.getByText(/9000 \/ 1 · 已生效/)).toBeVisible()
  await expect(page.getByText(/9001 \/ 1/)).toHaveCount(0)
  await issueNumber(page).fill('501')
  await queryIssue(page)
  await expect(page.getByText('事项 501 · ' + evidence.issue.body.failureReason, { exact: true })).toBeVisible()
  await issueNumber(page).fill('999999')
  await queryIssue(page)
  await expect(page.getByText(evidence.missingIssue.body.message, { exact: true })).toBeVisible()
  await expect(page.getByText(evidence.issue.body.failureReason, { exact: false })).toHaveCount(0)
})

test('captured cross-clinic rejections show no restricted rows or issue details', async ({ page }) => {
  await open(page, new Map([[api + '/schedules?clinicId=2', evidence.forbiddenList],
    [issuePath + '501', evidence.deniedIssue]]))
  await page.getByLabel('诊所编号').fill('2')
  await page.getByRole('button', { name: '查询安排', exact: true }).click()
  await expect(page.getByText(evidence.forbiddenList.body.message, { exact: true })).toBeVisible()
  await expect(page.getByText(/9001 \/ 1/)).toHaveCount(0)
  await issueNumber(page).fill('501')
  await queryIssue(page)
  await expect(page.getByText(evidence.deniedIssue.body.message, { exact: true })).toBeVisible()
  await expect(page.getByText(evidence.issue.body.failureReason, { exact: false })).toHaveCount(0)
})
