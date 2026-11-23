const test = require('node:test');
const assert = require('node:assert/strict');

const appPath = require.resolve('../miniprogram/app.js');
const configPath = require.resolve('../miniprogram/release-config.js');

test('formal builds use only the configured HTTPS origin and ignore the local demo fixture', () => {
  require(configPath);
  const original = require.cache[configPath].exports;
  const previewPath=require.resolve('../miniprogram/course-preview-config.js');
  require(previewPath);const originalPreview=require.cache[previewPath].exports;
  require.cache[previewPath].exports={enabled:false};
  let app;
  let envVersion = 'release';
  global.App = value => { app = value; };
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion } }),
    getStorageSync: () => assert.fail('Release bootstrap must not read local demo configuration'),
  };
  function boot(backendOrigin) {
    require.cache[configPath].exports = { backendOrigin };
    delete require.cache[appPath];
    require(appPath);
    app.onLaunch();
    return app.globalData.miniRequestConfig;
  }
  try {
    for (const invalid of ['', 'http://course.example.com', 'https://127.0.0.1', 'https://demo.localhost',
      'https://user:pass@course.example.com', 'https://course.example.com/path', 'https://-bad.example.com']) {
      assert.equal(boot(invalid), undefined);
    }
    assert.deepEqual(boot('https://course.example.com'), { baseUrl: 'https://course.example.com', slotSelectionEnabled:true });
    envVersion = 'trial';
    assert.deepEqual(boot('https://course.example.com'), { baseUrl: 'https://course.example.com', slotSelectionEnabled:true });
  } finally {
    require.cache[previewPath].exports=originalPreview;
    delete require.cache[appPath];
    require.cache[configPath].exports = original;
    delete global.App;
    delete global.wx;
  }
});
