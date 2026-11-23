const { backendOrigin } = require('./release-config.js');
const coursePreview = require('./course-preview-config.js');

App({
  globalData: {},
  onLaunch() {
    delete this.globalData.miniRequestConfig;
    const { envVersion } = wx.getAccountInfoSync().miniProgram;
    if ((envVersion === 'trial' || envVersion === 'develop') && coursePreview.enabled === true && coursePreview.origin === 'https://106.55.173.42') {
      this.globalData.miniRequestConfig = { baseUrl: coursePreview.origin, syntheticLogin: true,
        patientAccounts: coursePreview.patientAccounts === true,
        slotSelectionEnabled: true, changeRequestsEnabled: coursePreview.changeRequests === true, cancelRequestsEnabled: coursePreview.cancelRequests === true, courseClinicIds: ['81001'] };
      // Supported developer/experience debugging API; never invoked for release builds.
      this.globalData.courseDebugState = 'UNAVAILABLE';
      if (typeof wx.setEnableDebug === 'function') {
        try {
          if (typeof wx.getAppBaseInfo === 'function' && wx.getAppBaseInfo().enableDebug === true) {
            this.globalData.courseDebugState = 'ENABLED';
          } else {
            this.globalData.courseDebugState = 'REQUESTED';
            wx.setEnableDebug({ enableDebug: true,
              success: () => { this.globalData.courseDebugState = 'ENABLED'; },
              fail: () => { this.globalData.courseDebugState = 'FAILED'; } });
          }
        } catch (_) { this.globalData.courseDebugState = 'FAILED'; }
      }
      return;
    }
    if (envVersion === 'release' || envVersion === 'trial') {
      const domain = /^https:\/\/((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63})$/i;
      const host = domain.exec(backendOrigin)?.[1].toLowerCase();
      if (host && !host.endsWith('.localhost') && !host.endsWith('.local')) {
        this.globalData.miniRequestConfig = { baseUrl: backendOrigin, slotSelectionEnabled: true };
      }
      return;
    }
    if (envVersion !== 'develop') return;
    const remote = wx.getStorageSync('adc-course-remote-demo');
    if (remote?.enabled === true && remote.origin === 'https://106.55.173.42') {
      this.globalData.miniRequestConfig = {
        baseUrl: remote.origin, syntheticLogin: true, slotSelectionEnabled: true,
        changeRequestsEnabled: false, cancelRequestsEnabled: false,
        courseClinicIds: ['81001'],
      };
      return;
    }
    const demo = wx.getStorageSync('adc-local-uc01-demo');
    if (!demo || !/^http:\/\/127\.0\.0\.1:[1-9]\d{0,4}$/.test(demo.origin)) return;
    this.globalData.miniRequestConfig = {
      baseUrl: demo.origin, allowLocalHttp: true, syntheticLogin: true, changeRequestsEnabled: demo.changeRequests === true,
      cancelRequestsEnabled: demo.cancelRequests === true,
      slotSelectionEnabled: true,
    };
  },
});
