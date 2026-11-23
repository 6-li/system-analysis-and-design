function micros(value){
 const time=Date.parse(value);if(!Number.isFinite(time)||!/(?:Z|[+-]\d{2}:\d{2})$/.test(value))throw Error('INVALID_TIME');
 const fraction=(value.match(/\.(\d{1,6})(?:Z|[+-]\d{2}:\d{2})$/)?.[1]||'').padEnd(6,'0');
 return BigInt(time)*1000n+BigInt(fraction.slice(3)||'0');
}
// Read-only projection. Endpoints retain original microsecond text; server still decides booking capacity.
export function receptionAvailability(body){
 if(body?.coverage!=='COMPLETE'||!Array.isArray(body.windows)||!Array.isArray(body.busy))throw Error('COMPLETE_OCCUPANCY_REQUIRED');
 for(const row of [...body.windows,...body.busy])if(!/^[1-9]\d*$/.test(String(row.dentistId))||micros(row.endsAt)<=micros(row.startsAt))throw Error('INVALID_INTERVAL');
 const output=body.busy.map(b=>({...b,kind:'APPOINTMENT'}));
 for(const w of body.windows){
  const lo=micros(w.startsAt),hi=micros(w.endsAt);if(hi<=lo)throw Error('INVALID_WINDOW');
  const cuts=body.busy.filter(b=>b.dentistId===w.dentistId).map(b=>({lo:micros(b.startsAt),hi:micros(b.endsAt),start:b.startsAt,end:b.endsAt})).filter(b=>b.lo<hi&&lo<b.hi).sort((a,b)=>a.lo<b.lo?-1:a.lo>b.lo?1:0);
  let cursor=lo,text=w.startsAt;
  for(const b of cuts){if(b.hi<=b.lo)throw Error('INVALID_BUSY');if(b.lo>cursor)output.push({...w,kind:'FREE',startsAt:text,endsAt:b.start});if(b.hi>cursor){cursor=b.hi;text=b.end;}if(cursor>=hi)break;}
  if(cursor<hi)output.push({...w,kind:'FREE',startsAt:text,endsAt:w.endsAt});
 }
 return output;
}
