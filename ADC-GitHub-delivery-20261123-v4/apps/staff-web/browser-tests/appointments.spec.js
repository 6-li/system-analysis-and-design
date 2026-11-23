import { test, expect } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { resolve, sep } from 'node:path'

const bundle = process.env.ADC_APPOINTMENT_TEST_BUNDLE
  ? pathToFileURL(resolve(process.env.ADC_APPOINTMENT_TEST_BUNDLE) + sep)
  : new URL('../../../.tools/t033-appointments/', import.meta.url)
const origin = { scheduleId: '801', versionNo: 2 }
const support = { appointmentId: '901', originScheduleRef: origin, result: 'SUPPORTED', effectiveScheduleRef: origin }
const detail = { appointmentId: '901', stateToken: 'SYNTHETIC-token', originScheduleRef: origin,
  statusLabel: '待到诊', currentSupport: support,
  durationBasis: { primaryProjectId: '501', projectVersion: 2, adoptedDurationMinutes: 27 } }

async function mount(page, mutate, query = '') {
  const writes = []
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/') return route.fulfill({ contentType: 'text/html',
      body: await readFile(new URL('browser-tests/fixtures/appointments.html', bundle)) })
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)) })
    if (url.pathname === '/api/v1/staff/appointments/901') return route.fulfill({ json: detail })
    if (url.pathname === '/api/v1/staff/appointments/901/schedule-support') return route.fulfill({ json: support })
    if ((url.pathname === '/api/v1/staff/appointments'
        || ['changes', 'arrival-verifications', 'no-show-verifications']
          .some(action => url.pathname === '/api/v1/staff/appointments/901/' + action))
        && route.request().method() === 'POST') {
      writes.push(route.request())
      return mutate(route)
    }
    return route.abort()
  })
  await page.goto('/' + query)
  return writes
}

test('verified application context reaches create and incomplete context blocks submission', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC02', 'APPOINTMENT_CREATE', {
    appointmentId: '1401', originalUcSuccessEventId: '991',
  }), '?source=verified')
  await expect(page.getByText('已核验申请 SYNTHETIC-request')).toBeVisible()
  await expect(page.getByLabel('患者编号')).toHaveValue('88001')
  await expect(page.getByLabel('患者编号')).toHaveAttribute('readonly', '')
  for (const [label, value] of [
    ['生效安排编号', '802'], ['安排版本', '1'], ['开始时间（含偏移）', '2026-11-12T09:00:00+08:00'],
    ['结束时间（含偏移）', '2026-11-12T09:27:00+08:00'], ['诊疗需求', 'SYNTHETIC 诊疗'],
    ['主要项目编号', '501'], ['已发布项目版本', '2'], ['已发布项目时长（分钟）', '27'],
    ['申请人确认依据', 'SYNTHETIC accept'],
  ]) await page.getByLabel(label, { exact: true }).fill(value)
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('办理已确认。')).toBeVisible()
  expect(writes[0].postDataJSON()).toMatchObject({ patientId: '88001',
    sourceContextReference: 'SYNTHETIC-verified-context' })
  await page.goto('/?source=incomplete')
  await expect(page.getByRole('alert')).toContainText('申请核验信息不可用')
  await expect(page.getByRole('button', { name: '提交办理' })).toBeDisabled()
  expect(writes).toHaveLength(1)
})

async function lookup(page) {
  await page.getByLabel('预约编号').fill('901')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('预约已读取。')).toBeVisible()
}
async function fillProposal(page) {
  for (const [label, value] of [
    ['生效安排编号', '802'], ['安排版本', '1'], ['开始时间（含偏移）', '2026-11-12T09:00:00+08:00'],
    ['结束时间（含偏移）', '2026-11-12T09:27:00+08:00'], ['诊疗需求', 'SYNTHETIC 诊疗'],
    ['主要项目编号', '501'], ['改约理由', 'SYNTHETIC 时间调整'], ['申请人确认依据', 'SYNTHETIC accept'],
  ]) await page.getByLabel(label, { exact: true }).fill(value)
}

test('verified change request fixes the appointment and sends its source reference', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC03', 'APPOINTMENT_CHANGE', {
    appointmentId: '901', appointmentChangeId: '990', originalUcSuccessEventId: '991',
  }), '?changeSource=verified')
  await expect(page.getByText('已核验改约申请 SYNTHETIC-change')).toBeVisible()
  await expect(page.getByLabel('预约编号')).toHaveValue('901')
  await expect(page.getByLabel('预约编号')).toHaveAttribute('readonly', '')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('预约已读取。')).toBeVisible()
  await fillProposal(page)
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('办理已确认。')).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0].postDataJSON()).toMatchObject({
    expectedAppointmentToken: 'SYNTHETIC-token', sourceContextReference: 'SYNTHETIC-change-context',
  })
  await page.goto('/?changeSource=incomplete')
  await expect(page.getByRole('alert')).toContainText('改约申请核验信息不可用')
  await expect(page.getByRole('button', { name: '提交办理' })).toBeDisabled()
  expect(writes).toHaveLength(1)
})

test('unknown write retains the original key after reload and blocks resubmission', async ({ page }) => {
  const writes = await mount(page, route => route.fulfill({ status: 202, json: { status: 'UNKNOWN' } }))
  await lookup(page)
  await fillProposal(page)
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText(/提交结果待核对/)).toBeVisible()
  expect(writes[0].headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
  const key = writes[0].headers()['idempotency-key']
  await expect(page.getByText(new RegExp(key))).toBeVisible()
  await page.reload()
  await expect(page.getByText(new RegExp(key))).toBeVisible()
  await expect(page.getByRole('button', { name: '提交办理' })).toBeDisabled()
  expect(writes).toHaveLength(1)
})

test('stale reply and edited proposal each require renewed confirmation', async ({ page }) => {
  const writes = await mount(page, route => route.fulfill({ status: 409,
    json: { code: 'STALE_APPOINTMENT', message: 'SYNTHETIC stale' } }))
  await lookup(page)
  await fillProposal(page)
  const submit = page.getByRole('button', { name: '提交办理' })
  await page.getByLabel('结束时间（含偏移）').fill('2026-11-12T10:27:00+08:00')
  await expect(submit).toBeDisabled()
  await page.getByLabel('结束时间（含偏移）').fill('2026-11-12T09:27:00+08:00')
  await page.getByLabel('申请人确认依据').fill('SYNTHETIC new acceptance')
  await submit.click()
  await expect(page.getByText(/已失鲜/)).toBeVisible()
  await expect(page.getByLabel('申请人确认依据')).toHaveValue('')
  await expect(submit).toBeDisabled()
  await lookup(page)
  await expect(submit).toBeDisabled()
  expect(writes).toHaveLength(1)
})

test('time changes display and send the original version, project changes display the new current version', async ({ page }) => {
  const writes = await mount(page, route => route.fulfill({ status: 409,
    json: { code: 'STALE_APPOINTMENT', message: 'SYNTHETIC stale' } }))
  await lookup(page)
  await fillProposal(page)
  await expect(page.getByTestId('duration-basis')).toContainText('沿用原预约版本 2 · 27 分钟')
  await page.getByLabel('主要项目编号').fill('502')
  await page.getByLabel('已发布项目版本').fill('5')
  await page.getByLabel('已发布项目时长（分钟）').fill('40')
  await page.getByLabel('结束时间（含偏移）').fill('2026-11-12T09:40:00+08:00')
  await expect(page.getByTestId('duration-basis')).toContainText('采用当前项目版本 5 · 40 分钟')
  await expect(page.getByRole('button', { name: '提交办理' })).toBeDisabled()
  await page.getByLabel('申请人确认依据').fill('SYNTHETIC new project acceptance')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText(/已失鲜/)).toBeVisible()
  const request = writes[0].postDataJSON()
  expect(request.scheme.durationBasis).toMatchObject({ basisMode: 'CHANGED_PROJECT_CURRENT', projectVersion: 5, adoptedDurationMinutes: 40 })
  expect(request.acceptanceEvidenceReference).toBe('SYNTHETIC new project acceptance')
})

test('server capabilities limit actions and anonymous sessions cannot submit', async ({ page }) => {
  await mount(page, route => route.abort(), '?clinic')
  await expect(page.getByLabel('办理事项').locator('option')).toHaveText(['登记到诊', '登记未到诊'])
  await page.goto('/?anonymous')
  await expect(page.getByText('当前会话没有预约办理权限。')).toBeVisible()
  await expect(page.getByRole('button', { name: '提交办理' })).toHaveCount(0)
})


const actorScope = '0123456789abcdef0123456789abcdef'
function committedReceipt(route, uc, operation, links, status = 'COMPLETED', businessOutcome = 'SUCCEEDED') {
  const key = route.request().headers()['idempotency-key']
  return route.fulfill({ json: {
    requestReference: 'UC/v1/' + uc + '/' + actorScope + '/' + operation + '/' + key,
    ucCode: uc, operationKind: operation, status, businessOutcome,
    auditPersistence: 'CONFIRMED', existingResult: false, links,
  } })
}

test('confirmed change releases the pending marker only for the matching persisted result', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC03', 'APPOINTMENT_CHANGE', {
    appointmentId: '901', appointmentChangeId: '990', originalUcSuccessEventId: '991',
  }))
  await lookup(page)
  await fillProposal(page)
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('办理已确认。')).toBeVisible()
  expect(writes).toHaveLength(1)
  await page.reload()
  await expect(page.getByText(/提交结果待核对/)).toHaveCount(0)
})

test('pending arrival is shown as saved verification without an attendance confirmation', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC05', 'ARRIVAL_VERIFY', {
    appointmentId: '901', originalUcSuccessEventId: '991',
  }, 'PERSISTED_INCOMPLETE', 'PENDING_VERIFICATION'), '?clinic')
  await lookup(page)
  await page.getByLabel('人工核实依据').fill('SYNTHETIC pending')
  await page.getByLabel('仍待核实').check()
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('待核实记录已保存。')).toBeVisible()
  await expect(page.getByText('办理已确认。')).toHaveCount(0)
  expect(writes[0].postDataJSON()).toEqual({
    expectedAppointmentToken: 'SYNTHETIC-token', finding: 'PENDING_VERIFICATION', verificationBasis: 'SYNTHETIC pending',
  })
})

test('classroom smoke UC05 confirms arrival and sends the verified identity', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC05', 'ARRIVAL_VERIFY', {
    appointmentId: '901', originalUcSuccessEventId: '991', visitId: '992',
  }), '?clinic')
  await lookup(page)
  await page.getByLabel('人工核实依据').fill('SYNTHETIC human evidence')
  await page.getByLabel('已核实身份依据').fill('SYNTHETIC identity')
  await page.getByLabel('实际诊所编号').fill('81001')
  await page.getByLabel('实际牙医编号').fill('79001')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('办理已确认。')).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0].postDataJSON()).toEqual({
    expectedAppointmentToken: 'SYNTHETIC-token', finding: 'ARRIVAL_VERIFIED',
    verificationBasis: 'SYNTHETIC human evidence', verifiedIdentityReference: 'SYNTHETIC identity',
    actualClinicId: '81001', actualDentistId: '79001',
  })
})

test('classroom smoke UC06 confirms no-show without creating a visit', async ({ page }) => {
  const writes = await mount(page, route => committedReceipt(route, 'UC06', 'NO_SHOW_VERIFY', {
    appointmentId: '901', originalUcSuccessEventId: '991',
  }), '?clinic')
  await page.getByLabel('办理事项').selectOption('noShow')
  await lookup(page)
  await page.getByLabel('人工核实依据').fill('SYNTHETIC human evidence')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByText('办理已确认。')).toBeVisible()
  expect(writes).toHaveLength(1)
  expect(writes[0].url()).toMatch(/\/no-show-verifications$/)
  expect(writes[0].postDataJSON()).toEqual({
    expectedAppointmentToken: 'SYNTHETIC-token', finding: 'NO_SHOW_VERIFIED',
    verificationBasis: 'SYNTHETIC human evidence',
  })
})

test('classroom smoke UC07 displays prior treatment and submits a verified health snapshot', async ({ page }) => {
  let submitted
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/visit-health') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc07-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/health') return route.abort()
    if (req.method() === 'GET' && url.searchParams.get('patientId') === '31') return route.fulfill({ json: {
      snapshot: { id: '400', medicalHistory: 'SYNTHETIC verified history', allergyInformation: null },
      priorTreatments: [{ visitId: '40', recordId: '51', treatment: 'SYNTHETIC previous treatment', result: '已实施' }],
    } })
    if (req.method() === 'POST') {
      submitted = req.postDataJSON()
      return route.fulfill({ json: { visitId: '41', healthChangeId: '401', requestReference: 'SYNTHETIC request' } })
    }
    return route.abort()
  })
  await page.goto('/staff/visit-health')
  await page.getByLabel('患者编号').fill('31')
  await page.getByLabel('本次就诊编号').fill('41')
  await page.getByRole('button', { name: '查询资料' }).click()
  await expect(page.getByText(/SYNTHETIC previous treatment/)).toBeVisible()
  await expect(page.getByLabel('病史')).toHaveValue('SYNTHETIC verified history')
  await page.getByLabel('过敏信息').fill('SYNTHETIC confirmed allergy')
  await page.getByLabel('变更及核实说明').fill('SYNTHETIC patient verification')
  await page.getByRole('button', { name: '保存已核实变更' }).click()
  await expect(page.getByRole('status')).toContainText('记录编号 401')
  await expect(page.getByLabel('过敏信息')).toHaveValue('SYNTHETIC confirmed allergy')
  await expect(page.getByRole('button', { name: '保存已核实变更' })).toBeDisabled()
  expect(submitted).toEqual({ patientId: '31', expectedSnapshotId: '400',
    medicalHistory: 'SYNTHETIC verified history', allergyInformation: 'SYNTHETIC confirmed allergy',
    changeContent: 'SYNTHETIC patient verification' })
})

test('classroom smoke UC08 records actual consent and refreshes the visit history', async ({ page }) => {
  const entries = []
  const earlier = { consentResultId: '400', communicationParty: 'SYNTHETIC patient',
    guardianParticipation: null, proposedTreatment: 'SYNTHETIC earlier scope', consentResult: '待确认',
    comments: 'SYNTHETIC review pending', recordedBy: 'SYNTHETIC dentist', recordedAt: '2026-11-17T02:00:00Z' }
  entries.push(earlier)
  let submitted
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/visit-consent') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc08-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/consent') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    if (req.method() === 'GET' && url.searchParams.get('patientId') === '31') {
      return route.fulfill({ json: { visitId: '41', patientId: '31', entries } })
    }
    if (req.method() === 'POST') {
      submitted = req.postDataJSON()
      entries.push({ ...earlier, ...submitted, consentResultId: '401' })
      const key = req.headers()['idempotency-key']
      expect(key).toMatch(/^[0-9a-f-]{36}$/)
      return route.fulfill({ json: { visitId: '41', consentResultId: '401',
        requestReference: 'UC/v1/UC08/SYNTHETIC/TREATMENT_CONSENT_RECORD/' + key } })
    }
    return route.abort()
  })
  await page.goto('/staff/visit-consent')
  await page.getByLabel('患者编号').fill('31')
  await page.getByLabel('本次就诊编号').fill('41')
  await page.getByRole('button', { name: '查询沟通记录' }).click()
  await expect(page.getByText(/SYNTHETIC earlier scope/)).toBeVisible()
  await page.getByLabel('实际沟通对象').fill('SYNTHETIC patient and guardian')
  await page.getByLabel('拟治疗内容及范围').fill('SYNTHETIC treatment scope')
  await page.getByLabel('本次需要监护人参与或同意').check()
  await page.getByLabel('监护人参与情况').fill('SYNTHETIC guardian agreed')
  await page.getByLabel('实际同意结论').selectOption('同意')
  await page.getByRole('button', { name: '保存同意结果' }).click()
  await expect(page.getByRole('status')).toContainText('记录编号 401')
  await expect(page.getByText(/SYNTHETIC treatment scope/)).toBeVisible()
  await expect(page.getByText(/SYNTHETIC earlier scope/)).toBeVisible()
  expect(submitted).toEqual({ patientId: '31', communicationParty: 'SYNTHETIC patient and guardian',
    guardianRequired: true, guardianParticipation: 'SYNTHETIC guardian agreed',
    proposedTreatment: 'SYNTHETIC treatment scope', consentResult: '同意', comments: null })
})

test('classroom smoke UC09 records performed treatment from the visit consent', async ({ page }) => {
  let submitted
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/visit-treatment') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc09-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/treatment') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    if (req.method() === 'GET' && url.searchParams.get('patientId') === '31') return route.fulfill({
      json: { patientId: '31', visitId: '41', consents: [
        { consentResultId: '400', proposedTreatment: 'SYNTHETIC treatment', consentResult: '同意' },
      ] },
    })
    if (req.method() === 'POST') {
      submitted = req.postDataJSON()
      return route.fulfill({ json: { visitId: '41', clinicalRecordId: '701', recordVersionNo: 1,
        requestReference: 'UC/v1/UC09/SYNTHETIC/ACTUAL_TREATMENT_RECORD/' + req.headers()['idempotency-key'] } })
    }
    return route.abort()
  })
  await page.goto('/staff/visit-treatment')
  await page.getByLabel('患者编号').fill('31')
  await page.getByLabel('本次就诊编号').fill('41')
  await page.getByRole('button', { name: '查询同意记录' }).click()
  await page.getByLabel('同意结果').selectOption('400')
  await page.getByLabel('实施情况').selectOption('yes')
  await page.getByLabel('实际项目').fill('SYNTHETIC treatment')
  await page.getByLabel('计费单位').fill('次')
  await page.getByLabel('实际数量').fill('1.250')
  await page.getByRole('button', { name: '保存诊疗记录' }).click()
  await expect(page.getByRole('status')).toContainText('记录编号 701，版本 1')
  expect(submitted).toEqual({ patientId: '31', consentResultId: '400', performed: true,
    project: 'SYNTHETIC treatment', unit: '次', quantity: '1.250',
    specialRequirements: null, nonPerformanceReason: null })
})

test('classroom smoke UC10 shows the generated invoice and actual treatment charges', async ({ page }) => {
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/treatment-invoice') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc10-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/invoice' || req.method() !== 'POST') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC10/SYNTHETIC/TREATMENT_INVOICE_GENERATE/' + req.headers()['idempotency-key'],
      invoice: { visitId: '41', invoiceId: '801', invoiceVersionNo: 1, status: '待核对确认', total: '33.33',
        lines: [{ clinicalRecordId: '701', recordVersionNo: 1, item: 'SYNTHETIC treatment',
          unit: '次', quantity: '1.25', unitPrice: '26.66', amount: '33.33' }] },
    } })
  })
  await page.goto('/staff/treatment-invoice')
  await page.getByLabel('本次就诊编号').fill('41')
  await page.getByRole('button', { name: '生成或查看账单' }).click()
  await expect(page.getByRole('status')).toContainText('账单 801，版本 1')
  await expect(page.getByText('待核对确认')).toBeVisible()
  await expect(page.getByRole('row', { name: /SYNTHETIC treatment.*1.25.*26.66.*33.33/ })).toBeVisible()
  await expect(page.getByText('合计 33.33 元')).toBeVisible()
})

test('classroom smoke UC11 opens a price question and recalculates the same invoice', async ({ page }) => {
  const invoice = { visitId: '41', invoiceId: '801', invoiceVersionNo: 1, status: '待核对确认', total: '33.33',
    lines: [{ clinicalRecordId: '701', recordVersionNo: 1, item: 'SYNTHETIC treatment',
      unit: '次', quantity: '1.25', unitPrice: '26.66', amount: '33.33' }] }
  let current = { invoice, question: null, handling: {} }
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/invoice-question') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc11-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/invoice-question') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    if (req.method() === 'GET') return route.fulfill({ json: current })
    const input = req.postDataJSON()
    const reference = 'UC/v1/UC11/SYNTHETIC/' + input.operation + '/' + req.headers()['idempotency-key']
    if (input.operation === 'INVOICE_QUESTION_OPEN') {
      expect(input.clinicalQuestion).toBe(false)
      current = { ...current, question: input.note, status: '待核实', handling: {
        scope: 'PRICE', phase: 'PENDING', questionReference: reference,
      }}
    } else if (input.operation === 'INVOICE_QUESTION_RECALCULATE') {
      expect(input.operation).toBe('INVOICE_QUESTION_RECALCULATE')
      expect(input.questionReference).toBe(current.handling.questionReference)
      current = { ...current, status: '待再次确认', handling: { ...current.handling, phase: 'RECALCULATED' },
        invoice: { ...invoice, invoiceVersionNo: 2, total: '30.00',
          lines: [{ ...invoice.lines[0], unitPrice: '24.00', amount: '30.00' }] } }
    } else {
      expect(input.operation).toBe('INVOICE_APPLICANT_CONFIRM')
      expect(input.invoiceVersionNo).toBe(2)
      expect(input.applicant).toEqual({ identity: 'SYNTHETIC applicant', acceptedAmount: '30.00' })
      current = { ...current, status: '待结算', invoice: { ...current.invoice, status: '待结算' } }
    }
    return route.fulfill({ json: { requestReference: reference, review: current } })
  })
  await page.goto('/staff/invoice-question')
  await page.getByLabel('患者编号').fill('31')
  await page.getByLabel('就诊编号').fill('41')
  await page.getByLabel('账单编号').fill('801')
  await page.getByLabel('当前账单版本').fill('1')
  await page.getByRole('button', { name: '查询账单及疑问' }).click()
  await expect(page.getByRole('heading', { name: '账单 801 · 版本 1' })).toBeVisible()
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC price question')
  await page.getByRole('button', { name: '登记疑问' }).click()
  await expect(page.getByRole('status')).toContainText('费用疑问已登记')
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC price review')
  await page.getByRole('button', { name: '按核实依据重算' }).click()
  await expect(page.getByRole('status')).toContainText('请重新取得申请人确认')
  await expect(page.getByRole('heading', { name: '账单 801 · 版本 2' })).toBeVisible()
  await expect(page.getByText('待再次确认 · 合计 30.00 元')).toBeVisible()
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC applicant accepted this version')
  await page.getByLabel('实际确认的申请人').fill('SYNTHETIC applicant')
  await page.getByLabel('申请人确认的金额').fill('30.00')
  await page.getByRole('button', { name: '登记申请人再次确认' }).click()
  await expect(page.getByRole('status')).toContainText('状态为待结算')
  await expect(page.getByText('待结算 · 合计 30.00 元')).toBeVisible()
})

test('classroom smoke UC11 submits two clinical corrections together', async ({ page }) => {
  const invoice = { visitId: '41', invoiceId: '801', invoiceVersionNo: 1, status: '待核实', total: '66.66',
    lines: ['701', '702'].map((id, index) => ({ clinicalRecordId: id, recordVersionNo: 1,
      item: 'SYNTHETIC treatment ' + (index + 1), unit: '次', quantity: '1.25', unitPrice: '26.66', amount: '33.33' })) }
  const review = { invoice, question: 'SYNTHETIC clinical question',
    handling: { scope: 'CLINICAL', phase: 'PENDING', questionReference: 'SYNTHETIC-Q1' } }
  let submits = 0
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/invoice-question') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc11-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/invoice-question') return route.abort()
    if (request.method() === 'GET') {
      expect(url.searchParams.get('clinical')).toBe('true')
      return route.fulfill({ json: review })
    }
    submits++
    const body = request.postDataJSON()
    expect(body.operation).toBe('INVOICE_CLINICAL_CORRECT')
    expect(body.corrections).toEqual([
      { record: '701', consent: '400', project: 'SYNTHETIC treatment 1', unit: '次', quantity: '1.5' },
      { record: '702', consent: '401', project: 'SYNTHETIC treatment 2', unit: '次', quantity: '2' },
    ])
    const reference = 'UC/v1/UC11/SYNTHETIC/' + body.operation + '/' + request.headers()['idempotency-key']
    return route.fulfill({ json: { requestReference: reference, review: { ...review,
      handling: { ...review.handling, phase: 'CLINICAL_CONFIRMED', clinicalConfirmation: { conclusion: '已修正' } },
    } } })
  })
  await page.goto('/staff/invoice-question')
  await page.getByLabel('患者编号').fill('31')
  await page.getByLabel('就诊编号').fill('41')
  await page.getByLabel('账单编号').fill('801')
  await page.getByLabel('当前账单版本').fill('1')
  await page.getByLabel('牙医核实视图').check()
  await page.getByRole('button', { name: '查询账单及疑问' }).click()
  await page.getByLabel('登记诊疗修正').check()
  await page.getByLabel(/修正 SYNTHETIC treatment 1/).check()
  await page.getByLabel(/修正 SYNTHETIC treatment 2/).check()
  await page.getByLabel('对应同意结果编号').nth(0).fill('400')
  await page.getByLabel('对应同意结果编号').nth(1).fill('401')
  await page.getByLabel('核实后的数量').nth(0).fill('1.5')
  await page.getByLabel('核实后的数量').nth(1).fill('2')
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC dentist verified both quantities')
  await page.getByRole('button', { name: '保存诊疗修正' }).click()
  await expect(page.getByRole('status')).toContainText('诊疗修正已保存')
  expect(submits).toBe(1)
})

test('classroom smoke UC12 confirms an invoice and verifies the same simulated payment', async ({ page }) => {
  let phase = 0
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/invoice-settlement') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc12-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/visits/41/demo-settlement') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    const input = req.postDataJSON()
    expect(input.invoiceId).toBe('801')
    expect(input.invoiceVersionNo).toBe(1)
    phase++
    if (phase === 1) {
      expect(input).toMatchObject({ operation: 'DEMO_SETTLEMENT_START', paymentId: null,
        confirmation: { applicant: 'SYNTHETIC applicant', amount: '33.33', basis: 'SYNTHETIC confirmed' } })
    } else {
      expect(input).toMatchObject({ operation: 'DEMO_SETTLEMENT_VERIFY', paymentId: '901', confirmation: null })
    }
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC12/SYNTHETIC/' + input.operation + '/' + req.headers()['idempotency-key'],
      payment: { paymentId: '901', invoiceId: '801', invoiceVersionNo: 1, visitId: '41', amount: '33.33',
        status: phase === 1 ? '待核验' : '已结算', synthetic: true,
        ...(phase === 2 ? { receipt: 'SYNTHETIC-UC12-901' } : {}) },
    } })
  })
  await page.goto('/staff/invoice-settlement')
  await page.getByLabel('就诊编号').fill('41')
  await page.getByLabel('账单编号').fill('801')
  await page.getByLabel('当前账单版本').fill('1')
  await page.getByLabel('登记首版账单确认').check()
  await page.getByLabel('申请人').fill('SYNTHETIC applicant')
  await page.getByLabel('确认金额（元）').fill('33.33')
  await page.getByLabel('确认依据').fill('SYNTHETIC confirmed')
  await page.getByRole('button', { name: '发起或查看模拟交易' }).click()
  await expect(page.getByRole('status')).toContainText('等待核验')
  await page.getByRole('button', { name: '核验并完成模拟结算' }).click()
  await expect(page.getByRole('status')).toHaveText('模拟结算完成')
  await expect(page.getByText('模拟凭据 SYNTHETIC-UC12-901')).toBeVisible()
  expect(phase).toBe(2)
})

test('classroom smoke UC13 displays clinic stock, an existing warning and missing data', async ({ page }) => {
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/stock-inquiry') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc13-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/clinics/11/stock-inquiry' || req.method() !== 'POST') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    expect(req.postDataJSON()).toEqual({ supplyId: null })
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC13/SYNTHETIC/STOCK_INQUIRY/' + req.headers()['idempotency-key'],
      stock: { clinicId: '11', supplyId: null, queriedAt: '2026-11-17T04:05:06Z', items: [
        { supplyId: '101', name: 'SYNTHETIC gloves', unit: '盒', stockPresent: true,
          onHand: '4.000', threshold: null, existingWarning: 'SYNTHETIC 临近有效期预警' },
        { supplyId: '102', name: 'SYNTHETIC gauze', unit: '盒', stockPresent: false,
          onHand: null, threshold: null, existingWarning: null },
      ] },
    } })
  })
  await page.goto('/staff/stock-inquiry')
  await page.getByLabel('诊所编号').fill('11')
  await page.getByRole('button', { name: '查询库存' }).click()
  await expect(page.getByText(/诊所 11 · 全部用品/)).toBeVisible()
  await expect(page.getByRole('row', { name: /SYNTHETIC gloves.*4.000.*未维护.*临近有效期预警/ })).toBeVisible()
  await expect(page.getByRole('row', { name: /SYNTHETIC gauze.*无库存记录/ })).toBeVisible()
})

test('classroom smoke UC14 registers confirmed demand and reports unavailable supply on the same requirement', async ({ page }) => {
  let requests = 0
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/purchase-requirements') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc14-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    const base = '/api/v1/staff/clinics/11/purchase-requirements'
    if (req.method() !== 'POST' || ![base, base + '/901/unavailable'].includes(url.pathname)) return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    const unavailable = url.pathname !== base
    requests++
    const input = req.postDataJSON()
    if (unavailable) expect(input).toEqual({ explanation: 'SYNTHETIC supplier cannot deliver' })
    else expect(input).toEqual({ supplyId: '101', quantity: '8', confirmationReference: 'SYNTHETIC-CONF-1' })
    const op = unavailable ? 'PURCHASE_REQUIREMENT_UNAVAILABLE' : 'PURCHASE_REQUIREMENT_REGISTER'
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC14/SYNTHETIC/' + op + '/' + req.headers()['idempotency-key'],
      requirement: { requirementId: '901', clinicId: '11', supplyId: '101', quantity: '8',
        status: unavailable ? '无法供货待调整' : '待联系供应商', explanation: input.explanation ?? null },
    } })
  })
  await page.goto('/staff/purchase-requirements')
  await page.getByLabel('诊所编号').fill('11')
  await page.getByLabel('用品编号').fill('101')
  await page.getByLabel('采购需求数量').fill('8')
  await page.getByLabel('负责人确认依据编号').fill('SYNTHETIC-CONF-1')
  await page.getByRole('button', { name: '登记采购需求' }).click()
  await expect(page.getByRole('status')).toContainText('901 · 待联系供应商')
  await expect(page.getByLabel('采购需求编号')).toHaveValue('901')
  await page.getByLabel('无法供货及待调整说明').fill('SYNTHETIC supplier cannot deliver')
  await page.getByRole('button', { name: '保存无法供货情况' }).click()
  await expect(page.getByRole('status')).toContainText('901 · 无法供货待调整')
  expect(requests).toBe(2)
})

test('classroom smoke UC15 records a discrepancy then receives the qualified delivery once', async ({ page }) => {
  let phase = 0
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/supply-receipt') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc15-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/staff/clinics/11/purchase-requirements/901/receipt') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    const input = req.postDataJSON()
    phase++
    expect(input.deliveryLookup).toBe('SYNTHETIC DELIVERY')
    if (phase === 1) expect(input).toMatchObject({ accepted: false, received: null, cost: null, expiry: null,
      discrepancy: 'SYNTHETIC quantity mismatch' })
    else expect(input).toMatchObject({ accepted: true, received: '6', cost: '12.40', expiry: '2027-04-05' })
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC15/SYNTHETIC/SUPPLY_RECEIPT_RECORD/' + req.headers()['idempotency-key'],
      receipt: { receiptId: '1001', clinicId: '11', requirementId: '901', status: phase === 1 ? '差异待处理' : '已入库',
        received: input.received, cost: input.cost, expiry: input.expiry, stockBalanceAfter: phase === 1 ? null : '10' },
    } })
  })
  await page.goto('/staff/supply-receipt')
  await page.getByLabel('诊所编号').fill('11')
  await page.getByLabel('采购需求编号').fill('901')
  await page.getByLabel('交付核对编号').fill('SYNTHETIC DELIVERY')
  await page.getByLabel('办理结果').selectOption('pending')
  await page.getByLabel('实际验收情况').fill('SYNTHETIC initial discrepancy')
  await page.getByLabel('交付差异说明').fill('SYNTHETIC quantity mismatch')
  await page.getByRole('button', { name: '保存验收结果' }).click()
  await expect(page.getByRole('status')).toContainText('1001 · 差异待处理')
  await expect(page.getByText('差异已记录，库存未增加。')).toBeVisible()
  await page.getByLabel('办理结果').selectOption('accepted')
  await page.getByLabel('实际验收情况').fill('SYNTHETIC acceptance verified')
  await page.getByLabel('验收实收量').fill('6')
  await page.getByLabel('采购成本（元）').fill('12.40')
  await page.getByLabel('有效期').fill('2027-04-05')
  await page.getByRole('button', { name: '保存验收结果' }).click()
  await expect(page.getByRole('status')).toContainText('1001 · 已入库')
  await expect(page.getByText('本次入库后库存 10')).toBeVisible()
  expect(phase).toBe(2)
})

test('classroom smoke UC16 records verified consumption and shows actual cost and remaining stock', async ({ page }) => {
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/supply-consumption') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc16-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/staff/clinics/11/supply-consumption') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    expect(req.postDataJSON()).toEqual({ supplyId: '101', quantity: '2.5', occurredAt: '2026-11-17T15:30:00+08:00',
      proofReference: 'SYNTHETIC cost fact' })
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC16/SYNTHETIC/SUPPLY_CONSUMPTION_RECORD/' + req.headers()['idempotency-key'],
      consumption: { consumptionId: '811', clinicId: '11', supplyId: '101', quantity: '2.5', cost: '5.75',
        occurredAt: '2026-11-17T07:30:00Z', stockBalanceAfter: '7.5' },
    } })
  })
  await page.goto('/staff/supply-consumption')
  await page.getByLabel('诊所编号').fill('11')
  await page.getByLabel('用品编号').fill('101')
  await page.getByLabel('实际耗用量').fill('2.5')
  await page.getByLabel('实际发生时间（含时区偏移）').fill('2026-11-17T15:30:00+08:00')
  await page.getByLabel('已核实成本依据编号').fill('SYNTHETIC cost fact')
  await page.getByRole('button', { name: '保存实际耗用' }).click()
  await expect(page.getByRole('status')).toContainText('耗用记录 811 已保存')
  await expect(page.getByText('实耗 2.5 · 实际成本 5.75 元 · 扣减后库存 7.5')).toBeVisible()
})

test('classroom smoke UC17 displays a scoped frozen quarterly preview', async ({ page }) => {
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/quarterly-preview') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc17-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/staff/quarterly/previews') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    expect(req.postDataJSON()).toEqual({ quarter: 'SYNTH-Q3', clinics: ['11', '12'] })
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC17/SYNTHETIC/QUARTERLY_PREVIEW_GENERATE/' + req.headers()['idempotency-key'],
      preview: { statisticId: '801', quarter: 'SYNTH-Q3', clinics: ['11', '12'], readiness: '达到汇总要求',
        sourceVersion: 'SYNTHETIC-MANIFEST-v1', manifestReference: 'quarterly_operating_statistic/801/source_basis',
        basis: { start: '2026-10-02T00:00:00Z', endExclusive: '2027-01-02T00:00:00Z', rules: 'SYNTHETIC 已核实口径' },
        receivable: '45.00', received: '33.33', expense: '9.00', consumption: '5.75',
        due: 2, noShow: 1, noShowRate: '0.500000', cancelled: 1, performance: [{ dentistId: '31', amount: '12.00' }],
        issues: [] },
    } })
  })
  await page.goto('/staff/quarterly-preview')
  await page.getByLabel('统计季度').fill('SYNTH-Q3')
  await page.getByLabel('诊所编号（多个用逗号分隔）').fill('11,12')
  await page.getByRole('button', { name: '生成统计预览' }).click()
  await expect(page.getByRole('status')).toContainText('统计快照 801 已保存')
  await expect(page.getByText('SYNTH-Q3 · 诊所 11、12 · 达到汇总要求')).toBeVisible()
  await expect(page.getByText('45.00', { exact: true })).toBeVisible()
  await expect(page.getByText('33.33', { exact: true })).toBeVisible()
  await expect(page.getByText('0.500000', { exact: true })).toBeVisible()
  await expect(page.getByText('牙医 31 · 12.00')).toBeVisible()
})

test('classroom smoke UC18 saves a clinic correction then reviews the same history from headquarters', async ({ page }) => {
  let phase = 0
  let issue = { statisticId: '801', issueId: '901', original: 'SYNTHETIC source amount missing',
    sourceVersion: 'SYNTHETIC v1', status: '待核实', current: null, historyId: null }
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/quarterly-issue') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc18-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (url.pathname !== '/api/v1/staff/quarterly/previews/801/issues/901') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    if (req.method() === 'GET') {
      expect(url.searchParams.get('clinics')).toBe(phase === 0 ? '11' : '11,12')
      return route.fulfill({ json: issue })
    }
    const input = req.postDataJSON()
    phase++
    const op = phase === 1 ? 'QUARTER_ISSUE_CORRECT' : 'QUARTER_ISSUE_REVIEW'
    if (phase === 1) {
      expect(input).toEqual({ clinics: '11', correction: true, historyId: null, value: '5.75', evidence: 'SYNTHETIC proof' })
      issue = { ...issue, current: '5.75', historyId: '1001', status: '待复核' }
    } else {
      expect(input).toEqual({ clinics: '11,12', correction: false, historyId: '1001', value: null, evidence: null })
      issue = { ...issue, status: '复核通过', consistencyResult: 'SYNTHETIC 一致性通过' }
    }
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC18/SYNTHETIC/' + op + '/' + req.headers()['idempotency-key'], issue,
    } })
  })
  await page.goto('/staff/quarterly-issue')
  await page.getByLabel('统计快照编号').fill('801')
  await page.getByLabel('异常事项编号').fill('901')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  await page.getByRole('button', { name: '查询异常事项' }).click()
  await expect(page.getByText(/原始记录 SYNTHETIC source amount missing/)).toBeVisible()
  await page.getByLabel('核实后的补正值').fill('5.75')
  await page.getByLabel('核实依据编号').fill('SYNTHETIC proof')
  await page.getByRole('button', { name: '保存诊所补正' }).click()
  await expect(page.getByRole('status')).toContainText('补正历史 1001 已保存')
  await page.getByLabel('办理入口').selectOption('review')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11,12')
  await page.getByRole('button', { name: '查询异常事项' }).click()
  await page.getByRole('button', { name: '执行一致性检查' }).click()
  await expect(page.getByRole('status')).toContainText('一致性检查已保存 · 复核通过')
  await expect(page.getByText(/原始记录 SYNTHETIC source amount missing/)).toBeVisible()
  expect(phase).toBe(2)
})

test('classroom smoke UC19 calculates profit and the incentive pool from one frozen preview', async ({ page }) => {
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (url.pathname === '/staff/quarterly-calculation') {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc19-csrf" content="SYNTHETIC-CSRF"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (req.method() !== 'POST' || url.pathname !== '/api/v1/staff/quarterly/calculations') return route.abort()
    expect(req.headers()['x-csrf-token']).toBe('SYNTHETIC-CSRF')
    expect(req.postDataJSON()).toEqual({ statisticId: '801', clinics: '11',
      calculationId: null, expectedVersion: null, pendingNotes: null })
    return route.fulfill({ json: {
      requestReference: 'UC/v1/UC19/SYNTHETIC/QUARTER_CALCULATE/' + req.headers()['idempotency-key'],
      calculation: { calculationId: '1001', version: 1, statisticId: '801', status: '待复核',
        netProfit: '30.25', incentivePool: '3.03', pendingNotes: null,
        basis: { quarter: 'SYNTH-Q3', sourceVersion: 'SYNTHETIC-v1', receivable: '45.00', received: '33.33',
          expense: '9.00', consumption: '5.75' } },
    } })
  })
  await page.goto('/staff/quarterly-calculation')
  await page.getByLabel('统计快照编号').fill('801')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  await page.getByRole('button', { name: '保存本次核算' }).click()
  await expect(page.getByRole('status')).toContainText('核算 1001 · 版本 1 · 待复核')
  await expect(page.getByText('净利润 30.25 元 · 激励资金池 3.03 元')).toBeVisible()
  await expect(page.getByLabel('原核算编号（新建时留空）')).toHaveValue('1001')
})

test('classroom smoke UC20 reviews a frozen version and downloads that version from UC19', async ({ page }) => {
  const basisHash = 'a'.repeat(64)
  const calculation = { calculationId: '1001', version: 1, statisticId: '801', clinics: ['11'],
    netProfit: '30.25', incentivePool: '3.03', generatedBy: 'SYNTHETIC-OPS',
    basis: { sourceVersion: 'SYNTHETIC-v1' }, source: { facts: [] },
    performance: [{ dentistId: '71', amount: '19.00', attributionBasis: 'SYNTHETIC' }], basisHash }
  let approved = false
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (['/staff/quarterly-financial-review', '/staff/quarterly-calculation'].includes(url.pathname)) {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html', body: html.replace('</head>',
        '<meta name="adc-uc20-csrf" content="TEST-FIN"><meta name="adc-uc19-csrf" content="TEST-OPS"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    expect(url.searchParams.get('version')).toBe('1')
    expect(url.searchParams.get('clinics')).toBe('11')
    const headers = request.headers()
    if (url.pathname === '/api/v1/staff/quarterly/financial-reviews/1001') {
      expect(headers['x-csrf-token']).toBe('TEST-FIN')
      if (request.method() === 'GET') return route.fulfill({ json: calculation })
      expect(request.method()).toBe('POST')
      expect(request.postDataJSON()).toEqual({ basisHash, notes: 'SYNTHETIC 已核对' })
      approved = true
      return route.fulfill({ json: { requestReference: 'UC/v1/UC20/SYNTHETIC/QUARTER_FINANCIAL_PASS/' + headers['idempotency-key'],
        review: { calculationId: '1001', version: 1, reviewId: '505', result: '通过' } } })
    }
    expect(url.pathname).toBe('/api/v1/staff/quarterly/calculations/1001/reviewed-report')
    expect(request.method()).toBe('POST')
    expect(headers['x-csrf-token']).toBe('TEST-OPS')
    expect(approved).toBe(true)
    return route.fulfill({ json: { requestReference: 'UC/v1/UC19/SYNTHETIC/QUARTER_REVIEWED_EXPORT/' + headers['idempotency-key'],
      report: { ...calculation, reviewId: '505' } } })
  })
  await page.goto('/staff/quarterly-financial-review')
  await page.getByLabel('核算编号').fill('1001')
  await page.getByLabel('核算版本').fill('1')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  await page.getByRole('button', { name: '读取本版本核算' }).click()
  await expect(page.getByText('净利润 30.25 元 · 激励资金池 3.03 元')).toBeVisible()
  await page.getByLabel('复核说明').fill('SYNTHETIC 已核对')
  await page.getByRole('button', { name: '确认复核通过' }).click()
  await expect(page.getByRole('status')).toContainText('复核编号：505')
  await page.goto('/staff/quarterly-calculation')
  await page.getByLabel('原核算编号（新建时留空）').fill('1001')
  await page.getByLabel('原核算版本（新建时留空）').fill('1')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  const [file] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出经财务复核的报告' }).click()])
  expect(file.suggestedFilename()).toBe('quarterly-report-1001-v1.json')
  const report = JSON.parse(await readFile(await file.path(), 'utf8'))
  expect([report.calculationId, report.version, report.reviewId]).toEqual(['1001', 1, '505'])
})

test('classroom smoke QUARTER-CHAIN passes a generated snapshot through calculation, financial review and export', async ({ page }) => {
  const basisHash = 'a'.repeat(64)
  const preview = { statisticId: '801', quarter: 'SYNTH-Q3', clinics: ['11'], readiness: '达到汇总要求',
    sourceVersion: 'SYNTHETIC-v1', manifestReference: 'quarterly_operating_statistic/801/source_basis',
    basis: { start: '2026-07-01T00:00:00Z', endExclusive: '2026-10-01T00:00:00Z', rules: 'SYNTHETIC' },
    receivable: '45.00', received: '33.33', expense: '9.00', consumption: '5.75',
    due: 2, noShow: 1, cancelled: 0, performance: [], issues: [] }
  const money = cents => (cents / 100).toFixed(2)
  const cents = value => Number(value.replace('.', ''))
  const net = cents(preview.receivable) - cents(preview.expense) - cents(preview.consumption)
  const calculation = { calculationId: '1001', version: 1, statisticId: preview.statisticId, clinics: preview.clinics,
    netProfit: money(net), incentivePool: money(Math.round(net / 10)), generatedBy: 'SYNTHETIC-OPS',
    basis: { quarter: preview.quarter, sourceVersion: preview.sourceVersion, manifestReference: preview.manifestReference }, source: { facts: [] },
    performance: [{ dentistId: '71', amount: '19.00', attributionBasis: 'SYNTHETIC' }], basisHash }
  let previewSaved = false
  let calculationSaved = false
  let approved = false
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (['/staff/quarterly-preview', '/staff/quarterly-financial-review', '/staff/quarterly-calculation'].includes(url.pathname)) {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html', body: html.replace('</head>',
        '<meta name="adc-uc17-csrf" content="TEST-OPS"><meta name="adc-uc20-csrf" content="TEST-FIN"><meta name="adc-uc19-csrf" content="TEST-OPS"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    const headers = request.headers()
    if (url.pathname === '/api/v1/staff/quarterly/previews') {
      expect(request.method()).toBe('POST')
      expect(headers['x-csrf-token']).toBe('TEST-OPS')
      expect(request.postDataJSON()).toEqual({ quarter: preview.quarter, clinics: preview.clinics })
      previewSaved = true
      return route.fulfill({ json: { requestReference: 'UC/v1/UC17/SYNTHETIC/QUARTERLY_PREVIEW_GENERATE/' +
        headers['idempotency-key'], preview } })
    }
    if (url.pathname === '/api/v1/staff/quarterly/calculations') {
      expect(request.method()).toBe('POST')
      expect(headers['x-csrf-token']).toBe('TEST-OPS')
      expect(previewSaved).toBe(true)
      expect(request.postDataJSON()).toEqual({ statisticId: preview.statisticId, clinics: preview.clinics.join(','),
        calculationId: null, expectedVersion: null, pendingNotes: null })
      calculationSaved = true
      return route.fulfill({ json: { requestReference: 'UC/v1/UC19/SYNTHETIC/QUARTER_CALCULATE/' +
        headers['idempotency-key'], calculation: { ...calculation, status: '待复核' } } })
    }
    expect(url.searchParams.get('version')).toBe('1')
    expect(url.searchParams.get('clinics')).toBe('11')
    if (url.pathname === '/api/v1/staff/quarterly/financial-reviews/1001') {
      expect(calculationSaved).toBe(true)
      expect(headers['x-csrf-token']).toBe('TEST-FIN')
      if (request.method() === 'GET') return route.fulfill({ json: calculation })
      expect(request.method()).toBe('POST')
      expect(request.postDataJSON()).toEqual({ basisHash, notes: 'SYNTHETIC 已核对' })
      approved = true
      return route.fulfill({ json: { requestReference: 'UC/v1/UC20/SYNTHETIC/QUARTER_FINANCIAL_PASS/' + headers['idempotency-key'],
        review: { calculationId: '1001', version: 1, reviewId: '505', result: '通过' } } })
    }
    expect(url.pathname).toBe('/api/v1/staff/quarterly/calculations/1001/reviewed-report')
    expect(request.method()).toBe('POST')
    expect(headers['x-csrf-token']).toBe('TEST-OPS')
    expect(approved).toBe(true)
    return route.fulfill({ json: { requestReference: 'UC/v1/UC19/SYNTHETIC/QUARTER_REVIEWED_EXPORT/' + headers['idempotency-key'],
      report: { ...calculation, reviewId: '505' } } })
  })
  await page.goto('/staff/quarterly-preview')
  await page.getByLabel('统计季度').fill(preview.quarter)
  await page.getByLabel('诊所编号（多个用逗号分隔）').fill(preview.clinics.join(','))
  await page.getByRole('button', { name: '生成统计预览' }).click()
  const snapshotReceipt = await page.getByRole('status').filter({ hasText: /统计快照 \d+ 已保存/ }).innerText()
  const snapshotId = snapshotReceipt.match(/统计快照 (\d+) 已保存/)[1]
  await page.goto('/staff/quarterly-calculation')
  await page.getByLabel('统计快照编号').fill(snapshotId)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill(preview.clinics.join(','))
  await page.getByRole('button', { name: '保存本次核算' }).click()
  await expect(page.getByRole('status')).toContainText('待复核')
  const calculationId = await page.getByLabel('原核算编号（新建时留空）').inputValue()
  const version = await page.getByLabel('原核算版本（新建时留空）').inputValue()

  await page.goto('/staff/quarterly-financial-review')
  await page.getByLabel('核算编号').fill(calculationId)
  await page.getByLabel('核算版本').fill(version)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  await page.getByRole('button', { name: '读取本版本核算' }).click()
  await expect(page.getByText('净利润 30.25 元 · 激励资金池 3.03 元')).toBeVisible()
  await page.getByLabel('复核说明').fill('SYNTHETIC 已核对')
  await page.getByRole('button', { name: '确认复核通过' }).click()
  await expect(page.getByRole('status')).toContainText('复核编号：505')
  await page.goto('/staff/quarterly-calculation')
  await page.getByLabel('原核算编号（新建时留空）').fill(calculationId)
  await page.getByLabel('原核算版本（新建时留空）').fill(version)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('11')
  const [file] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: '导出经财务复核的报告' }).click()])
  expect(file.suggestedFilename()).toBe('quarterly-report-1001-v1.json')
  const report = JSON.parse(await readFile(await file.path(), 'utf8'))
  expect([report.statisticId, report.calculationId, report.version, report.reviewId]).toEqual([snapshotId, calculationId, +version, '505'])
  expect(report.basis.manifestReference).toBe(preview.manifestReference)
})

test('classroom smoke VISIT-CHAIN carries one visit through treatment, invoice and simulated settlement', async ({ page }) => {
  const visit = '41', patient = '31', consent = '400'
  const paths = new Map([
    ['/staff/visit-treatment', ['09', 'TEST-DENTIST']],
    ['/staff/treatment-invoice', ['10', 'TEST-BILLING']],
    ['/staff/invoice-settlement', ['12', 'TEST-BILLING']],
  ])
  const api = '/api/v1/staff/visits/' + visit
  let treatment, invoice, payment
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (paths.has(url.pathname)) {
      const [uc, csrf] = paths.get(url.pathname)
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc' + uc + '-csrf" content="' + csrf + '"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    const receipt = (uc, op) => 'UC/v1/UC' + uc + '/SYNTHETIC/' + op + '/' + req.headers()['idempotency-key']
    const csrf = req.headers()['x-csrf-token']
    if (url.pathname === api + '/treatment') {
      expect(csrf).toBe('TEST-DENTIST')
      if (req.method() === 'GET') {
        expect(url.searchParams.get('patientId')).toBe(patient)
        return route.fulfill({ json: { patientId: patient, visitId: visit, consents: [
          { consentResultId: consent, proposedTreatment: 'SYNTHETIC treatment', consentResult: '同意' },
        ] } })
      }
      expect(req.method()).toBe('POST')
      const input = req.postDataJSON()
      expect(input).toMatchObject({ patientId: patient, consentResultId: consent, performed: true,
        project: 'SYNTHETIC treatment', unit: '次', quantity: '1.250' })
      treatment = { ...input, clinicalRecordId: '701', recordVersionNo: 1 }
      return route.fulfill({ json: { visitId: visit, clinicalRecordId: treatment.clinicalRecordId,
        recordVersionNo: treatment.recordVersionNo, requestReference: receipt('09', 'ACTUAL_TREATMENT_RECORD') } })
    }
    if (url.pathname === api + '/invoice') {
      expect(csrf).toBe('TEST-BILLING')
      expect(req.method()).toBe('POST')
      expect(treatment).toBeDefined()
      const cents = Math.round(Number(treatment.quantity) * 2666)
      const amount = (cents / 100).toFixed(2)
      invoice = { visitId: visit, invoiceId: '801', invoiceVersionNo: 1, status: '待核对确认', total: amount,
        lines: [{ clinicalRecordId: treatment.clinicalRecordId, recordVersionNo: treatment.recordVersionNo,
          item: treatment.project, unit: treatment.unit, quantity: treatment.quantity, unitPrice: '26.66', amount }] }
      return route.fulfill({ json: { requestReference: receipt('10', 'TREATMENT_INVOICE_GENERATE'), invoice } })
    }
    if (url.pathname === api + '/demo-settlement') {
      expect(csrf).toBe('TEST-BILLING')
      expect(req.method()).toBe('POST')
      expect(invoice).toBeDefined()
      const input = req.postDataJSON()
      expect(input).toMatchObject({ invoiceId: invoice.invoiceId, invoiceVersionNo: invoice.invoiceVersionNo })
      if (!payment) {
        expect(input).toMatchObject({ operation: 'DEMO_SETTLEMENT_START', paymentId: null,
          confirmation: { applicant: 'SYNTHETIC applicant', amount: invoice.total, basis: 'SYNTHETIC confirmed' } })
        payment = { paymentId: '901', visitId: visit, invoiceId: invoice.invoiceId,
          invoiceVersionNo: invoice.invoiceVersionNo, amount: invoice.total, status: '待核验', synthetic: true }
      } else {
        expect(input).toMatchObject({ operation: 'DEMO_SETTLEMENT_VERIFY', paymentId: payment.paymentId, confirmation: null })
        payment = { ...payment, status: '已结算', receipt: 'SYNTHETIC-UC12-' + payment.paymentId }
      }
      return route.fulfill({ json: { requestReference: receipt('12', input.operation), payment } })
    }
    return route.abort()
  })

  await page.goto('/staff/visit-treatment')
  await page.getByLabel('患者编号').fill(patient)
  await page.getByLabel('本次就诊编号').fill(visit)
  await page.getByRole('button', { name: '查询同意记录' }).click()
  await page.getByLabel('同意结果').selectOption(consent)
  await page.getByLabel('实施情况').selectOption('yes')
  await page.getByLabel('实际项目').fill('SYNTHETIC treatment')
  await page.getByLabel('计费单位').fill('次')
  await page.getByLabel('实际数量').fill('1.250')
  await page.getByRole('button', { name: '保存诊疗记录' }).click()
  await expect(page.getByRole('status')).toContainText('记录编号 701，版本 1')

  await page.goto('/staff/treatment-invoice')
  await page.getByLabel('本次就诊编号').fill(visit)
  await page.getByRole('button', { name: '生成或查看账单' }).click()
  const bill = page.getByRole('status').filter({ hasText: /账单 \d+，版本 \d+/ })
  const [, invoiceId, version] = (await bill.innerText()).match(/账单 (\d+)，版本 (\d+)/)
  const total = page.getByText(/^合计 \d+\.\d{2} 元$/)
  const [, amount] = (await total.innerText()).match(/合计 (\d+\.\d{2}) 元/)
  await expect(page.getByRole('row', { name: /SYNTHETIC treatment.*1.250.*26.66.*33.33/ })).toBeVisible()

  await page.goto('/staff/invoice-settlement')
  await page.getByLabel('就诊编号').fill(visit)
  await page.getByLabel('账单编号').fill(invoiceId)
  await page.getByLabel('当前账单版本').fill(version)
  await page.getByLabel('登记首版账单确认').check()
  await page.getByLabel('申请人').fill('SYNTHETIC applicant')
  await page.getByLabel('确认金额（元）').fill(amount)
  await page.getByLabel('确认依据').fill('SYNTHETIC confirmed')
  await page.getByRole('button', { name: '发起或查看模拟交易' }).click()
  await expect(page.getByRole('status')).toContainText('等待核验')
  await page.getByRole('button', { name: '核验并完成模拟结算' }).click()
  await expect(page.getByRole('status')).toHaveText('模拟结算完成')
  await expect(page.getByText('账单 ' + invoiceId + ' · 版本 ' + version + ' · ' + amount + ' 元')).toBeVisible()
  await expect(page.getByText('模拟凭据 SYNTHETIC-UC12-901')).toBeVisible()
})

test('classroom smoke STOCK-CHAIN carries one supply from purchase through stock inquiry', async ({ page }) => {
  const clinic = '11', supply = '101', initial = 4
  const screens = new Map([
    ['/staff/purchase-requirements', ['14', 'TEST-PROCUREMENT']],
    ['/staff/supply-receipt', ['15', 'TEST-PROCUREMENT']],
    ['/staff/supply-consumption', ['16', 'TEST-MANAGER']],
    ['/staff/stock-inquiry', ['13', 'TEST-MANAGER']],
  ])
  const base = '/api/v1/staff/clinics/' + clinic
  let balance = initial, requirement, receipt, consumption
  await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== 'https://adc-t033.invalid') return route.abort()
    if (screens.has(url.pathname)) {
      const [uc, csrf] = screens.get(url.pathname)
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      return route.fulfill({ contentType: 'text/html',
        body: html.replace('</head>', '<meta name="adc-uc' + uc + '-csrf" content="' + csrf + '"></head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
      contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
      body: await readFile(new URL(url.pathname.slice(1), bundle)),
    })
    if (req.method() !== 'POST') return route.abort()
    const csrf = req.headers()['x-csrf-token']
    const key = req.headers()['idempotency-key']
    expect(key).toMatch(/^[a-f0-9-]{36}$/)
    const reply = (uc, operation, name, result) => route.fulfill({ json: {
      requestReference: 'UC/v1/UC' + uc + '/SYNTHETIC/' + operation + '/' + key, [name]: result,
    } })
    const input = req.postDataJSON()
    if (url.pathname === base + '/purchase-requirements') {
      expect(csrf).toBe('TEST-PROCUREMENT')
      expect(input).toEqual({ supplyId: supply, quantity: '6', confirmationReference: 'SYNTHETIC-CONF-1' })
      requirement = { requirementId: '901', clinicId: clinic, supplyId: input.supplyId, quantity: input.quantity,
        status: '待联系供应商' }
      return reply('14', 'PURCHASE_REQUIREMENT_REGISTER', 'requirement', requirement)
    }
    if (requirement && url.pathname === base + '/purchase-requirements/' + requirement.requirementId + '/receipt') {
      expect(csrf).toBe('TEST-PROCUREMENT')
      expect(input).toMatchObject({ deliveryLookup: 'SYNTHETIC DELIVERY', accepted: true,
        received: requirement.quantity, cost: '12.40', expiry: '2027-04-05' })
      expect(receipt).toBeUndefined()
      balance += Number(input.received)
      receipt = { receiptId: '1001', clinicId: clinic, requirementId: requirement.requirementId, status: '已入库',
        received: input.received, cost: input.cost, expiry: input.expiry, stockBalanceAfter: String(balance) }
      return reply('15', 'SUPPLY_RECEIPT_RECORD', 'receipt', receipt)
    }
    if (url.pathname === base + '/supply-consumption') {
      expect(csrf).toBe('TEST-MANAGER')
      expect(receipt?.status).toBe('已入库')
      expect(consumption).toBeUndefined()
      expect(input).toMatchObject({ supplyId: requirement.supplyId, quantity: '2.5', proofReference: 'SYNTHETIC cost fact' })
      balance -= Number(input.quantity)
      consumption = { consumptionId: '811', clinicId: clinic, supplyId: input.supplyId, quantity: input.quantity,
        cost: '5.75', occurredAt: '2026-11-17T07:30:00Z', stockBalanceAfter: String(balance) }
      return reply('16', 'SUPPLY_CONSUMPTION_RECORD', 'consumption', consumption)
    }
    if (url.pathname === base + '/stock-inquiry') {
      expect(csrf).toBe('TEST-MANAGER')
      expect(input).toEqual({ supplyId: null })
      return reply('13', 'STOCK_INQUIRY', 'stock', { clinicId: clinic, supplyId: null,
        queriedAt: '2026-11-17T08:00:00Z', items: [{ supplyId: supply, name: 'SYNTHETIC gloves', unit: '盒',
          stockPresent: true, onHand: balance.toFixed(3), threshold: null, existingWarning: null }] })
    }
    return route.abort()
  })

  async function checkStock(expected) {
    await page.goto('/staff/stock-inquiry')
    await page.getByLabel('诊所编号').fill(clinic)
    await page.getByRole('button', { name: '查询库存' }).click()
    const row = page.getByRole('row', { name: /SYNTHETIC gloves/ })
    await expect(row.getByRole('cell', { name: expected + ' 盒', exact: true })).toBeVisible()
  }
  await checkStock('4.000')
  await page.goto('/staff/purchase-requirements')
  await page.getByLabel('诊所编号').fill(clinic)
  await page.getByLabel('用品编号').fill(supply)
  await page.getByLabel('采购需求数量').fill('6')
  await page.getByLabel('负责人确认依据编号').fill('SYNTHETIC-CONF-1')
  await page.getByRole('button', { name: '登记采购需求' }).click()
  await expect(page.getByRole('status')).toContainText('待联系供应商')
  const requirementId = await page.getByLabel('采购需求编号').inputValue()

  await page.goto('/staff/supply-receipt')
  await page.getByLabel('诊所编号').fill(clinic)
  await page.getByLabel('采购需求编号').fill(requirementId)
  await page.getByLabel('交付核对编号').fill('SYNTHETIC DELIVERY')
  await page.getByLabel('办理结果').selectOption('accepted')
  await page.getByLabel('实际验收情况').fill('SYNTHETIC acceptance verified')
  await page.getByLabel('验收实收量').fill('6')
  await page.getByLabel('采购成本（元）').fill('12.40')
  await page.getByLabel('有效期').fill('2027-04-05')
  await page.getByRole('button', { name: '保存验收结果' }).click()
  await expect(page.getByText('本次入库后库存 10')).toBeVisible()

  await page.goto('/staff/supply-consumption')
  await page.getByLabel('诊所编号').fill(clinic)
  await page.getByLabel('用品编号').fill(supply)
  await page.getByLabel('实际耗用量').fill('2.5')
  await page.getByLabel('实际发生时间（含时区偏移）').fill('2026-11-17T15:30:00+08:00')
  await page.getByLabel('已核实成本依据编号').fill('SYNTHETIC cost fact')
  await page.getByRole('button', { name: '保存实际耗用' }).click()
  await expect(page.getByText('实耗 2.5 · 实际成本 5.75 元 · 扣减后库存 7.5')).toBeVisible()
  await checkStock('7.500')
})

async function loginClassroom(page, account) {
  const password = process.env['ADC_DEMO_' + account.toUpperCase()]
  if (!password) throw new Error('Missing classroom test login configuration for ' + account)
  await page.getByLabel('员工账号').fill(account)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

test('classroom live INVOICE-QUESTION recalculates and reconfirms one invoice', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  const invoice = process.env.ADC_CLASSROOM_QUESTION_INVOICE_ID
  test.skip(!origin || !invoice, 'Requires the isolated UC11 classroom server')
  await page.goto(origin)
  await loginClassroom(page, 'billing')
  await page.getByRole('link', { name: '账单费用疑问' }).click()
  await page.getByLabel('患者编号').fill('88001')
  await page.getByLabel('就诊编号').fill('42')
  await page.getByLabel('账单编号').fill(invoice)
  await page.getByLabel('当前账单版本').fill('1')
  await page.getByRole('button', { name: '查询账单及疑问' }).click()
  await expect(page.getByRole('heading', { name: '账单 ' + invoice + ' · 版本 1' })).toBeVisible()
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC price question')
  await page.getByRole('button', { name: '登记疑问' }).click()
  await expect(page.getByRole('status')).toContainText('费用疑问已登记')
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC price review')
  await page.getByRole('button', { name: '按核实依据重算' }).click()
  await expect(page.getByText('待再次确认 · 合计 30.00 元')).toBeVisible()
  await expect(page.getByRole('heading', { name: '账单 ' + invoice + ' · 版本 2' })).toBeVisible()
  await page.getByLabel('疑问或核实说明').fill('SYNTHETIC applicant accepted this version')
  await page.getByLabel('实际确认的申请人').fill('SYNTHETIC applicant')
  await page.getByLabel('申请人确认的金额').fill('30.00')
  await page.getByRole('button', { name: '登记申请人再次确认' }).click()
  await expect(page.getByText('待结算 · 合计 30.00 元')).toBeVisible()
})

test('classroom live NO-SHOW confirms an elapsed appointment', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom server')
  await page.goto(origin)
  await loginClassroom(page, 'reception')
  await page.getByLabel('办理事项').selectOption('noShow')
  await page.getByLabel('预约编号', { exact: true }).fill('904')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('待到诊', { exact: true })).toBeVisible()
  await page.getByLabel('人工核实依据').fill('SYNTHETIC no-show verified')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByRole('status')).toHaveText('办理已确认。')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('已确认未到诊', { exact: true })).toBeVisible()
})

test('classroom live SCHEDULE saves a draft and separately activates that version', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom server')
  await page.goto(origin)
  await loginClassroom(page, 'manager')
  await page.getByRole('link', { name: '排班草案' }).click()
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByLabel('牙医编号').fill('79001')
  await page.getByLabel('开始时间').fill('2026-12-01T09:00:00+08:00')
  await page.getByLabel('结束时间').fill('2026-12-01T10:00:00+08:00')
  await page.getByRole('button', { name: '保存首次草案' }).click()
  const result = page.getByRole('status').filter({ hasText: /已保存待生效草案 \d+ \/ \d+/ })
  const [, scheduleId, version] = (await result.innerText()).match(/已保存待生效草案 (\d+) \/ (\d+)/)
  await page.getByRole('link', { name: '排班生效' }).click()
  await page.getByLabel('生效安排 ID').fill(scheduleId)
  await page.getByLabel('生效版本').fill(version)
  await page.getByRole('button', { name: '核对待生效版本' }).click()
  await page.getByRole('button', { name: '确认生效' }).click()
  await expect(page.getByText(`排班版本 ${version} 已确认生效。`)).toBeVisible()
})

test('classroom live QUARTER links a saved preview, independent review and report', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom server')
  await page.goto(origin)
  await loginClassroom(page, 'reception')
  await page.getByLabel('办理事项').selectOption('cancel')
  await page.getByLabel('预约编号', { exact: true }).fill('904')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('待到诊', { exact: true })).toBeVisible()
  await page.getByLabel('人工核实依据').fill('SYNTHETIC cancellation verified')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByRole('status')).toHaveText('办理已确认。')

  await page.goto(origin)
  await loginClassroom(page, 'manager')
  await page.getByRole('link', { name: '实际耗用' }).click()
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByLabel('用品编号').fill('101')
  await page.getByLabel('实际耗用量').fill('2.5')
  await page.getByLabel('实际发生时间（含时区偏移）').fill('2026-11-17T15:30:00+08:00')
  await page.getByLabel('已核实成本依据编号').fill('SYNTHETIC cost fact')
  await page.getByRole('button', { name: '保存实际耗用' }).click()
  await expect(page.getByText('实耗 2.5 · 实际成本 5.75 元 · 扣减后库存 1.5')).toBeVisible()

  await page.getByRole('link', { name: '异常补正' }).click()
  await page.getByLabel('统计快照编号').fill('18001')
  await page.getByLabel('异常事项编号').fill('18002')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('81001')
  await page.getByRole('button', { name: '查询异常事项' }).click()
  await page.getByLabel('核实后的补正值').fill('8.50')
  await page.getByLabel('核实依据编号').fill('SYNTHETIC proof')
  await page.getByRole('button', { name: '保存诊所补正' }).click()
  await expect(page.getByRole('heading', { name: '异常 18002 · 待复核' })).toBeVisible()

  await page.goto(origin)
  await loginClassroom(page, 'operations')
  await page.getByRole('link', { name: '异常复核' }).click()
  await page.getByLabel('统计快照编号').fill('18001')
  await page.getByLabel('异常事项编号').fill('18002')
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('81001')
  await page.getByLabel('办理入口').selectOption('review')
  await page.getByRole('button', { name: '查询异常事项' }).click()
  await page.getByRole('button', { name: '执行一致性检查' }).click()
  await expect(page.getByRole('heading', { name: '异常 18002 · 复核通过' })).toBeVisible()
  await page.getByRole('link', { name: '季度统计' }).click()
  await page.getByLabel('统计季度').fill('2026-Q4')
  await page.getByLabel('诊所编号（多个用逗号分隔）').fill('81001')
  await page.getByRole('button', { name: '生成统计预览' }).click()
  const snapshot = await page.getByRole('status').filter({ hasText: /统计快照 \d+ 已保存/ }).innerText()
  const statisticId = snapshot.match(/统计快照 (\d+) 已保存/)[1]
  await expect(page.locator('dt').filter({ hasText: /^取消预约$/ }).locator('+ dd')).toHaveText('1')
  await expect(page.locator('dt').filter({ hasText: /^应到诊$/ }).locator('+ dd')).toHaveText('2')
  await expect(page.locator('dt').filter({ hasText: /^运营费用$/ }).locator('+ dd')).toHaveText('8.50')
  await page.getByRole('link', { name: '季度核算' }).click()
  await page.getByLabel('统计快照编号').fill(statisticId)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('81001')
  await page.getByRole('button', { name: '保存本次核算' }).click()
  await expect(page.getByRole('status')).toContainText('待复核')
  const calculationId = await page.getByLabel('原核算编号（新建时留空）').inputValue()
  const version = await page.getByLabel('原核算版本（新建时留空）').inputValue()

  await page.goto(origin)
  await loginClassroom(page, 'finance')
  await page.getByLabel('核算编号').fill(calculationId)
  await page.getByLabel('核算版本').fill(version)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('81001')
  await page.getByRole('button', { name: '读取本版本核算' }).click()
  await expect(page.getByText('净利润 19.08 元 · 激励资金池 1.91 元')).toBeVisible()
  await page.getByLabel('复核说明').fill('SYNTHETIC 已核对')
  await page.getByRole('button', { name: '确认复核通过' }).click()
  const review = await page.getByRole('status').filter({ hasText: /复核编号：\d+/ }).innerText()
  const reviewId = review.match(/复核编号：(\d+)/)[1]

  await page.goto(origin)
  await loginClassroom(page, 'operations')
  await page.getByRole('link', { name: '季度核算' }).click()
  await page.getByLabel('原核算编号（新建时留空）').fill(calculationId)
  await page.getByLabel('原核算版本（新建时留空）').fill(version)
  await page.getByLabel('诊所范围（多个用逗号分隔）').fill('81001')
  const [file] = await Promise.all([page.waitForEvent('download'),
    page.getByRole('button', { name: '导出经财务复核的报告' }).click()])
  const report = JSON.parse(await readFile(await file.path(), 'utf8'))
  expect([report.statisticId, report.calculationId, report.version, report.reviewId])
    .toEqual([statisticId, calculationId, Number(version), reviewId])
  expect(report.performance).toHaveLength(1)
  expect(report.performance[0]).toMatchObject({ dentistId: '79001', amount: '33.33' })
})

test('classroom live STOCK carries procurement and manager actions through PostgreSQL', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom server')
  await page.goto(origin)
  const denied = await page.request.post(origin + '/demo/login', {
    headers: { 'Sec-Fetch-Site': 'same-origin' }, form: { account: 'procurement', password: 'invalid-fixture' }
  })
  expect(denied.status()).toBe(401)
  await loginClassroom(page, 'procurement')
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByLabel('用品编号').fill('101')
  await page.getByLabel('采购需求数量').fill('6')
  await page.getByLabel('负责人确认依据编号').fill('SYNTHETIC-CONF-1')
  await page.getByRole('button', { name: '登记采购需求' }).click()
  await expect(page.getByRole('status')).toContainText('待联系供应商')
  const demand = await page.getByLabel('采购需求编号').inputValue()
  await page.getByRole('link', { name: '验收入库' }).click()
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByLabel('采购需求编号').fill(demand)
  await page.getByLabel('交付核对编号').fill('SYNTHETIC DELIVERY')
  await page.getByLabel('办理结果').selectOption('accepted')
  await page.getByLabel('实际验收情况').fill('SYNTHETIC acceptance verified')
  await page.getByLabel('验收实收量').fill('6')
  await page.getByLabel('采购成本（元）').fill('12.40')
  await page.getByLabel('有效期').fill('2027-04-05')
  await page.getByRole('button', { name: '保存验收结果' }).click()
  await expect(page.getByText('本次入库后库存 10')).toBeVisible()

  await page.goto(origin)
  await loginClassroom(page, 'manager')
  await page.getByRole('link', { name: '实际耗用' }).click()
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByLabel('用品编号').fill('101')
  await page.getByLabel('实际耗用量').fill('2.5')
  await page.getByLabel('实际发生时间（含时区偏移）').fill('2026-11-17T15:30:00+08:00')
  await page.getByLabel('已核实成本依据编号').fill('SYNTHETIC cost fact')
  await page.getByRole('button', { name: '保存实际耗用' }).click()
  await expect(page.getByText('实耗 2.5 · 实际成本 5.75 元 · 扣减后库存 7.5')).toBeVisible()
  await page.getByRole('link', { name: '库存查询' }).click()
  await page.getByLabel('诊所编号').fill('81001')
  await page.getByRole('button', { name: '查询库存' }).click()
  await expect(page.getByRole('row', { name: /SYNTHETIC gloves/ })
    .getByRole('cell', { name: '7.500 盒', exact: true })).toBeVisible()
})

test('classroom live VISIT carries staff actions to Spring Boot and PostgreSQL', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom billing launcher')
  expect(origin).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)
  const roles = new Map([
    ['/staff/visit-consent', '08'], ['/staff/visit-treatment', '09'],
    ['/staff/treatment-invoice', '10'], ['/staff/invoice-settlement', '12'],
  ])
  const full = process.env.ADC_CLASSROOM_FULL_CHAIN === 'true'
  let visitId = '42'
  let patientId = '88001'
  const visit = () => '/api/v1/staff/visits/' + visitId
  const apiRoles = new Map([['/consent', 'dentist'], ['/treatment', 'dentist'],
    ['/invoice', 'billing'], ['/demo-settlement', 'billing']])
  if (!full) await page.route('**/*', async route => {
    const req = route.request()
    const url = new URL(req.url())
    if (url.origin !== origin) return route.abort()
    const role = apiRoles.get(url.pathname.slice(visit().length))
    if (url.pathname.startsWith(visit()) && role) {
      return route.continue({ headers: { ...req.headers(), 'x-test-classroom-role': role } })
    }
    if (roles.has(url.pathname)) {
      const html = await readFile(new URL('index.html', bundle), 'utf8')
      const meta = '<meta name="adc-uc' + roles.get(url.pathname) + '-csrf" content="SYNTHETIC-CSRF">'
      return route.fulfill({ contentType: 'text/html', body: html.replace('</head>', meta + '</head>') })
    }
    if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) {
      return route.fulfill({ contentType: url.pathname.endsWith('.css') ? 'text/css' : 'text/javascript',
        body: await readFile(new URL(url.pathname.slice(1), bundle)) })
    }
    return route.abort()
  })

  async function chooseRole(account) {
    await page.goto(origin + '/')
    await loginClassroom(page, account)
  }

  if (full) {
    await chooseRole('reception')
    const newPatient = process.env.ADC_CLASSROOM_PATIENT === 'true'
    if (newPatient) {
      await page.getByRole('link', { name: '患者建档' }).click()
      for (const [label, value] of Object.entries({
        '申请编号': process.env.ADC_CLASSROOM_REQUEST_ID, '申请版本': '0',
        '身份核验依据': 'SYNTHETIC classroom identity', '患者姓名': 'SYNTHETIC new patient',
        '患者识别信息': 'SYNTHETIC classroom new patient', '联系方式': 'classroom@example.invalid',
      })) await page.getByLabel(label, { exact: true }).fill(value)
      await page.getByRole('button', { name: '核验并建档' }).click()
      const receipt = page.getByRole('status').filter({ hasText: /患者编号 \d+/ })
      patientId = (await receipt.innerText()).match(/患者编号 (\d+)/)[1]
      expect(patientId).not.toBe('88001')
      await page.getByRole('link', { name: '预约与到诊' }).click()
    }
    await page.getByLabel('办理事项').selectOption('create')
    for (const [label, value] of Object.entries({
      '患者编号': patientId, '生效安排编号': '87001', '安排版本': '2',
      '开始时间（含偏移）': '2026-11-08T10:30:00Z', '结束时间（含偏移）': '2026-11-08T10:57:00Z',
      '诊疗需求': 'SYNTHETIC treatment', '主要项目编号': '85001', '已发布项目版本': '1',
      '已发布项目时长（分钟）': '27', '申请人确认依据': newPatient ? 'SYNTHETIC-new-patient-acceptance' : 'SYNTHETIC-acceptance',
    })) await page.getByLabel(label, { exact: true }).fill(value)
    await page.getByRole('button', { name: '提交办理' }).click()
    const booking = page.getByRole('status').filter({ hasText: /预约编号 \d+/ })
    const appointmentId = (await booking.innerText()).match(/预约编号 (\d+)/)[1]
    await expect(page.getByLabel('预约编号', { exact: true })).toHaveValue(appointmentId)
    if (process.env.ADC_CLASSROOM_MINI_READ === 'true') {
      const login = await page.request.post(origin + '/api/v1/mini/sessions/wechat', { data: { code: 'SYNTHETIC-code' } })
      expect(login.status()).toBe(200)
      const { accessToken } = await login.json()
      const headers = { Authorization: 'Bearer ' + accessToken }
      const grants = await page.request.get(origin + '/api/v1/mini/authorizations', { headers })
      expect(grants.status()).toBe(200)
      expect((await grants.json()).items).toMatchObject([{ patientId, kind: 'SELF', status: 'ACTIVE' }])
      const own = await page.request.get(origin + '/api/v1/mini/appointments', { headers, params: { patientId } })
      expect(own.status()).toBe(200)
      expect((await own.json()).items).toMatchObject([{
        patientId, appointmentId, formalStatus: '待到诊', factVerification: 'VERIFIED',
      }])
      const other = await page.request.get(origin + '/api/v1/mini/appointments', { headers, params: { patientId: '88001' } })
      expect(other.status()).toBe(403)
    }
    if (process.env.ADC_CLASSROOM_CHANGE === 'true') {
      await page.getByLabel('办理事项').selectOption('change')
      await page.getByRole('button', { name: '查询预约' }).click()
      await expect(page.getByRole('status')).toHaveText('预约已读取。')
      for (const [label, value] of Object.entries({
        '生效安排编号': '87001', '安排版本': '2', '开始时间（含偏移）': '2026-11-08T11:00:00Z',
        '结束时间（含偏移）': '2026-11-08T11:27:00Z', '诊疗需求': 'SYNTHETIC treatment',
        '主要项目编号': '85001', '改约理由': 'SYNTHETIC patient changed time',
        '申请人确认依据': 'SYNTHETIC-change-acceptance',
      })) await page.getByLabel(label, { exact: true }).fill(value)
      await expect(page.getByTestId('duration-basis')).toContainText('沿用原预约版本 1')
      const saved = page.waitForResponse(r => r.url() === origin + '/api/v1/staff/appointments/' + appointmentId + '/changes')
      await page.getByRole('button', { name: '提交办理' }).click()
      const response = await saved
      expect(response.status()).toBe(200)
      expect((await response.json()).links).toMatchObject({ appointmentId, appointmentChangeId: expect.stringMatching(/^\d+$/) })
      await expect(page.getByRole('status')).toHaveText('办理已确认。')
      return
    }
    await page.getByLabel('办理事项').selectOption('arrival')
    await page.getByRole('button', { name: '查询预约' }).click()
    await expect(page.getByRole('status')).toHaveText('预约已读取。')
    await page.getByLabel('人工核实依据').fill('SYNTHETIC human evidence')
    await page.getByLabel('已核实身份依据').fill('SYNTHETIC patient verified')
    await page.getByLabel('实际诊所编号').fill('81001')
    await page.getByLabel('实际牙医编号').fill('79001')
    await page.getByRole('button', { name: '提交办理' }).click()
    const arrival = page.getByRole('status').filter({ hasText: /就诊编号 \d+/ })
    visitId = (await arrival.innerText()).match(/就诊编号 (\d+)/)[1]
    expect(visitId).not.toBe('42')
    await chooseRole('dentist')
    await page.getByRole('link', { name: '健康资料' }).click()
    await page.getByLabel('患者编号').fill(patientId)
    await page.getByLabel('本次就诊编号').fill(visitId)
    await page.getByRole('button', { name: '查询资料' }).click()
    if (newPatient) {
      await expect(page.getByText('授权范围内暂无既往诊疗记录。')).toBeVisible()
      await expect(page.getByLabel('病史')).toHaveValue('')
    } else {
      await expect(page.getByText(/SYNTHETIC current treatment · 已实施/)).toBeVisible()
      await expect(page.getByLabel('病史')).toHaveValue('SYNTHETIC prior history')
    }
    await page.getByLabel('过敏信息').fill('SYNTHETIC penicillin allergy')
    await page.getByLabel('变更及核实说明').fill('SYNTHETIC verified update')
    await page.getByRole('button', { name: '保存已核实变更' }).click()
    await expect(page.getByRole('status')).toContainText(/记录编号 \d+/)
    await expect(page.getByLabel('过敏信息')).toHaveValue('SYNTHETIC penicillin allergy')
    await page.getByRole('link', { name: '诊疗同意' }).click()
  } else {
    await page.goto(origin + '/staff/visit-consent')
  }
  await page.getByLabel('患者编号').fill(patientId)
  await page.getByLabel('本次就诊编号').fill(visitId)
  await page.getByRole('button', { name: '查询沟通记录' }).click()
  await page.getByLabel('实际沟通对象').fill('SYNTHETIC patient')
  await page.getByLabel('拟治疗内容及范围').fill('SYNTHETIC treatment')
  await page.getByLabel('实际同意结论').selectOption('同意')
  const consentSaved = page.waitForResponse(r => r.url() === origin + visit() + '/consent' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '保存同意结果' }).click()
  const consent = await consentSaved
  expect(consent.status()).toBe(200)
  const { consentResultId } = await consent.json()
  await expect(page.getByRole('status')).toContainText('记录编号 ' + consentResultId)

  await page.goto(origin + '/staff/visit-treatment')
  await page.getByLabel('患者编号').fill(patientId)
  await page.getByLabel('本次就诊编号').fill(visitId)
  await page.getByRole('button', { name: '查询同意记录' }).click()
  await page.getByLabel('同意结果').selectOption(consentResultId)
  await page.getByLabel('实施情况').selectOption('yes')
  await page.getByLabel('实际项目').fill('SYNTHETIC treatment')
  await page.getByLabel('计费单位').fill('次')
  await page.getByLabel('实际数量').fill('1.250')
  await page.getByRole('button', { name: '保存诊疗记录' }).click()
  await expect(page.getByRole('status')).toContainText(/记录编号 \d+，版本 1/)

  const openInvoice = async () => {
    await page.goto(origin + '/staff/treatment-invoice')
    await page.getByLabel('本次就诊编号').fill(visitId)
    await page.getByRole('button', { name: '生成或查看账单' }).click()
    const bill = page.getByRole('status').filter({ hasText: /账单 \d+，版本 \d+/ })
    return (await bill.innerText()).match(/账单 (\d+)，版本 (\d+)/).slice(1)
  }
  if (full) await chooseRole('billing')
  const [invoiceId, version] = await openInvoice()
  await expect(page.getByText('合计 33.33 元')).toBeVisible()

  await page.goto(origin + '/staff/invoice-settlement')
  await page.getByLabel('就诊编号').fill(visitId)
  await page.getByLabel('账单编号').fill(invoiceId)
  await page.getByLabel('当前账单版本').fill(version)
  await page.getByLabel('登记首版账单确认').check()
  await page.getByLabel('申请人').fill('SYNTHETIC patient')
  await page.getByLabel('确认金额（元）').fill('33.33')
  await page.getByLabel('确认依据').fill('SYNTHETIC confirmed')
  await page.getByRole('button', { name: '发起或查看模拟交易' }).click()
  await expect(page.getByRole('status')).toContainText('等待核验')
  await page.getByRole('button', { name: '核验并完成模拟结算' }).click()
  await expect(page.getByRole('status')).toHaveText('模拟结算完成')
  await expect(page.getByText(/模拟凭据 SYNTHETIC-UC12-\d+/)).toBeVisible()
  expect(await openInvoice()).toEqual([invoiceId, version])
  await expect(page.getByText('已结算', { exact: true })).toBeVisible()
})

test('classroom live CANCEL cancels and reloads a persisted appointment', async ({ page }) => {
  const origin = process.env.ADC_CLASSROOM_HTTP
  test.skip(!origin, 'Requires the isolated classroom cancellation launcher')
  expect(origin).toMatch(/^http:\/\/127\.0\.0\.1:\d+$/)
  await page.goto(origin + '/')
  await loginClassroom(page, 'reception')
  await page.getByLabel('办理事项').selectOption('cancel')
  await page.getByLabel('预约编号', { exact: true }).fill('904')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('待到诊', { exact: true })).toBeVisible()
  await page.getByLabel('人工核实依据').fill('SYNTHETIC cancellation verified')
  await page.getByRole('button', { name: '提交办理' }).click()
  await expect(page.getByRole('status')).toHaveText('办理已确认。')
  await page.reload()
  await page.getByLabel('预约编号', { exact: true }).fill('904')
  await page.getByRole('button', { name: '查询预约' }).click()
  await expect(page.getByText('已取消', { exact: true })).toBeVisible()
})
