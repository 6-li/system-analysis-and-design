const { chromium } = require('@playwright/test')
const assert = require('node:assert/strict')

async function run() {
  const origin = process.env.ADC_RESOLUTION_TEST_ORIGIN
  assert.match(origin, /^http:\/\/127\.0\.0\.1:[1-9]\d*$/)
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  try {
    const page = await browser.newPage()
    await page.route('**/*', route => new URL(route.request().url()).origin === origin
      ? route.continue({ headers: { ...route.request().headers(),
        'X-Synthetic-Test': process.env.ADC_RESOLUTION_TEST_SECRET } }) : route.abort())
    await page.goto(origin + '/staff/schedule-resolutions')
    if (process.env.ADC_RESOLUTION_TEST_MODE === 'create-activate') {
      await page.goto(origin + '/staff/schedule-workspace')
      await page.getByLabel('诊所编号').fill('81002')
      await page.getByLabel('牙医编号').fill('79001')
      await page.getByLabel('开始时间').fill('2026-11-08T16:00:00Z')
      await page.getByLabel('结束时间').fill('2026-11-08T17:00:00Z')
      const created = page.waitForResponse(response => response.url().endsWith('/schedule-drafts'))
      await page.getByRole('button', { name: '保存首次草案' }).click()
      const draft = await created
      assert.equal(draft.status(), 200, await draft.text())
      const ref = (await draft.json()).links.scheduleRef
      assert.deepEqual(ref, { scheduleId: '88000', versionNo: 1 })
      await page.goto(origin + '/staff/schedule-resolutions')
      return await activate(page, ref, false)
    }
    if (process.env.ADC_RESOLUTION_TEST_MODE === 'activation') {
      return await activate(page)
    }
    await page.getByLabel('安排 ID', { exact: true }).fill('87001')
    await page.getByLabel('版本', { exact: true }).fill('3')
    await page.getByRole('button', { name: '读取影响和历史' }).click()
    await page.getByRole('combobox', { name: '预约', exact: true }).selectOption('901')
    await page.getByLabel('处理主题').fill('SYNTHETIC browser')
    await page.getByLabel('处理依据').fill('SYNTHETIC pending')
    const saved = page.waitForResponse(response => response.url().endsWith('/resolutions'))
    await page.getByRole('button', { name: '保存处理记录' }).click()
    const reply = await saved
    assert.equal(reply.status(), 200, await reply.text())
    const id = (await reply.json()).links.resolutionId
    await page.getByRole('heading', { name: '原始记录 ' + id }).waitFor()
    console.log('EDGE_MVC_PG_RESOLUTION_SAVED_AND_READ', id)
  } finally {
    await browser.close()
  }
}

async function activate(page, target = { scheduleId: '87002', versionNo: 2 }, loseReply = true) {
  let posts = 0
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/activations')) posts++
  })
  await page.getByLabel('生效安排 ID').fill(target.scheduleId)
  await page.getByLabel('生效版本').fill(String(target.versionNo))
  await page.getByRole('button', { name: '核对待生效版本' }).click()
  const submitted = page.waitForResponse(response => response.url().endsWith('/activations'))
  await page.getByRole('button', { name: '确认生效' }).click()
  const uncertain = await submitted
  if (!loseReply) {
    assert.equal(uncertain.status(), 200, await uncertain.text())
    assert.deepEqual((await uncertain.json()).links.scheduleRef, target)
    await page.getByText('排班版本 ' + target.versionNo + ' 已确认生效。', { exact: true }).waitFor()
    assert.equal(posts, 1)
    return
  }
  assert.equal(uncertain.status(), 202, await uncertain.text())
  const reference = (await uncertain.json()).requestReference
  await page.getByRole('button', { name: '查询原提交结果' }).waitFor()
  await page.reload()
  const recovered = page.waitForResponse(response => response.url().includes('/schedule-commands/'))
  await page.getByRole('button', { name: '查询原提交结果' }).click()
  const reply = await recovered
  assert.equal(reply.status(), 200, await reply.text())
  const result = await reply.json()
  assert.equal(result.requestReference, reference)
  assert.deepEqual(result.links.scheduleRef, { scheduleId: '87002', versionNo: 2 })
  await page.getByText('排班版本 2 已确认生效。', { exact: true }).waitFor()
  assert.equal(posts, 1)
  console.log('EDGE_MVC_PG_ACTIVATION_RECOVERED_WITHOUT_REPOST')
}

run().catch(error => { console.error(error); process.exitCode = 1 })
