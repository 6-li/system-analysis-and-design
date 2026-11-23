const test=require('node:test'),assert=require('node:assert/strict');
const {createHttpClient}=require('../miniprogram/services/http.js');
const {createAppointmentRequests}=require('../miniprogram/services/appointment-requests.js');
test('change options sends only a valid bound original reference to dedicated endpoint',async()=>{
 const calls=[];const client=createAppointmentRequests(createHttpClient({baseUrl:'https://adc.test',request:o=>{calls.push(o);o.success({statusCode:200,data:o.url.endsWith('/sessions/wechat')?{accessToken:'TEST-token',identityVerified:false}:{clinicId:'81001',date:'2026-12-21',reservesCapacity:false,timeZone:'Asia/Shanghai',projects:[],slots:[],expectedAppointmentToken:'TEST-version'}})}}));
 await client.login('TEST-code');await client.bookingOptions('81001','2026-12-21','85001','100001');
 assert.match(calls[1].url,/booking-change-options\?clinicId=81001&date=2026-12-21&projectId=85001&originalAppointmentId=100001$/);
 await assert.rejects(client.bookingOptions('81001','2026-12-21','85001','other'),{code:'INVALID_REQUEST'});assert.equal(calls.length,2);
});
function page(){let d;global.Page=x=>d=x;global.wx={getRandomValues:o=>o.success({randomValues:new Uint8Array(16).buffer}),setStorageSync:()=>{},removeStorageSync:()=>{}};const file=require.resolve('../miniprogram/pages/index/index.js');delete require.cache[file];require(file);return {...d,patientAccounts:true,data:{...d.data,screen:'change',signedIn:true,requestProfileReady:true,patient:'TEST identity',applicant:'TEST name',contact:'TEST contact',date:'2026-12-21',selectedPatient:'1',requestTarget:'2',slotSelectionEnabled:true,bookingDoctor:'79001',originalAppointmentToken:'TEST-version'},setData:x=>{},showReceipt:()=>{}};}
test('change cannot submit without a confirmed available slot',async()=>{const p=page();p.setData=x=>Object.assign(p.data,x);let sent=false;p.client={submit:async()=>sent=true};await p.submit();assert.equal(sent,false);assert.match(p.data.message,/请选择可预约时段/);});
test('change submits exact selected slot and original version with no extra clinic requirement',async()=>{
 const p=page();p.setData=x=>Object.assign(p.data,x);let input;
 p.data.selectionConfirmed=true;p.data.selectedSlot={dentistId:'79001',effectiveScheduleRef:{scheduleId:'10',versionNo:1},startsAt:'2026-12-21T09:00:00+08:00',endsAt:'2026-12-21T09:27:00+08:00',durationBasis:{schemaVersion:1,basisMode:'PRESERVED_ORIGINAL',primaryProjectId:'85001',projectVersion:1,adoptedDurationMinutes:27}};
 p.client={submit:async(k,r)=>{input=r;return {receiptStatus:'CONFIRMED_RECEIVED'}}};await p.submit();
 assert.equal(input.type,'CHANGE');assert.equal(input.expectedAppointmentToken,'TEST-version');assert.equal(input.selectedScheme.startsAt,p.data.selectedSlot.startsAt);assert.equal(input.patientConfirmed,true);assert.equal(input.originalAppointmentId,'2');
});
