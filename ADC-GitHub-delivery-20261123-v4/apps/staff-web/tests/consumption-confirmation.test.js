import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {clinicInstant,courseCost} from '../src/components/consumption-time.js'
function mount(fetch){
 const script=fs.readFileSync(new URL('../src/views/SupplyConsumption.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const values=new Map(),context={clinicInstant,courseCost,ref:value=>({value}),computed:f=>({get value(){return f()}}),document:{querySelector:s=>({content:s.includes('csrf')?'TEST-csrf':'true'})},sessionStorage:{getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)},crypto:{randomUUID:()=> '00000000-0000-0000-0000-000000000001'},fetch}
 vm.runInNewContext(script+'\nglobalThis.page={clinic,supply,quantity,selectedTime,verificationBasis,confirmCost,costConfirmed,costPending,ready}',context)
 const p=context.page;p.clinic.value='81001';p.supply.value='9000001';p.quantity.value='1.25';p.selectedTime.value='2026-11-20T13:00';p.verificationBasis.value='SYNTHETIC verified actual use';return p
}
test('selected time becomes a confirmed fact and edits require reconfirmation',async()=>{
 let body;const p=mount(async(url,options)=>{body=JSON.parse(options.body);return {status:200,json:async()=>({requestReference:'UC/v1/UC16/test/CONSUMPTION_COST_CONFIRM/00000000-0000-0000-0000-000000000001',fact:{synthetic:true,clinicId:'81001',supplyId:'9000001',quantity:'1.25',occurredAt:'2026-11-20T05:00:00Z',cost:'2.50',sourceReference:'SYNTHETIC-COST-v2-1000001'}})}})
 assert.equal(Boolean(p.ready.value),false);await p.confirmCost();assert.equal(body.occurredAt,'2026-11-20T13:00:00+08:00');assert.equal(Boolean(p.ready.value),true)
 p.quantity.value='2';assert.equal(Boolean(p.ready.value),false);assert.equal(Boolean(p.costConfirmed.value),false)
})
test('unknown confirmation result remains pending and blocks repeated writes',async()=>{
 let calls=0;const p=mount(async()=>{calls++;throw Error('TEST lost connection')});await p.confirmCost();assert(p.costPending.value);await p.confirmCost();assert.equal(calls,1);assert.equal(Boolean(p.ready.value),false)
})
