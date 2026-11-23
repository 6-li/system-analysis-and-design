const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function boot(version,remote){let app;vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/app.js'),'utf8'),{App:x=>app=x,require:()=>({backendOrigin:''}),wx:{getAccountInfoSync:()=>({miniProgram:{envVersion:version}}),getStorageSync:key=>key==='adc-course-remote-demo'?remote:null}});app.onLaunch();return app.globalData.miniRequestConfig;}
test('explicit developer-only course origin connects over HTTPS without claiming WeChat identity',()=>{
 const config=boot('develop',{enabled:true,origin:'https://106.55.173.42'});assert.equal(config.baseUrl,'https://106.55.173.42');assert.equal(config.syntheticLogin,true);assert.equal(config.allowLocalHttp,undefined);
});
test('remote synthetic setting cannot enable trial, release, HTTP or another origin',()=>{
 for(const version of ['trial','release'])assert.equal(boot(version,{enabled:true,origin:'https://106.55.173.42'}),undefined);
 for(const origin of ['http://106.55.173.42','https://attacker.example.invalid'])assert.equal(boot('develop',{enabled:true,origin}),undefined);
});
