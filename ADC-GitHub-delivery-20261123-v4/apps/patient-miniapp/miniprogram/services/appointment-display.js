// Course clinics use Asia/Shanghai. Format explicit instants independently of phone timezone.
const pad=value=>String(value).padStart(2,'0');
function local(value){
  if(typeof value!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(value))return null;
  const time=Date.parse(value);if(!Number.isFinite(time))return null;
  const date=new Date(time+8*60*60*1000);
  return {day:date.getUTCFullYear()+'年'+pad(date.getUTCMonth()+1)+'月'+pad(date.getUTCDate())+'日',clock:pad(date.getUTCHours())+':'+pad(date.getUTCMinutes()),time};
}
function appointmentDisplay(item,clinics=[],doctors=[]){
  const start=local(item.startsAt),end=local(item.endsAt),valid=start&&end&&start.time<end.time;
  const verified=item.factVerification==='VERIFIED';
  return {statusLabel:verified||['已到诊','已确认未到诊','已取消'].includes(item.formalStatus)?item.formalStatus:'预约状态待核对',
    verificationNotice:verified?'':'预约时间信息待核对',
    clinicLabel:clinics.find(c=>c.clinicId===item.clinicId)?.clinicName||'门诊编号 '+item.clinicId,
    dentistLabel:doctors.find(d=>d.dentistId===item.dentistId)?.dentistLabel||'医生编号 '+item.dentistId,
    arrivalLabel:verified&&valid?start.day+' '+start.clock+'（北京时间）':'时间待核对',
    estimatedEndLabel:verified&&valid?end.day+' '+end.clock+'（北京时间）':'时间待核对',
    intervalLabel:verified&&valid?start.day+' '+start.clock+' 至 '+(start.day===end.day?'':end.day+' ')+end.clock+'（北京时间）':''};
}
function instantDisplay(value){const d=local(value);return d?d.day+' '+d.clock+'（北京时间）':'时间待核对';}
module.exports={appointmentDisplay,instantDisplay};
