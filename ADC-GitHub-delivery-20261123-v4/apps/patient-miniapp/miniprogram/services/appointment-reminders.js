const {appointmentDisplay}=require('./appointment-display.js');
const WINDOW_MS=30*60*1000;
const explicitInstant=value=>typeof value==='string'&&/(Z|[+-]\d{2}:\d{2})$/.test(value)?Date.parse(value):NaN;

// This is a current, read-only in-app prompt, never a sent/delivered message record.
function appointmentReminders(items,now,clinics=[],doctors=[]){
 if(!Number.isFinite(now))return [];
 const seen=new Set(),rows=[];
 for(const item of items){
  const start=explicitInstant(item.startsAt),end=explicitInstant(item.endsAt),remaining=start-now;
  if(item.formalStatus!=='待到诊'||item.factVerification!=='VERIFIED'||!Number.isFinite(start)||!Number.isFinite(end)||end<=start||remaining<0||remaining>WINDOW_MS)continue;
  const key=item.patientId+':'+item.appointmentId;if(seen.has(key))continue;seen.add(key);
  const minutes=Math.ceil(remaining/60000);
  rows.push({appointmentId:item.appointmentId,patientId:item.patientId,clinicId:item.clinicId,dentistId:item.dentistId,
   ...appointmentDisplay(item,clinics,doctors),countdown:minutes===0?'预约时间已到':'还有'+minutes+'分钟',startsAt:item.startsAt});
 }
 return rows.sort((a,b)=>explicitInstant(a.startsAt)-explicitInstant(b.startsAt)||String(a.appointmentId).localeCompare(String(b.appointmentId)));
}

async function readReminderSnapshot(client){
 const selves=list=>{
  if(!Array.isArray(list))throw Error('REMINDER_AUTHORIZATION_INVALID');
  return [...new Set(list.filter(p=>p.kind==='SELF'&&typeof p.patientId==='string'&&/^[1-9]\d*$/.test(p.patientId)).map(p=>p.patientId))].sort();
 };
 const patients=selves(await client.appointmentPatients('APPOINTMENT_READ'));
 const appointments=[];
 for(const patient of patients){
  let cursor;const cursors=new Set();let complete=false;
  // Bounded network safety, not a silent history cutoff: incomplete reads fail closed.
  for(let page=0;page<100;page++){
   const result=await client.appointments(patient,cursor);
   if(!Array.isArray(result?.items))throw Error('REMINDER_PAGE_INVALID');
   if(result.items.some(row=>row.patientId!==patient))throw Error('REMINDER_SCOPE_MISMATCH');
   appointments.push(...result.items);
   const next=result.nextCursor;
   if(next==null||next===''){complete=true;break;}
   if(typeof next!=='string'||!/^[1-9]\d*$/.test(next)||cursors.has(next)||(cursor&&(next.length<cursor.length||next.length===cursor.length&&next<=cursor)))throw Error('REMINDER_PAGINATION_INVALID');
   cursors.add(next);cursor=next;
  }
  if(!complete)throw Error('REMINDER_PAGINATION_INCOMPLETE');
 }
 const current=selves(await client.appointmentPatients('APPOINTMENT_READ'));
 if(JSON.stringify(current)!==JSON.stringify(patients))throw Error('REMINDER_AUTHORIZATION_CHANGED');
 return {appointments};
}
module.exports={appointmentReminders,readReminderSnapshot};
