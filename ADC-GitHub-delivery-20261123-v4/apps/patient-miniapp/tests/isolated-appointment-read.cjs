const assert = require('node:assert/strict');
const { readFile } = require('node:fs/promises');
const path = require('node:path');
const { createHttpClient } = require('../miniprogram/services/http.js');
const { createAppointmentRequests } = require('../miniprogram/services/appointment-requests.js');

async function verify() {
  const [port, requestId, actorScope, existingAppointment, operation] = process.argv.slice(2);
  const origin = 'http://127.0.0.1:' + port;
  const probe = process.env.ADC_MINI_READ_PROBE;
  const appointmentId = await bookInStaffPage(origin, probe, requestId, actorScope, existingAppointment, operation);
  const client = createAppointmentRequests(createHttpClient({
    baseUrl: origin,
    allowLocalHttp: true,
    async request(options) {
      try {
        const response = await fetch(options.url, {
          method: options.method,
          headers: { ...options.header, 'X-Synthetic-Test': probe },
          body: options.data === undefined ? undefined : JSON.stringify(options.data),
        });
        options.success({ statusCode: response.status, data: await response.json() });
      } catch (failure) {
        options.fail(failure);
      }
    },
  }));
  await client.login('SYNTHETIC-code');
  assert.deepEqual(await client.appointmentPatients(), [{ patientId: '88001', kind: 'SELF' }]);
  const result = await client.appointments('88001');
  const booking = result.items.find(item => item.appointmentId === appointmentId);
  assert.equal(booking?.factVerification, 'VERIFIED');
  if (existingAppointment) {
    assert.equal(appointmentId, existingAppointment);
    if (operation === 'cancel') assert.equal(booking.formalStatus, '已取消');
    else assert.equal(booking.startsAt, '2026-11-08T10:00:00Z');
  }
  await assert.rejects(client.appointments('88002'), { status: 403 });
  const useCase = existingAppointment ? (operation === 'cancel' ? 'UC04' : 'UC03') : 'UC02';
  console.log(useCase + '_MINI_CLIENT_READBACK_PASS');
}

async function bookInStaffPage(origin, probe, requestId, actorScope, existingAppointment, operation) {
  const { chromium } = require('../../staff-web/node_modules/playwright');
  const bundle = path.resolve(__dirname, '../../../.tools/uc02-staff-source-live');
  const browser = await chromium.launch({ channel: 'msedge' });
  try {
    const page = await browser.newPage();
    const staffRoot = '/api/v1/staff/appointments';
    const detail = staffRoot + '/' + existingAppointment;
    const cancel = operation === 'cancel';
    const change = !!existingAppointment && !cancel;
    const write = existingAppointment ? detail + (cancel ? '/cancellations' : '/changes') : staffRoot;
    const reference = cancel ? 'SYNTHETIC cancellation source'
      : change ? 'SYNTHETIC-change-source' : 'SYNTHETIC-verified-context';
    const source = existingAppointment
      ? { [cancel ? 'verifiedCancelSource' : 'verifiedChangeSource']: { requestId, reference, appointmentId: existingAppointment } }
      : { verifiedBookingSource: { requestId, reference, patientId: '88001' } };
    await page.addInitScript(context => { globalThis.syntheticAppointmentContext = context; }, {
      actions: [cancel ? 'cancel' : change ? 'change' : 'create'], canRead: !!existingAppointment,
      csrfToken: 'SYNTHETIC-CSRF', requestActorScope: actorScope, ...source,
    });
    await page.route('**/*', async route => {
      const req = route.request();
      const url = new URL(req.url());
      if (url.origin !== origin) return route.abort();
      if (url.pathname === '/') return route.fulfill({
        contentType: 'text/html',
        body: await readFile(path.join(bundle, 'browser-tests/fixtures/appointments.html')),
      });
      if (/^\/assets\/[\w-]+\.(js|css)$/.test(url.pathname)) return route.fulfill({
        contentType: url.pathname.endsWith('.js') ? 'text/javascript' : 'text/css',
        body: await readFile(path.join(bundle, url.pathname.slice(1))),
      });
      if ((url.pathname === write && req.method() === 'POST') ||
          (existingAppointment && [detail, detail + '/schedule-support'].includes(url.pathname) && req.method() === 'GET')) {
        return route.continue({ headers: { ...req.headers(), 'X-Synthetic-Test': probe } });
      }
      return route.abort();
    });
    await page.goto(origin, { timeout: 10000 });
    if (existingAppointment) {
      await page.getByRole('button', { name: '查询预约' }).click({ timeout: 5000 });
      await page.getByText('预约已读取。').waitFor({ timeout: 5000 });
    }
    const fields = cancel ? [['人工核实依据', 'SYNTHETIC cancellation']] : [
      ['生效安排编号', '87001'], ['安排版本', '2'],
      ['开始时间（含偏移）', change ? '2026-11-08T10:00:00Z' : '2026-11-08T09:30:00Z'],
      ['结束时间（含偏移）', change ? '2026-11-08T10:27:00Z' : '2026-11-08T09:57:00Z'],
      ['诊疗需求', 'SYNTHETIC request'], ['主要项目编号', '85001'],
      ...(change ? [['改约理由', 'SYNTHETIC change']] : [
        ['已发布项目版本', '1'], ['已发布项目时长（分钟）', '27'],
      ]),
      ['申请人确认依据', 'SYNTHETIC acceptance'],
    ];
    for (const [label, value] of fields) await page.getByLabel(label, { exact: true }).fill(value, { timeout: 5000 });
    const reply = page.waitForResponse(response => response.url() === origin + write,
      { timeout: 10000 });
    await page.getByRole('button', { name: '提交办理' }).click({ timeout: 5000 });
    const response = await reply;
    assert.equal(response.status(), 200);
    const body = await response.json();
    await page.getByText('办理已确认。').waitFor({ timeout: 5000 });
    assert.equal(response.request().postDataJSON().sourceContextReference, reference);
    return body.links.appointmentId;
  } finally {
    await browser.close();
  }
}

verify().catch(failure => {
  console.error(failure);
  process.exitCode = 1;
});
