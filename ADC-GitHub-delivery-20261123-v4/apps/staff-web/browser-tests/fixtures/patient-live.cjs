const assert = require('node:assert/strict')
const { chromium } = require('@playwright/test')
const { randomBytes } = require('node:crypto')

const origin = process.env.ADC_RESOLUTION_TEST_ORIGIN
assert.match(origin, /^http:\/\/127\.0\.0\.1:[1-9]\d*$/)
const secret = process.env.ADC_RESOLUTION_TEST_SECRET

async function verify() {
  let definition
  const storage = new Map()
  global.Page = value => { definition = value }
  global.getApp = () => ({ globalData: { miniRequestConfig: { baseUrl: origin, allowLocalHttp: true, syntheticLogin: true } } })
  global.wx = {
    login: ({ success }) => success({ code: 'SYNTHETIC-code' }),
    getRandomValues: ({ success }) => success({ randomValues: Uint8Array.from(randomBytes(16)).buffer }),
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    async request(options) {
      try {
        assert.equal(new URL(options.url).origin, origin)
        const reply = await fetch(options.url, { method: options.method,
          headers: { ...options.header, 'X-Synthetic-Test': secret }, redirect: 'error',
          body: options.data === undefined ? undefined : JSON.stringify(options.data) })
        options.success({ statusCode: reply.status, data: await reply.json() })
      } catch { options.fail({ errMsg: 'synthetic transport failed' }) }
    },
  }
  require('../../../patient-miniapp/miniprogram/pages/index/index.js')
  const mini = { ...definition, data: { ...definition.data },
    setData(values) { Object.assign(this.data, values) } }
  mini.onLoad()
  await mini.login()
  assert.equal(mini.data.screen, 'home')
  await mini.openBooking()
  for (const [field, value] of Object.entries({ patient: 'SYNTHETIC-UC01-identity',
    applicant: 'SYNTHETIC applicant', contact: 'SYNTHETIC contact', clinic: '81001',
    date: '2026-11-16', need: 'SYNTHETIC need' })) {
    mini.edit({ currentTarget: { dataset: { field } }, detail: { value } })
  }
  mini.recommendDemoClinic()
  await mini.submit()
  const requestId = mini.data.message.match(/申请编号 ([a-f0-9-]{36})$/)?.[1]
  assert.ok(requestId, mini.data.message)
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const page = await browser.newPage()
    await page.route('**/*', route => new URL(route.request().url()).origin === origin
      ? route.continue({ headers: { ...route.request().headers(), 'X-Synthetic-Test': secret } }) : route.abort())
    await page.goto(origin + '/staff/patients')
    for (const [label, value] of [['申请编号', requestId], ['申请版本', '0'], ['身份核验依据', 'SYNTHETIC'],
      ['患者姓名', 'SYNTHETIC name'], ['患者识别信息', 'SYNTHETIC-UC01-identity'], ['联系方式', 'SYNTHETIC contact']]) {
      await page.getByLabel(label).fill(value)
    }
    const saved = page.waitForResponse(response => response.url().endsWith('/api/v1/staff/patients'))
    await page.getByRole('button', { name: '核验并建档' }).click()
    const reply = await saved
    const result = await reply.json()
    assert.equal(reply.status(), 201, JSON.stringify(result))
    assert.equal(result.sourceAssociation.requestId, requestId)
    assert.equal(result.sourceAssociation.patientId, '91001')
    await page.getByText('已建档并关联申请，患者编号 91001').waitFor()
  } finally { await browser.close() }
}

verify().catch(error => { console.error(error); process.exitCode = 1 })

