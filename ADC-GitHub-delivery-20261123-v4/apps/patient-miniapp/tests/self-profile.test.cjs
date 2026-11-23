const test=require('node:test'), assert=require('node:assert/strict');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
test('self profile uses session bearer and validates the archive response',async()=>{
  let seen; const client=createAppointmentRequests({post:async()=>({accessToken:'SYNTHETIC-token',identityVerified:false}),
    get:async(path,headers)=>{seen={path,headers};return {status:'VERIFIED',patientId:'71',patientName:'SYNTHETIC 本人',identityInformation:'SYNTHETIC identity',contactInformation:'self@example.invalid'}}});
  await client.login('SYNTHETIC-code'); const profile=await client.selfProfile();
  assert.equal(profile.patientName,'SYNTHETIC 本人');assert.equal(seen.path,'/api/v1/mini/me');
  assert.equal(seen.headers.Authorization,'Bearer SYNTHETIC-token');
});
function page(){let def;global.Page=d=>def=d;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;return {...def,data:{...def.data,signedIn:true},setData(d){Object.assign(this.data,d)}}}
test('profile page displays a verified archive then clears it when the read fails',async()=>{
  const p=page();p.client={selfProfile:async()=>({status:'VERIFIED',patientName:'SYNTHETIC 本人',contactInformation:'demo@example.invalid'})};
  await p.openProfile();assert.equal(p.data.selfProfile.patientName,'SYNTHETIC 本人');
  p.client.selfProfile=async()=>{throw Error('revoked')};await p.openProfile();
  assert.equal(p.data.selfProfile,null);assert.match(p.data.profileNotice,/暂时无法读取/);assert.equal(p.data.busy,false);
});
test('unlinked account shows verification instructions without manufacturing a profile',async()=>{
  const p=page();p.client={selfProfile:async()=>({status:'UNLINKED'})};await p.openProfile();
  assert.equal(p.data.selfProfile,null);assert.match(p.data.profileNotice,/前台核验/);
});
