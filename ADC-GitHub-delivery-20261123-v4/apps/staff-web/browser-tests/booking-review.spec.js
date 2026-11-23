import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { pathToFileURL } from 'node:url'
import { resolve, sep } from 'node:path'

const build = process.env.ADC_UC02_BUNDLE
  ? pathToFileURL(resolve(process.env.ADC_UC02_BUNDLE) + sep)
  : new URL('../../../.tools/uc02-rid009-vue-fix/', import.meta.url)
const appointment = '/api/v1/staff/appointments'
const reference = key => `UC/v1/UC02/0123456789abcdef0123456789abcdef/APPOINTMENT_CREATE/${key}`
const receipt = route => ({
  requestReference: reference(route.request().headers()['idempotency-key']),
  ucCode: 'UC02', operationKind: 'APPOINTMENT_CREATE',
  message: 'SYNTHETIC-response',
})
const success = route => ({
  ...receipt(route), status: 'COMPLETED', businessOutcome: 'SUCCEEDED', auditPersistence: 'CONFIRMED',
  existingResult: false, links: { appointmentId: '901', originalUcSuccessEventId: '2' },
})
const stored = page => page.evaluate(() => JSON.parse(sessionStorage.getItem('SYNTHETIC-UC02-pending')))

async function mount(page, onSubmit, path = '/') {
  const requests = []
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== 'https://adc-uc02.invalid') return route.abort()
    if (url.pathname === appointment && route.request().method() === 'POST') {
      requests.push({ key: route.request().headers()['idempotency-key'], input: route.request().postDataJSON(),
        payload: route.request().postData() })
      return onSubmit(route, requests.length)
    }
    if (url.pathname === '/') {
      return route.fulfill({ contentType: 'text/html',
        body: await readFile(new URL('browser-tests/fixtures/booking-review.html', build)) })
    }
    if (/^\/assets\/[A-Za-z0-9_-]+\.js$/.test(url.pathname)) {
      return route.fulfill({ contentType: 'text/javascript', body: await readFile(new URL(url.pathname.slice(1), build)) })
    }
    return route.abort()
  })
  await page.goto(path)
  return requests
}

const match = page => page.getByRole('button', { name: '重新匹配' })
const submit = page => page.getByRole('button', { name: '提交预约' })
const confirm = page => page.getByRole('button', { name: '注入合成接受记录' })
const click = (page, name) => page.getByRole('button', { name }).click()

async function send(page) {
  await match(page).click()
  await confirm(page).click()
  await submit(page).click()
}

async function expectPending(page, request) {
  await expect(page.getByRole('status')).toContainText('提交结果待核对')
  await expect(page.getByText(reference(request.key), { exact: false })).toBeVisible()
  await expect(match(page)).toBeDisabled()
  await expect(submit(page)).toBeDisabled()
  await expect(page.getByText('预约已确认。')).toHaveCount(0)
  expect(await stored(page)).toEqual({
    requestKey: request.key, requestReference: reference(request.key),
    payloadSha256: createHash('sha256').update(request.payload).digest('hex'),
  })
}

test('409 preserves demand, requires a new confirmation and submits the new version once', async ({ page }) => {
  const requests = await mount(page, (route, attempt) => attempt === 1
    ? route.fulfill({ status: 409, json: { code: 'STALE_DURATION_BASIS', message: 'SYNTHETIC-stale' } })
    : route.fulfill({ status: 200, json: success(route) }))
  await match(page).click()
  await expect(submit(page)).toBeDisabled()
  await confirm(page).click()
  await submit(page).click()
  await expect(page.getByRole('status')).toContainText('重新匹配并取得申请人确认')
  expect(await stored(page)).toBeNull()
  expect(requests).toHaveLength(1)
  await match(page).click()
  await expect(submit(page)).toBeDisabled()
  await confirm(page).click()
  await submit(page).click()
  await expect(page.getByText('预约编号 901')).toBeVisible()
  expect(await stored(page)).toBeNull()
  expect(requests).toHaveLength(2)
  expect(requests[0].key).not.toBe(requests[1].key)
  expect(requests.map(({ input }) => input.scheme.durationBasis.projectVersion)).toEqual([1, 2])
  expect(requests.map(({ input }) => input.patientId)).toEqual(['88001', '88001'])
  expect(requests.map(({ input }) => input.acceptanceEvidenceReference))
    .toEqual(['SYNTHETIC-confirmation-1', 'SYNTHETIC-confirmation-2'])
})

test('pending request is single-flight and 202 blocks matching and resubmission', async ({ page }) => {
  let respond
  const hold = new Promise(resolve => { respond = resolve })
  const requests = await mount(page, async route => {
    await hold
    return route.fulfill({ status: 202, json: { ...receipt(route), status: 'UNKNOWN',
      businessOutcome: 'UNKNOWN', auditPersistence: 'UNKNOWN' } })
  })
  await match(page).click()
  await confirm(page).click()
  await submit(page).click()
  await expect(submit(page)).toBeDisabled()
  await expect(match(page)).toBeDisabled()
  respond()
  await expect(page.getByRole('status')).toContainText('不要重发')
  await expectPending(page, requests[0])
  await page.reload()
  await expectPending(page, requests[0])
  expect(requests).toHaveLength(1)
})

test('T025-06 stale schedule with matching reference requires rematch and new acceptance', async ({ page }) => {
  const requests = await mount(page, (route, attempt) => attempt === 1
    ? route.fulfill({ status: 409, json: { code: 'STALE_SCHEDULE', message: 'SYNTHETIC-stale-schedule',
      requestReference: receipt(route).requestReference } })
    : route.fulfill({ status: 200, json: success(route) }))
  await send(page)
  await expect(page.getByRole('status')).toContainText('接诊安排已变化')
  await expect(submit(page)).toBeDisabled()
  expect(await stored(page)).toBeNull()
  await match(page).click()
  await expect(submit(page)).toBeDisabled()
  await confirm(page).click()
  await submit(page).click()
  await expect(page.getByText('预约编号 901')).toBeVisible()
  expect(requests).toHaveLength(2)
  expect(requests[0].key).not.toBe(requests[1].key)
  expect(requests.map(({ input }) => input.patientId)).toEqual(['88001', '88001'])
  expect(requests.map(({ input }) => input.scheme.treatmentRequest)).toEqual(['SYNTHETIC-UC02', 'SYNTHETIC-UC02'])
  expect(requests.map(({ input }) => input.acceptanceEvidenceReference))
    .toEqual(['SYNTHETIC-confirmation-1', 'SYNTHETIC-confirmation-2'])
})

test('T025-07 stale schedule from another request remains unknown and is not resent', async ({ page }) => {
  const requests = await mount(page, route => route.fulfill({ status: 409, json: {
    code: 'STALE_SCHEDULE', message: 'SYNTHETIC-unrelated',
    requestReference: reference('00000000-0000-4000-8000-000000000001'),
  } }))
  await send(page)
  await expectPending(page, requests[0])
  await page.reload()
  await expectPending(page, requests[0])
  expect(requests).toHaveLength(1)
})

test('lost response keeps the original request key and cannot report success', async ({ page }) => {
  const requests = await mount(page, route => route.abort('connectionclosed'))
  await send(page)
  await expect(page.getByRole('status')).toContainText('提交结果待核对')
  expect(requests).toHaveLength(1)
  await expectPending(page, requests[0])
})

const ambiguous = [
  ['contradictory completed receipt', route => ({ status: 200, json: { ...success(route), businessOutcome: 'REJECTED' } })],
  ['success for a different request', route => ({ status: 200, json: { ...success(route),
    requestReference: receipt(route).requestReference.replace('0123456789abcdef0123456789abcdef', 'fedcba9876543210fedcba9876543210') } })],
  ['success without both business links', route => ({ status: 200, json: { ...success(route), links: { appointmentId: '901' } } })],
  ['unrecognized business state', route => ({ status: 200, json: { ...success(route), status: 'UNRECOGNIZED' } })],
  ['contract-external status', () => ({ status: 201, json: {} })],
  ['empty response body', () => ({ status: 200, body: '' })],
  ['same-key different-payload conflict', route => ({ status: 409, json: {
    code: 'SAME_REQUEST_DIFFERENT_PAYLOAD', message: 'SYNTHETIC-payload-conflict',
    requestReference: receipt(route).requestReference,
  } })],
]

for (const [name, respond] of ambiguous) {
  test(`${name} retains the original request across remount`, async ({ page }) => {
    const requests = await mount(page, route => route.fulfill(respond(route)))
    await send(page)
    await expect(page.getByRole('status')).toContainText('提交结果待核对')
    await expectPending(page, requests[0])
    await click(page, '卸载或重新挂载')
    await click(page, '卸载或重新挂载')
    await expectPending(page, requests[0])
    expect(requests).toHaveLength(1)
  })
}

for (const change of ['原地修改患者', '修改嵌套需求', '修改嵌套方案']) {
  test(`${change} invalidates the prior confirmation until a new match and acceptance`, async ({ page }) => {
    const requests = await mount(page, route => route.fulfill({ status: 200, json: success(route) }))
    await match(page).click()
    await confirm(page).click()
    await expect(submit(page)).toBeEnabled()
    await click(page, change)
    await expect(submit(page)).toBeDisabled()
    await expect(page.getByRole('status')).toContainText('需求或方案已变化')
    expect(requests).toHaveLength(0)
    await match(page).click()
    await expect(submit(page)).toBeDisabled()
    await confirm(page).click()
    await submit(page).click()
    await expect(page.getByText('预约编号 901')).toBeVisible()
    expect(requests).toHaveLength(1)
    expect(requests[0].input.patientId).toBe(change === '原地修改患者' ? '88002' : '88001')
    expect(requests[0].input.acceptanceEvidenceReference).toBe('SYNTHETIC-confirmation-2')
  })
}

test('changes while matching discard the late proposal even if the demand returns to its old value', async ({ page }) => {
  const requests = await mount(page, route => route.fulfill({ status: 200, json: success(route) }), '/?hold-match')
  await match(page).click()
  await expect(match(page)).toBeDisabled()
  await click(page, '修改嵌套需求')
  await click(page, '修改嵌套需求')
  await click(page, '释放匹配')
  await expect(page.getByRole('status')).toContainText('需求或方案已变化')
  await expect(confirm(page)).toBeDisabled()
  await match(page).click()
  await click(page, '释放匹配')
  await expect(submit(page)).toBeDisabled()
  await confirm(page).click()
  await submit(page).click()
  await expect(page.getByText('预约编号 901')).toBeVisible()
  expect(requests).toHaveLength(1)
  expect(requests[0].input.acceptanceEvidenceReference).toBe('SYNTHETIC-confirmation-2')
})
