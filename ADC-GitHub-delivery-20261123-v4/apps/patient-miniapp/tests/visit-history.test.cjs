const test = require('node:test');
const assert = require('node:assert/strict');

test('patient home displays the authorized synthetic visit summary', async () => {
  let definition;
  const requests = [];
  global.Page = value => { definition = value; };
  global.getApp = () => ({ globalData: { miniRequestConfig: { baseUrl: 'https://adc.test', syntheticLogin: true } } });
  global.wx = {
    getStorageSync: () => '',
    request({ url, header, success }) {
      requests.push(url);
      let data;
      if (url.endsWith('/sessions/wechat')) data = { identityVerified: false, accessToken: 'SYNTHETIC-token' };
      else {
        assert.equal(header.Authorization, 'Bearer SYNTHETIC-token');
        if (url.endsWith('/authorizations')) data = { items: [
          { patientId: '30', status: 'ACTIVE', kind: 'SELF', permissions: ['VISIT_HISTORY_READ'] },
          { patientId: '31', status: 'ACTIVE', kind: 'GUARDIAN', permissions: ['APPOINTMENT_READ'] },
        ] };
        else {
          assert.match(url, /\/visit-history\?patientId=30$/);
          data = { items: [{ patientId: '30', visitId: '51', clinicId: '81001',
            registeredAt: '2026-11-18T10:30:00+08:00', performedProjects: ['SYNTHETIC 检查'] }] };
        }
      }
      success({ statusCode: 200, data });
    },
  };
  try {
    require('../miniprogram/pages/index/index.js');
    const page = { ...definition, data: { ...definition.data },
      setData(values) { Object.assign(this.data, values); } };
    page.onLoad();
    await page.login();
    await page.openVisitHistory();
    assert.equal(page.data.screen, 'history');
    assert.equal(requests.length, 3);
    assert.equal(page.data.historyRows[0].clinicLabel, 'ADC 近邻门诊（演示）');
    assert.equal(page.data.historyRows[0].projectLabel, 'SYNTHETIC 检查');
    page.openHome();
    assert.deepEqual(page.data.historyRows, []);
  } finally {
    delete global.Page;
    delete global.getApp;
    delete global.wx;
  }
});
