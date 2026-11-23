import test from 'node:test'; import assert from 'node:assert/strict';
import {getTechnicalJson,technicalErrorMessage} from '../src/services/http.js';
test('one GET with JSON accept and bounded abort signal',async()=>{let count=0;await getTechnicalJson('/api/technical/ping',{fetcher:async(path,options)=>{count++;assert.equal(options.method,'GET');assert.equal(options.headers.Accept,'application/json');assert.ok(options.signal instanceof AbortSignal);return {ok:true,json:async()=>({ok:true})}}});assert.equal(count,1)});
test('timeout is not retried or exposed as success',async()=>{let count=0;await assert.rejects(getTechnicalJson('/api/technical/ping',{fetcher:async()=>{count++;throw Object.assign(new Error('SECRET'),{name:'TimeoutError'})}}),{code:'TIMEOUT'});assert.equal(count,1)});
test('rejects endpoints outside technical scope before network',async()=>{let count=0;for(const path of ['https://example.com/api/technical/ping','/api/appointments','/api/technical/../../appointments','/api/technical/ping?secret=1'])await assert.rejects(getTechnicalJson(path,{fetcher:()=>{count++}}),{code:'INVALID_ENDPOINT'});assert.equal(count,0)});
test('rejects invalid timeout before request',async()=>{await assert.rejects(getTechnicalJson('/api/technical/ping',{timeoutMs:0}),{code:'INVALID_TIMEOUT'})});
test('unexpected fetch response fails safely',async()=>{await assert.rejects(getTechnicalJson('/api/technical/ping',{fetcher:async()=>null}),{code:'INVALID_RESPONSE'})});
test('UI uses fixed messages instead of untrusted error text',()=>{assert.doesNotMatch(technicalErrorMessage({message:'SECRET'}),/SECRET/);assert.match(technicalErrorMessage({code:'TIMEOUT'}),/超时/)});
test('timeout while reading response body is still TIMEOUT',async()=>{
 await assert.rejects(getTechnicalJson('/api/technical/ping',{fetcher:async()=>({ok:true,json:async()=>{throw Object.assign(new Error('PRIVATE_BODY_DETAIL'),{name:'AbortError'})}})}),{code:'TIMEOUT'});
});
test('platform-invalid timeout values fail before fetch, not as network errors',async()=>{
 let calls=0;
 for(const timeoutMs of [1.5,-1,NaN,Infinity,2147483648]){
  await assert.rejects(getTechnicalJson('/api/technical/ping',{timeoutMs,fetcher:async()=>{calls++}}),{code:'INVALID_TIMEOUT'});
 }
 assert.equal(calls,0);
});
