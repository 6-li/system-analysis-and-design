export async function requestDuration(path,{method='GET',body,csrf,key,fetcher=globalThis.fetch}={}){
 if(path.includes('?')){if(!/^\/api\/v1\/staff\/duration-projects\?after=[0-9]+&limit=([1-9]|[1-9][0-9]|100)$/.test(path))throw Error('INVALID_ENDPOINT');}else
 if(!/^\/api\/v1\/(staff\/duration-projects(?:\/[1-9][0-9]*\/versions\/[1-9][0-9]*)?|headquarters\/duration-project-drafts|headquarters\/duration-projects\/[1-9][0-9]*\/versions\/[1-9][0-9]*\/(draft|publications)|headquarters\/duration-commands\/HQCFG\/v1\/[a-f0-9]{32}\/[A-Z_]+\/[a-f0-9-]{36})$/.test(path))throw Error('INVALID_ENDPOINT');
 if(!['GET','POST','PUT'].includes(method)||method!=='GET'&&(!csrf||!key))throw Error('INVALID_WRITE_CONTEXT');
 const headers={Accept:'application/json'};if(method!=='GET')Object.assign(headers,{'Content-Type':'application/json','X-CSRF-Token':csrf,'Idempotency-Key':key});
 let response;try{response=await fetcher(path,{method,credentials:'same-origin',headers,body:method==='GET'?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});}catch{throw Error('NETWORK_OR_RESULT_UNKNOWN');}
 let json;try{json=await response.json();}catch{throw Error('INVALID_RESPONSE');}if(!response.ok&&!json?.requestReference)throw Error('HTTP_'+response.status);if(!json||typeof json!=='object')throw Error('INVALID_RESPONSE');return json;
}
export function durationReceiptMessage(r){if(r?.status==='UNKNOWN')return '提交结果待核对，请先查询原请求';if(r?.auditPersistence==='NOT_PERSISTED')return '审计未持久化，请核对原请求';if(r?.auditPersistence==='UNKNOWN')return '审计提交结果待核对';if(r?.status==='COMMITTED'&&r?.auditPersistence==='CONFIRMED')return '配置操作已提交';return '操作被拒绝或失败，原配置保持';}
export function positiveMinutes(text){if(!/^[1-9][0-9]*$/.test(String(text))||Number(text)>2147483647)throw Error('正整数分钟必填');return Number(text);}
export function originalRequestReference(namespace,operation,key){if(!/^HQCFG\/v1\/[a-f0-9]{32}$/.test(namespace)||!['DURATION_DRAFT_CREATE','DURATION_DRAFT_EDIT','DURATION_PUBLISH'].includes(operation)||!/^[a-f0-9-]{36}$/.test(key))throw Error('INVALID_REQUEST_CONTEXT');return `${namespace}/${operation}/${key}`;}
