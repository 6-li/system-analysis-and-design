import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
function mount(fetch){
 const source=fs.readFileSync(new URL('../src/views/PatientMaintenance.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const context={reactive:x=>x,ref:value=>({value}),document:{querySelector:s=>s.includes('csrf')?{content:'TEST-csrf'}:null},location:{search:''},URLSearchParams,sessionStorage:{getItem:()=>null},fetch}
 vm.runInNewContext(source+'\nglobalThis.bindingTest={binding,busy,bindingMessage,bindAccount}',context)
 return context.bindingTest
}
test('binding UI requires separate explicit identity confirmation and posts exact original source',async()=>{
 let calls=0,body
 const page=mount(async(url,options)=>{calls++;assert.equal(url,'/api/v1/staff/patient-account-bindings');assert.equal(options.headers['X-CSRF-Token'],'TEST-csrf');body=JSON.parse(options.body);return {ok:true,json:async()=>({bound:true})}})
 Object.assign(page.binding,{patientId:'71004',requestId:'TEST-source',version:1,basis:'TEST-verified'})
 await page.bindAccount();assert.equal(calls,0)
 page.binding.confirmed=true;await page.bindAccount();assert.equal(calls,1);assert.equal(body.identityConfirmed,true);assert.equal(body.patientId,'71004');assert.equal(body.requestVersion,1);assert.match(page.bindingMessage.value,/已确认/)
})
test('network failure keeps binding unconfirmed rather than claiming successful access',async()=>{
 const page=mount(async()=>{throw Error('TEST-offline')})
 Object.assign(page.binding,{patientId:'71004',requestId:'TEST-source',version:1,basis:'TEST-verified',confirmed:true})
 await page.bindAccount();assert.match(page.bindingMessage.value,/待核对/);assert.equal(page.busy.value,false)
})
