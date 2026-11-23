const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {appointmentDisplay}=require('../miniprogram/services/appointment-display.js');
const base={appointmentId:'1',clinicId:'81001',dentistId:'79001',startsAt:'2026-11-23T01:00:00Z',endsAt:'2026-11-23T01:30:00Z',factVerification:'PENDING_VERIFICATION'};
test('server terminal statuses stay visible independently of booking evidence verification',()=>{
 for(const formalStatus of ['已到诊','已确认未到诊','已取消']){
  const result=appointmentDisplay({...base,formalStatus});assert.equal(result.statusLabel,formalStatus);assert.equal(result.intervalLabel,'');assert.equal(result.verificationNotice,'预约时间信息待核对');
 }
 for(const formalStatus of ['待到诊','待核实','UNKNOWN'])assert.equal(appointmentDisplay({...base,formalStatus}).statusLabel,'预约状态待核对');
});
test('terminal display never enables appointment changes and copies the same status into detail',async()=>{
 let def;global.Page=d=>def=d;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;
 const p={...def,data:{...def.data,signedIn:true,changeEnabled:true,cancelEnabled:true,appointmentPatients:[{patientId:'2',kind:'SELF'}]},setData(d){Object.assign(this.data,d)},client:{appointments:async()=>({items:['已到诊','已确认未到诊','已取消','待到诊'].map((formalStatus,index)=>({...base,appointmentId:String(index+1),formalStatus}))})}};
 await p.readAppointments('2');assert.deepEqual(p.data.formalAppointments.map(r=>r.statusLabel),['已到诊','已确认未到诊','已取消','预约状态待核对']);
 for(const row of p.data.formalAppointments){assert.equal(row.canRequestChange,false);assert.equal(row.canRequestCancel,false);}
 p.openAppointmentDetail({currentTarget:{dataset:{id:'1'}}});assert.equal(p.data.appointmentDetail.statusLabel,'已到诊');
});
test('bill table renders original amounts with an explicit empty state and payment facts retained',()=>{
 const s=fs.readFileSync(require.resolve('../miniprogram/pages/index/index.wxml'),'utf8');
 for(const title of ['项目','数量','单价','小计'])assert.ok(s.includes('>'+title+'</view>'));
 for(const expression of ['item.unitPrice','item.amount','historyDetail.invoice.total','historyDetail.invoice.payments'])assert.ok(s.includes(expression));
 assert.match(s,/scroll-view scroll-x/);assert.match(s,/暂无账单明细/);assert.match(s,/账单尚未生成/);assert.match(s,/课程模拟结算（非实际扣款）/);
});
