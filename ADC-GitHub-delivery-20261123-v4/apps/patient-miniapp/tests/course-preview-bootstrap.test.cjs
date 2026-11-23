const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function boot(version,enabled=true){let app;vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/app.js'),'utf8'),{App:x=>app=x,require:name=>name.includes('course-preview')?{enabled,origin:'https://106.55.173.42'}:{backendOrigin:''},wx:{getAccountInfoSync:()=>({miniProgram:{envVersion:version}}),getStorageSync:()=>null}});app.onLaunch();return app.globalData.miniRequestConfig;}
test('uploaded course experience selects shared cloud endpoint without relying on simulator storage',()=>{const config=boot('trial');assert.equal(config.baseUrl,'https://106.55.173.42');assert.equal(config.syntheticLogin,true);assert.equal(config.slotSelectionEnabled,true);});
test('phone developer preview also uses cloud without inheriting computer loopback storage',()=>{const config=boot('develop');assert.equal(config.baseUrl,'https://106.55.173.42');assert.equal(config.syntheticLogin,true);});
test('course experience flag cannot configure a release build and can be disabled',()=>{assert.equal(boot('release'),undefined);assert.equal(boot('trial',false),undefined);});
test('cloud demo offers only the configured clinic; local demo directory remains available',()=>{
 let def;vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/pages/index/index.js'),'utf8'),{Page:x=>def=x,require:name=>name.includes('demo-dentists')?{demoDentists:()=>[]}:{}});
 const page={...def,syntheticLogin:true,courseClinicIds:['81001'],data:{signedIn:true},setData(value){Object.assign(this.data,value)}};
 page.recommendDemoClinic();assert.equal(page.data.clinics.length,1);assert.equal(page.data.clinics[0].clinicId,'81001');
});
