import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
function mount(fetch){
 const source=fs.readFileSync(new URL('../src/views/PatientMaintenance.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const context={reactive:x=>x,ref:value=>({value}),document:{querySelector:s=>s.includes('csrf')?{content:'TEST-csrf'}:null},location:{search:'',assign:url=>context.navigation=url},URLSearchParams,sessionStorage:{getItem:()=>null},fetch}
 vm.runInNewContext(source+'\nglobalThis.harness={form,existing,lookupExisting,linkExisting,continueExisting}',context)
 return {page:context.harness,context}
}
test('original request lookup requires explicit verification and advances to booking only after committed response',async()=>{
 const calls=[];const {page,context}=mount(async(url,options)=>{
  const body=JSON.parse(options.body);calls.push({url,body});
  return {ok:true,json:async()=>url.endsWith('preview')?{patientId:'71004',patientName:'TEST-patient',contactHint:'T***'}:{bound:true,reusedExistingPatient:true,patientId:'71004',requestId:'TEST-request',requestVersion:1}}
 })
 Object.assign(page.form,{requestId:'TEST-request',requestVersion:'0'});await page.lookupExisting();
 assert.deepEqual(Object.keys(calls[0].body).sort(),['requestId','requestVersion']);assert.equal(page.existing.candidate.patientId,'71004')
 await page.linkExisting();assert.equal(calls.length,1)
 Object.assign(page.existing,{confirmed:true,basis:'TEST verification'});await page.linkExisting();assert.equal(calls.length,2);assert.equal(page.existing.linked,true)
 page.continueExisting();assert.match(context.navigation,/sourceVersion=1/)
})
test('failed response never offers successful booking navigation',async()=>{
 const {page,context}=mount(async()=>{throw Error('TEST offline')})
 Object.assign(page.existing,{candidate:{patientId:'71004'},requestId:'TEST-request',version:0,confirmed:true,basis:'TEST verified'})
 await page.linkExisting();assert.equal(page.existing.linked,false);page.continueExisting();assert.equal(context.navigation,undefined);assert.match(page.existing.message,/未确认/)
})
