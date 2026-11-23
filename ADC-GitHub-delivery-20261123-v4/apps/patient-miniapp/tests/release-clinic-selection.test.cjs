const test = require('node:test');
const assert = require('node:assert/strict');

test('the release-style appointment page lets the patient select a directory clinic by name', async () => {
  let definition;
  global.Page = value => { definition = value; };
  global.getApp = () => ({ globalData: { miniRequestConfig: { baseUrl: 'https://course.example.invalid' } } });
  global.wx = {
    getStorageSync: () => '',
    login: ({ success }) => success({ code: 'TEST-wx-code' }),
    request({ url, header, data, success }) {
      if (url.endsWith('/sessions/wechat')) {
        assert.equal(data.code, 'TEST-wx-code');
        return success({ statusCode: 200, data: { accessToken: 'TEST-token', identityVerified: false } });
      }
      assert.match(url, /\/booking\/clinics$/);
      assert.equal(header.Authorization, 'Bearer TEST-token');
      success({ statusCode: 200, data: { items: [
        { clinicId: '81001', clinicName: 'SYNTHETIC 近邻门诊' },
        { clinicId: '81002', clinicName: 'SYNTHETIC 中心门诊' },
      ] } });
    },
  };
  try {
    require('../miniprogram/pages/index/index.js');
    const page = { ...definition, data: { ...definition.data },
      setData(values) { Object.assign(this.data, values); } };
    page.onLoad();
    await page.login();
    await page.openBooking();
    assert.equal(page.data.clinic, '');
    page.openClinics();
    assert.equal(page.data.choosingClinic, true);
    page.selectClinic({ currentTarget: { dataset: { id: '81002' } } });
    assert.equal(page.data.clinicName, 'SYNTHETIC 中心门诊');
    assert.equal(page.data.clinic, '81002');
    assert.equal(page.data.choosingClinic, false);
  } finally {
    delete global.wx;
    delete global.App;
    delete global.Page;
    delete global.getApp;
  }
});
