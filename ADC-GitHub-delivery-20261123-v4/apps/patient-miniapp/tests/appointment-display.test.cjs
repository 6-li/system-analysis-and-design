const test=require('node:test'),assert=require('node:assert/strict');
const {appointmentDisplay}=require('../miniprogram/services/appointment-display.js');
const item={appointmentId:'100000',clinicId:'81001',dentistId:'79001',startsAt:'2026-11-22T01:00:00Z',endsAt:'2026-11-22T01:27:00Z',factVerification:'VERIFIED',formalStatus:'待到诊'};
test('UTC booking displays clinic-local arrival and directory names',()=>{
 const row=appointmentDisplay(item,[{clinicId:'81001',clinicName:'TEST门诊'}],[{dentistId:'79001',dentistLabel:'TEST医生'}]);
 assert.equal(row.arrivalLabel,'2026年11月22日 09:00（北京时间）');assert.equal(row.intervalLabel,'2026年11月22日 09:00 至 09:27（北京时间）');assert.equal(row.clinicLabel,'TEST门诊');assert.equal(row.dentistLabel,'TEST医生');
 assert.equal(appointmentDisplay({...item,startsAt:'2026-11-22T09:00:00+08:00'}).arrivalLabel,row.arrivalLabel);
});
test('cross-midnight interval shows both dates and invalid or unverified instants are not invented',()=>{
 const row=appointmentDisplay({...item,startsAt:'2026-12-31T15:50:00Z',endsAt:'2026-12-31T16:20:00Z'});
 assert.equal(row.intervalLabel,'2026年12月31日 23:50 至 2027年01月01日 00:20（北京时间）');
 for(const change of [{startsAt:'invalid'},{startsAt:'2026-11-22T09:00:00'},{factVerification:'PENDING_VERIFICATION'}]){
  const result=appointmentDisplay({...item,...change});assert.equal(result.intervalLabel,'');assert.equal(result.arrivalLabel,'时间待核对');
 }
 assert.equal(appointmentDisplay(item).clinicLabel,'门诊编号 81001');
});
function page(){let def;global.Page=d=>def=d;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;return {...def,data:{...def.data,signedIn:true,appointmentPatients:[{patientId:'71',kind:'SELF'}]},setData(d){Object.assign(this.data,d)}}}
test('appointment page loads server clinic directory and opens only its listed detail',async()=>{
 const p=page();p.syntheticLogin=true;p.client={appointments:async()=>({items:[item]}),clinics:async()=>[{clinicId:'81001',clinicName:'TEST门诊'}]};
 await p.readAppointments('71');assert.equal(p.data.formalAppointments[0].clinicLabel,'TEST门诊');assert.match(p.data.formalAppointments[0].intervalLabel,/09:00/);
 p.openAppointmentDetail({currentTarget:{dataset:{id:'OTHER'}}});assert.equal(p.data.appointmentDetail,null);
 p.openAppointmentDetail({currentTarget:{dataset:{id:'100000'}}});assert.equal(p.data.screen,'appointment-detail');assert.equal(p.data.appointmentDetail.clinicLabel,'TEST门诊');
 p.data.signedIn=false;p.openAppointmentDetail({currentTarget:{dataset:{id:'OTHER'}}});assert.equal(p.data.appointmentDetail.appointmentId,'100000');
});
