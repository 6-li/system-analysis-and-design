import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {recordId,recordScope,sameRecordScope,readQuarterRecord} from '../src/services/quarterly-records.js'
test('record identity and full scope reject malformed and duplicate selections',()=>{
 assert.equal(recordId('42'),true);assert.equal(recordId('042'),false);assert.equal(recordScope('81001,81001'),null);assert.equal(recordScope(''),null);assert.deepEqual(recordScope('81002,81001'),['81001','81002']);assert(sameRecordScope(['81001','81002'],'81002,81001'));assert(!sameRecordScope(['81001'],'81001,81002'))
})
test('detail reloads server frozen data with explicit scope, version and no-store',async()=>{
 const old=globalThis.fetch;let request
 globalThis.fetch=async(url,options)=>{request={url,options};return {ok:true,json:async()=>({calculationId:'77',version:2,clinics:['81001','81002'],performance:[{dentistId:'79001',amount:'330.00'}]})}}
 try{const result=await readQuarterRecord('calculations','77','81001,81002','TEST csrf',2);assert.equal(result.performance[0].amount,'330.00');assert.match(request.url,/version=2/);assert.equal(request.options.cache,'no-store');assert.equal(request.options.headers['X-CSRF-Token'],'TEST csrf')}finally{globalThis.fetch=old}
})
test('mismatched record scope or version never becomes a selected record',async()=>{
 const old=globalThis.fetch;globalThis.fetch=async()=>({ok:true,json:async()=>({calculationId:'77',version:1,clinics:['81001']})})
 try{await assert.rejects(readQuarterRecord('calculations','77','81001,81002','TEST',1));await assert.rejects(readQuarterRecord('calculations','77','81001','TEST',2))}finally{globalThis.fetch=old}
})
test('directory fetches persisted records and clears selection on filter change or failure',async()=>{
 const source=fs.readFileSync(new URL('../src/components/QuarterlyRecordBrowser.vue',import.meta.url),'utf8'),events=[],watchers=[]
 let fail=false
 const ctx={ref:v=>({value:v}),computed:fn=>({get value(){return fn()}}),watch:(deps,fn)=>watchers.push(fn),defineProps:()=>({kind:'previews',csrf:'TEST'}),defineEmits:()=>((...args)=>events.push(args)),recordScope,sameRecordScope,recordId,URLSearchParams,fetch:async()=>{if(fail)throw Error('TEST unavailable');return {ok:true,json:async()=>({quarter:'2026-Q4',clinics:['81001','81002'],items:[{statisticId:'42',quarter:'2026-Q4',clinics:['81001','81002']}]})}}}
 vm.runInNewContext(source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')+';globalThis.panel={quarter,clinics,items,message,search,loaded}',ctx)
 const p=ctx.panel;p.quarter.value='2026-Q4';await p.search();assert.equal(p.items.value[0].statisticId,'42');assert(events.some(e=>e[0]==='invalidate'));watchers[0]();assert.equal(p.items.value.length,0);assert.equal(p.loaded.value,false)
 fail=true;await p.search();assert.equal(p.loaded.value,false);assert.match(p.message.value,/失败/)
})
