const test = require('node:test');
const assert = require('node:assert/strict');

test('preliminary page can submit and recover after reload without replay', async () => {
  let definition;
  const storage = new Map();
  const calls = [];
  global.Page = value => { definition = value; };
  global.getApp = () => ({ globalData: { miniRequestConfig: { baseUrl: 'https://adc.test', syntheticLogin: true } } });
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    login: ({ success }) => success({ code: 'SYNTHETIC-code' }),
    getRandomValues: ({ success }) => success({ randomValues: new Uint8Array(16).buffer }),
    request(options) {
      calls.push(options);
      if (options.url.endsWith('/sessions/wechat')) return options.success({ statusCode: 200,
        data: { accessToken: 'SYNTHETIC-token', identityVerified: false } });
      if (options.method === 'POST') return options.fail({ errMsg: 'timeout' });
      return options.success({ statusCode: 200, data: { operation: 'op008', receiptStatus: 'CONFIRMED_RECEIVED',
        submissionKey: '0'.repeat(32), requestId: 'SYNTHETIC-request' } });
    },
  };
  try {
    require('../miniprogram/pages/index/index.js');
    function page() {
      const instance = { ...definition, data: { ...definition.data },
        setData(values) { Object.assign(this.data, values); } };
      instance.onLoad();
      return instance;
    }
    const first = page();
    await first.login();
    assert.equal(first.data.screen, 'home');
    assert.equal(first.data.clinic, '');
    await first.submit();
    assert.equal(calls.length, 1);
    await first.openBooking();
    assert.equal(first.data.screen, 'booking');
    for (const [field, value] of Object.entries({ patient: 'SYNTHETIC patient', applicant: 'SYNTHETIC applicant',
      contact: 'SYNTHETIC contact', clinic: '81001', date: '2026-11-16', need: 'SYNTHETIC need' })) {
      first.edit({ currentTarget: { dataset: { field } }, detail: { value } });
    }
    assert.equal(first.data.clinic, '81001');
    assert.deepEqual(first.data.clinics.map(item => item.clinicId), ['81001', '81002']);
    first.selectClinic({ currentTarget: { dataset: { id: 81002 } } });
    assert.equal(first.data.clinic, '81002');
    await first.submit();
    assert.equal(first.data.pending, '0'.repeat(32));
    assert.match(first.data.message, /待核对/);
    const reloaded = page();
    await reloaded.login();
    assert.equal(reloaded.data.screen, 'home');
    await reloaded.submit();
    await reloaded.openAppointments();
    assert.equal(reloaded.data.screen, 'appointments');
    await reloaded.lookup();
    assert.equal(reloaded.data.pending, '');
    assert.deepEqual(reloaded.data.receivedRequests, ['SYNTHETIC-request']);
    assert.match(reloaded.data.message, /等待接待人员核验/);
    assert.equal(calls.filter(op => op.url.endsWith('/requests')).length, 1);
    assert.equal(calls.find(op => op.url.endsWith('/requests')).data.intendedClinicId, '81002');
    assert.equal(calls.at(-1).method, 'GET');
  } finally {
    delete global.wx;
    delete global.Page;
    delete global.getApp;
  }
});

test('appointment workspace reads only a selected authorized patient and clears results on denial', async () => {
  let definition;
  const urls = [];
  let denied = false;
  let submitted;
  const modulePath = require.resolve('../miniprogram/pages/index/index.js');
  delete require.cache[modulePath];
  global.Page = value => { definition = value; };
  global.getApp = () => ({ globalData: { miniRequestConfig: {
    baseUrl: 'https://adc.test', syntheticLogin: true, changeRequestsEnabled: true, cancelRequestsEnabled: true,
  } } });
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    removeStorageSync() {},
    getRandomValues: ({ success }) => success({ randomValues: new Uint8Array(16).buffer }),
    request({ url, header, success, data }) {
      urls.push(url);
      if (url.endsWith('/sessions/wechat')) return success({ statusCode: 200,
        data: { accessToken: 'SYNTHETIC-token', identityVerified: false } });
      assert.equal(header.Authorization, 'Bearer SYNTHETIC-token');
      if (url.endsWith('/requests')) {
        submitted = data;
        return success({ statusCode: 201, data: { receiptStatus: 'CONFIRMED_RECEIVED', operation: 'op008',
          submissionKey: '0'.repeat(32), requestId: 'SYNTHETIC-change' } });
      }
      if (url.endsWith('/authorizations')) return success({ statusCode: 200, data: { items: [
        { status: 'ACTIVE', kind: 'SELF', patientId: '88001' },
        { status: 'ACTIVE', kind: 'GUARDIAN', patientId: '88002' },
        { status: 'REVOKED', kind: 'GUARDIAN', patientId: '88003' },
      ] } });
      if (denied) return success({ statusCode: 403, data: {} });
      const patientId = url.includes('patientId=88001') ? '88001' : '88002';
      const next = url.includes('cursor=');
      return success({ statusCode: 200, data: { nextCursor: next ? null : '91', items: [{
        patientId, appointmentId: next ? '92' : '91', formalStatus: '待到诊',
        startsAt: '2026-11-18T08:00:00Z', endsAt: '2026-11-18T08:30:00Z',
        factVerification: next ? 'PENDING_VERIFICATION' : 'VERIFIED',
      }] } });
    },
  };
  try {
    require(modulePath);
    const page = { ...definition, data: { ...definition.data },
      setData(values) { Object.assign(this.data, values); } };
    const select = id => page.selectAppointmentPatient({ currentTarget: { dataset: { id } } });
    page.onLoad();
    await page.login();
    await page.openAppointments();
    assert.deepEqual(page.data.appointmentPatients.map(row => row.patientId), ['88001', '88002']);
    await select('88003');
    assert.equal(urls.length, 2);
    await select('88002');
    assert.equal(page.data.formalAppointments[0].statusLabel, '待到诊');
    assert.equal(page.data.formalAppointments[0].canRequestChange, false);
    assert.equal(page.data.formalAppointments[0].canRequestCancel, false);
    await page.loadMoreAppointments();
    assert.deepEqual(page.data.formalAppointments.map(row => row.statusLabel), ['待到诊', '预约状态待核对']);
    assert.equal(page.data.formalAppointments[1].intervalLabel, '');
    denied = true;
    await select('88002');
    assert.deepEqual(page.data.formalAppointments, []);
    assert.match(page.data.appointmentNotice, /暂时无法读取/);
    denied = false;
    await select('88001');
    page.openChange({ currentTarget: { dataset: { id: '91' } } });
    assert.equal(page.data.screen, 'change');
    for (const [field, value] of Object.entries({ patient: 'SYNTHETIC patient', applicant: 'SYNTHETIC applicant',
      contact: 'SYNTHETIC contact', date: '2026-11-19' })) {
      page.edit({ currentTarget: { dataset: { field } }, detail: { value } });
    }
    await page.submit();
    assert.equal(submitted.type, 'CHANGE');
    assert.equal(submitted.originalAppointmentId, '91');
    assert.equal(submitted.patientId, '88001');
    assert.match(page.data.message, /等待接待人员核验/);
    await select('88001');
    page.openCancel({ currentTarget: { dataset: { id: '91' } } });
    assert.equal(page.data.screen, 'cancel');
    for (const [field, value] of Object.entries({ patient: 'SYNTHETIC patient', applicant: 'SYNTHETIC applicant',
      contact: 'SYNTHETIC contact', need: 'SYNTHETIC reason' })) {
      page.edit({ currentTarget: { dataset: { field } }, detail: { value } });
    }
    await page.submit();
    assert.equal(submitted.type, 'CANCEL');
    assert.equal(submitted.originalAppointmentId, '91');
    assert.equal(submitted.cancellationReason, 'SYNTHETIC reason');
    assert.equal(Object.hasOwn(submitted, 'preferred'), false);
    assert.match(page.data.message, /等待接待人员核验/);
  } finally {
    delete require.cache[modulePath];
    delete global.wx;
    delete global.Page;
    delete global.getApp;
  }
});

