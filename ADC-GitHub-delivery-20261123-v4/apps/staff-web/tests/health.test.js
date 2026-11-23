import test from 'node:test';
import assert from 'node:assert/strict';
import {checkBackend} from '../src/services/health.js';
test('accepts only the expected healthy service', async()=>{assert.equal((await checkBackend(async()=>({ok:true,json:async()=>({service:'adc-backend',status:'UP'})}))).status,'UP')});
test('HTTP error cannot expose server body',async()=>{await assert.rejects(checkBackend(async()=>({ok:false,status:503,json:async()=>({message:'SECRET'})})),{code:'HTTP_503',status:503})});
test('rejects unrelated successful response',async()=>{await assert.rejects(checkBackend(async()=>({ok:true,json:async()=>({status:'UP'})})),{code:'INVALID_RESPONSE'})});
test('network failure is normalized',async()=>{await assert.rejects(checkBackend(async()=>{throw new Error('SECRET')}),{code:'NETWORK_ERROR'})});
test('null payload is not healthy',async()=>{await assert.rejects(checkBackend(async()=>({ok:true,json:async()=>null})),{code:'INVALID_RESPONSE'})});
test('non JSON is not healthy',async()=>{await assert.rejects(checkBackend(async()=>({ok:true,json:async()=>{throw new SyntaxError('SECRET')}})),{code:'INVALID_RESPONSE'})});
