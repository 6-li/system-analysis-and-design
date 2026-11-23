import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'

const { buildDataset } = createRequire(import.meta.url)('../../../tools/demo/p0-synthetic-data.cjs')
const [appointment] = buildDataset().appointments
const bundle = new URL('../../../.tools/' +
  (process.env.ADC_APPOINTMENT_READ_BUNDLE ?? 't028-appointment-read') + '/', import.meta.url)
const original = { ...appointment.effectiveScheduleRef, versionNo: 1 }
const support = {
  appointmentId: appointment.id, originScheduleRef: original, effectiveScheduleRef: appointment.effectiveScheduleRef,
  evaluatedAt: appointment.startsAt, result: 'SUPPORTED', reasons: [],
}
const detail = {
  appointmentId: appointment.id, patientId: appointment.patientId, statusLabel: appointment.status,
  originScheduleRef: original, currentSupport: support, startsAt: appointment.startsAt, endsAt: appointment.endsAt,
  stateToken: 'SYNTHETIC-state', durationBasis: { ...appointment.durationBasis, schemaVersion: 1, basisMode: 'INITIAL_CURRENT' },
}

async function mount(page, handler) {
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== 'https://adc-uc02.invalid') return route.abort()
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html',
      body: await readFile(new URL('browser-tests/fixtures/appointment-read.html', bundle)) })
    if (/^\/assets\/[\w-]+\.js$/.test(url.pathname)) return route.fulfill({ contentType: 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)) })
    if (url.pathname.startsWith('/api/v1/staff/appointments/') && route.request().method() === 'GET') {
      return handler(route, url.pathname.endsWith('/schedule-support'))
    }
    return route.abort()
  })
  await page.goto('/')
}

async function lookup(page, id = '901') {
  await page.getByLabel('预约编号').fill(id)
  await page.getByRole('button', { name: '查询' }).click()
}

test('keeps historic basis separate from the current version', async ({ page }) => {
  await mount(page, (route, isSupport) => route.fulfill({ json: isSupport ? support : detail }))
  await lookup(page)
  await expect(page.getByText('87001 / 版本 1')).toBeVisible()
  await expect(page.getByText('87001 / 版本 2')).toBeVisible()
  await expect(page.getByText('27 分钟')).toBeVisible()
})

test('clears prior details when access is withdrawn or record is missing', async ({ page }) => {
  let status = 200
  await mount(page, (route, isSupport) => route.fulfill({ status, json: isSupport ? support : detail }))
  await lookup(page)
  await expect(page.getByText('27 分钟')).toBeVisible()
  for (const [code, message] of [[403, '当前无权查看预约'], [404, '未找到可查看的预约']]) {
    status = code
    await lookup(page)
    await expect(page.getByRole('status')).toContainText(message)
    await expect(page.getByText('27 分钟')).toHaveCount(0)
  }
})

test('does not merge inconsistent snapshots or show values after dependency failure', async ({ page }) => {
  let status = 200
  await mount(page, (route, isSupport) => route.fulfill({ status,
    json: isSupport ? { ...support, appointmentId: '999' } : detail }))
  for (const code of [200, 503]) {
    status = code
    await lookup(page)
    await expect(page.getByRole('status')).toContainText('暂时无法核对预约')
    await expect(page.getByText('27 分钟')).toHaveCount(0)
  }
})

test('shows unresolved historical records without a guessed duration or effective reference', async ({ page }) => {
  const { effectiveScheduleRef, ...rest } = support
  const unresolved = { ...rest, result: 'UNRESOLVED' }
  await mount(page, (route, isSupport) => route.fulfill({
    json: isSupport ? unresolved : { ...detail, currentSupport: unresolved, durationBasis: undefined },
  }))
  await lookup(page)
  await expect(page.getByText('当前支持待核对')).toBeVisible()
  await expect(page.getByText('无可核验依据')).toBeVisible()
  await expect(page.getByText('87001 / 版本 2')).toHaveCount(0)
})

test('rejects a detail whose embedded historical basis contradicts both reads', async ({ page }) => {
  await mount(page, (route, isSupport) => route.fulfill({
    json: isSupport ? support : {
      ...detail,
      currentSupport: { ...support, originScheduleRef: { ...original, versionNo: 9 } },
    },
  }))
  await lookup(page)
  await expect(page.getByRole('status')).toContainText('暂时无法核对预约')
  await expect(page.getByText('87001 / 版本 1')).toHaveCount(0)
  await expect(page.getByText('27 分钟')).toHaveCount(0)
})
