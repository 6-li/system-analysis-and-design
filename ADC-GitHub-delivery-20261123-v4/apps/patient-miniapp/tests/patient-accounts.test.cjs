const test=require('node:test'),assert=require('node:assert/strict');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
function page(){let d;global.Page=x=>d=x;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');return {...d,data:{...d.data},setData(x){Object.assign(this.data,x)}}}
test('password session uses dedicated endpoint, no implicit registration and clears token on logout',async()=>{
 const calls=[];const client=createAppointmentRequests({post:async(path,body)=>{calls.push({path,body});return {identityVerified:false,accessToken:'TEST-token'}},get:async(path,headers)=>{assert.equal(headers.Authorization,'Bearer TEST-token');return {items:[]}}});
 await client.passwordLogin('Test_user','TEST-password');assert.equal(calls[0].path,'/api/v1/mini/sessions/password');assert.equal(calls.length,1);
 await client.ownRequests();client.clearSession();await assert.rejects(client.ownRequests());
});
test('new page logs in and restores server history with account-scoped pending key, never stores password',async()=>{
 const p=page();p.patientAccounts=true;p.accountOrigin='https://106.55.173.42';p.data.accountName='Test_user';p.data.accountPassword='TEST-password';let key;
 global.wx={getStorageSync(k){key=k;return 'TEST-pending'}};
 p.client={passwordLogin:async()=>{},ownRequests:async()=>({items:[{requestId:'TEST-request',status:'BOOKED'}]})};
 await p.login();assert.equal(p.data.signedIn,true);assert.equal(p.data.accountPassword,'');assert.match(key,/:test_user$/);assert.equal(p.data.ownRequests[0].statusLabel,'已生成正式预约');
});
test('logout rejects late request response from previous account',async()=>{
 const p=page();p.patientAccounts=true;p.data.signedIn=true;let resolve;
 p.client={clearSession(){},ownRequests:()=>new Promise(r=>resolve=r)};
 const reading=p.refreshRequests();p.logout();resolve({items:[{requestId:'OTHER-account',status:'BOOKED'}]});await reading;
 assert.equal(p.data.signedIn,false);assert.deepEqual(p.data.ownRequests,[]);
});
test('registration failure clears password and does not mark account signed in',async()=>{
 const p=page();p.data.accountName='TEST_user';p.data.accountPassword='TEST-password';p.client={register:async()=>{throw {status:409}}};
 await p.registerAccount();assert.equal(p.data.accountPassword,'');assert.equal(p.data.signedIn,false);assert.match(p.data.message,/被使用/);
});
test('account flag is explicit in course builds and cannot enable release configuration',()=>{
 const vm=require('node:vm'),fs=require('node:fs');
 function boot(version,accounts=true){let app;vm.runInNewContext(fs.readFileSync(require.resolve('../miniprogram/app.js'),'utf8'),{
  App:x=>app=x,require:n=>n.includes('course-preview')?{enabled:true,origin:'https://106.55.173.42',patientAccounts:accounts}:{backendOrigin:''},
  wx:{getAccountInfoSync:()=>({miniProgram:{envVersion:version}})}});app.onLaunch();return app.globalData.miniRequestConfig;}
 assert.equal(boot('trial').patientAccounts,true);assert.equal(boot('release'),undefined);
 assert.equal(boot('trial',false).patientAccounts,false);
});
test('logout requests server revocation and discards token even when network fails',async()=>{
 let logoutHeaders;const client=createAppointmentRequests({post:async(path,body,headers)=>{
  if(path.endsWith('/password'))return {identityVerified:false,accessToken:'TEST-token'};
  logoutHeaders=headers;throw Error('TEST-network');}});
 await client.passwordLogin('Test_user','TEST-password');await assert.rejects(client.logout());
 assert.equal(logoutHeaders.Authorization,'Bearer TEST-token');await assert.rejects(client.ownRequests());
});
