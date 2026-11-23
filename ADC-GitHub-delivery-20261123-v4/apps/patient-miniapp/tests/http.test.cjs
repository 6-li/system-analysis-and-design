const test = require('node:test');
const assert = require('node:assert/strict');
const { createHttpClient, TechnicalHttpError } = require('../miniprogram/services/http.js');
const endpoint = 'https://technical.example.invalid';
const good = { statusCode: 200, data: { service: 'adc-backend', status: 'UP' } };
function client(handler, options={}) { return createHttpClient({ baseUrl:endpoint, request:handler, ...options }); }

test('default client cannot issue network requests', async()=>{
 let calls=0; const http=createHttpClient({ request(){calls++} });
 await assert.rejects(http.get('/api/technical/ping'), {code:'NOT_CONFIGURED'}); assert.equal(calls,0);
});
test('rejects insecure nonloopback and credentials in service URLs',()=>{
 for(const baseUrl of ['http://example.invalid','http://127.0.0.1','https://user:secret@example.invalid','https://example.invalid/?token=x','https://example.invalid/#x','https://example.invalid\\other'])
  assert.throws(()=>createHttpClient({baseUrl}), TechnicalHttpError);
});
test('allows only explicit loopback HTTP development exception',()=>{
 for(const baseUrl of ['http://127.0.0.1:8080','http://localhost:8080','http://[::1]:8080'])
  assert.doesNotThrow(()=>createHttpClient({baseUrl,allowLocalHttp:true}));
 assert.throws(()=>createHttpClient({baseUrl:'http://other.invalid',allowLocalHttp:true}));
});
test('invalid timeout and port fail before requests',()=>{
 for(const timeoutMs of [0,-1,1.5,'5000']) assert.throws(()=>createHttpClient({timeoutMs}));
 for(const baseUrl of ['https://example.invalid:0','https://example.invalid:65536']) assert.throws(()=>createHttpClient({baseUrl}));
});
test('GET forwards only technical transport settings and configured timeout',async()=>{
 let sent; const http=client(options=>{sent=options;options.success(good)},{baseUrl:endpoint+'/',timeoutMs:1200});
 assert.deepEqual(await http.get('/api/technical/ping'),good.data);
 assert.equal(sent.url,endpoint+'/api/technical/ping'); assert.equal(sent.method,'GET'); assert.equal(sent.timeout,1200);
 assert.deepEqual(sent.header,{Accept:'application/json'}); assert.equal(sent.data,undefined);
});
test('paths cannot change origin, inject query or traverse',async()=>{
 let calls=0;const http=client(()=>calls++);
 for(const path of ['//other.invalid','https://other.invalid','/../x','/api?secret=x','/api#x','/api\\x','/%2e%2e/x'])
  await assert.rejects(http.get(path),{code:'INVALID_REQUEST'});
 assert.equal(calls,0);
});
test('native adapter handles unavailable wx runtime safely',async()=>{
 const http=createHttpClient({baseUrl:endpoint});
 await assert.rejects(http.get('/api/technical/ping'),{code:'TRANSPORT_UNAVAILABLE'});
});
test('non2xx creates stable safe errors, discarding server message and body',async()=>{
 for(const status of [400,401,403,404,405,409,500,503]){
  let calls=0;const http=client(o=>{calls++;o.success({statusCode:status,data:{code:'UNTRUSTED',message:'SECRET_MARKER',patient:'PRIVATE'}})});
  await assert.rejects(http.get('/api/technical/ping'),error=>{
   assert.equal(error.code,'HTTP_'+status);assert.equal(error.status,status);
   assert.doesNotMatch(JSON.stringify(error),/SECRET_MARKER|PRIVATE|UNTRUSTED/);
   assert.doesNotMatch(error.message,/SECRET_MARKER|PRIVATE/);return true;
  }); assert.equal(calls,1);
 }
});
test('redirect responses are not interpreted as healthy',async()=>{
 await assert.rejects(client(o=>o.success({statusCode:302,data:{status:'UP'}})).get('/api/technical/ping'),{code:'HTTP_ERROR',status:302});
});
test('malformed success responses are rejected',async()=>{
 for(const response of [null,{}, {statusCode:'200',data:{}}, {statusCode:200,data:null},{statusCode:200,data:'<html>private</html>'}])
  await assert.rejects(client(o=>o.success(response)).get('/api/technical/ping'),{code:'INVALID_RESPONSE'});
});
test('network failure is sanitized and never automatically retried',async()=>{
 let calls=0;const http=client(o=>{calls++;o.fail({errMsg:'network failed SECRET_MARKER'})});
 await assert.rejects(http.get('/api/technical/ping'),e=>{assert.equal(e.code,'NETWORK_ERROR');assert.doesNotMatch(e.message,/SECRET_MARKER/);return true});assert.equal(calls,1);
});
test('timeout is distinguished without leaking native error details',async()=>{
 await assert.rejects(client(o=>o.fail({errMsg:'request:fail timeout SECRET_MARKER'})).get('/api/technical/ping'),{code:'TIMEOUT'});
});
test('synchronous native exceptions are sanitized',async()=>{
 await assert.rejects(client(()=>{throw new Error('SECRET_MARKER')}).get('/api/technical/ping'),{code:'NETWORK_ERROR'});
});
test('late callbacks do not overwrite the initial result',async()=>{
 const http=client(o=>{o.fail({errMsg:'timeout'});o.success(good);o.fail({errMsg:'offline'})});
 await assert.rejects(http.get('/api/technical/ping'),{code:'TIMEOUT'});
});
test('wx.request is bound to wx for real adapter use without network in test',async()=>{
 const previous=global.wx; let calls=0;
 const fake={request(options){assert.equal(this,fake);calls++;options.success(good)}};global.wx=fake;
 try { assert.deepEqual(await createHttpClient({baseUrl:endpoint}).get('/api/technical/ping'),good.data);assert.equal(calls,1); }
 finally {if(previous===undefined)delete global.wx;else global.wx=previous;}
});
