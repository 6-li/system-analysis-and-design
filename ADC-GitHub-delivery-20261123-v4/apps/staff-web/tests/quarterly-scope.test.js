import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import {quarterlyIssueLabel} from '../src/services/quarterly-display.js'
const source=fs.readFileSync(new URL('../src/components/QuarterScopePicker.vue',import.meta.url),'utf8')
test('quarter picker submits explicit period and a unique ordered selected clinic scope',()=>{
 const props={quarter:'',clinics:''},events=[];const ctx={ref:v=>({value:v}),computed:fn=>({get value(){return fn()}}),watch:()=>{},defineProps:()=>props,defineEmits:()=>((event,value)=>{events.push([event,value]);if(event==='update:clinics')props.clinics=value})};vm.runInNewContext(source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')+';globalThis.panel={year,number,periodChanged,toggle}',ctx);
 const p=ctx.panel;p.year.value='2027';p.number.value='Q1';p.periodChanged();assert.deepEqual(events[0],['update:quarter','2027-Q1']);p.toggle('81002',true);p.toggle('81001',true);assert.equal(props.clinics,'81001,81002');p.toggle('81001',false);assert.equal(props.clinics,'81002')
})
test('expense marker has a readable label and legacy issue text remains unchanged',()=>{
 const legacy='SYNTHETIC expense-1 review required v1';assert.equal(quarterlyIssueLabel(legacy),legacy);const label=quarterlyIssueLabel('QUARTER_EXPENSE_V2:'+JSON.stringify({quarter:'2027-Q1',clinicId:81002,factId:'cost-2',factVersion:'1'}));assert.match(label,/2027-Q1/);assert.match(label,/81002/);assert.match(label,/cost-2/)
})
test('preview retains server-selected scope checks and displays only supplied frozen clinic summaries',()=>{
 const page=fs.readFileSync(new URL('../src/views/QuarterlyPreview.vue',import.meta.url),'utf8');assert.match(page,/saved.clinics.every\(id => clinics.value.includes\(id\)\)/);assert.match(page,/preview.clinicSummaries/);assert.match(page,/display\(row.expense\)/);assert.doesNotMatch(page,/9\.00元核实为8\.50元/)
})
