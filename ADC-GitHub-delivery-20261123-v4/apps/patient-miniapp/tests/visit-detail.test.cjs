const test=require('node:test'),assert=require('node:assert/strict');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
const detail={visitId:'51',clinicId:'81001',clinicName:'演示门诊',dentistId:'79001',registeredAt:'2026-11-22T01:00:00Z',projects:[{item:'口腔检查',unit:'次',quantity:'1'}],invoice:null};
test('details use patient-scoped URL and reject mismatched response',async()=>{
 let seen;const client=createAppointmentRequests({post:async()=>({accessToken:'SYNTHETIC-token',identityVerified:false}),get:async(path)=>{seen=path;return detail}});await client.login('TEST');
 assert.equal((await client.visitDetail('30','51')).invoice,null);assert.equal(seen,'/api/v1/mini/visit-history/51?patientId=30');
 await assert.rejects(client.visitDetail('30','52'));
});
function page(){let def;global.Page=x=>def=x;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;return {...def,data:{...def.data,signedIn:true,historyPatient:'30',historyRows:[{visitId:'51'}]},setData(x){Object.assign(this.data,x)}}}
test('detail displays clinic doctor and Beijing instant then clears failed read',async()=>{
 const p=page();p.client={visitDetail:async()=>detail};await p.openHistoryDetail({currentTarget:{dataset:{id:'51'}}});
 assert.equal(p.data.historyDetail.dentistLabel,'演示牙医甲');assert.match(p.data.historyDetail.dateLabel,/09:00/);assert.equal(p.data.screen,'history-detail');
 p.backToHistory();p.client.visitDetail=async()=>{throw Error('denied')};await p.openHistoryDetail({currentTarget:{dataset:{id:'51'}}});assert.equal(p.data.historyDetail,null);assert.match(p.data.historyDetailNotice,/无法读取/);
});
test('cannot open a visit absent from the authorized list',async()=>{
 const p=page();p.client={visitDetail:()=>{throw Error('must not call')}};await p.openHistoryDetail({currentTarget:{dataset:{id:'99'}}});assert.equal(p.data.screen,'home');
});
test('returning home and signing out clear the loaded private detail',async()=>{
 const p=page();p.data.historyDetail=detail;p.openHome();assert.equal(p.data.historyDetail,null);
 p.data.historyDetail=detail;p.client={clearSession(){},logout:async()=>{}};await p.logout();assert.equal(p.data.historyDetail,null);assert.equal(p.data.signedIn,false);
});
