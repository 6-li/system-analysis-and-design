const test = require('node:test');
const assert = require('node:assert/strict');

test('the simulator demo config works only in develop and on IPv4 loopback', () => {
  const previewPath=require.resolve('../miniprogram/course-preview-config.js');
  require(previewPath);const originalPreview=require.cache[previewPath].exports;
  require.cache[previewPath].exports={enabled:false};
  let app;
  let envVersion = 'release';
  let origin = 'http://127.0.0.1:52701';
  global.App = value => { app = value; };
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
    getStorageSync: () => ({ origin }),
  };
  try {
    require('../miniprogram/app.js');
    app.onLaunch();
    assert.equal(app.globalData.miniRequestConfig, undefined);
    envVersion = 'develop';
    origin = 'http://192.168.1.4:52701';
    app.onLaunch();
    assert.equal(app.globalData.miniRequestConfig, undefined);
    origin = 'http://127.0.0.1:52701';
    app.onLaunch();
    assert.deepEqual(app.globalData.miniRequestConfig,
      { baseUrl: origin, allowLocalHttp: true, syntheticLogin: true,
        changeRequestsEnabled: false, cancelRequestsEnabled: false, slotSelectionEnabled:true });
  } finally {
    require.cache[previewPath].exports=originalPreview;
    delete global.wx;
    delete global.App;
  }
});
