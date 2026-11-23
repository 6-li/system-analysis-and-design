export const recordId=value=>typeof value==='string'&&/^[1-9]\d*$/.test(value)
export function recordScope(value){const ids=Array.isArray(value)?value:String(value||'').split(',').map(x=>x.trim());return ids.length&&ids.every(recordId)&&new Set(ids).size===ids.length?[...ids].sort():null}
export function sameRecordScope(a,b){const x=recordScope(a),y=recordScope(b);return !!x&&!!y&&x.join(',')===y.join(',')}
export async function readQuarterRecord(kind,id,clinics,csrf,version){
 if(!['previews','calculations'].includes(kind)||!recordId(id)||!recordScope(clinics)||!csrf)throw Error('INVALID_RECORD')
 const params=new URLSearchParams({clinics:recordScope(clinics).join(',')});if(kind==='calculations'){if(!Number.isInteger(+version)||+version<1)throw Error('INVALID_VERSION');params.set('version',String(version))}
 const response=await fetch('/api/v1/staff/quarterly/records/'+kind+'/'+id+'?'+params,{headers:{'X-CSRF-Token':csrf},cache:'no-store',redirect:'error'});const body=await response.json()
 if(!response.ok||body[kind==='previews'?'statisticId':'calculationId']!==id||!sameRecordScope(body.clinics,clinics)||(kind==='calculations'&&body.version!==+version))throw Error('RECORD_UNAVAILABLE')
 return body
}
