<script setup>
import {ref,computed,watch} from 'vue'
import QuarterScopePicker from './QuarterScopePicker.vue'
import {recordScope,sameRecordScope,recordId} from '../services/quarterly-records.js'
import {beijingTime} from '../services/quarterly-report.js'
const props=defineProps({kind:{type:String,required:true},csrf:String,disabled:Boolean})
const emit=defineEmits(['select','invalidate'])
const today=new Date(Date.now()+8*3600000)
const quarter=ref(today.getUTCFullYear()+'-Q'+(Math.floor(today.getUTCMonth()/3)+1)),clinics=ref('81001,81002')
const items=ref([]),message=ref(''),busy=ref(false),next=ref(''),loaded=ref(false)
const ready=computed(()=>!!props.csrf&&!!recordScope(clinics.value)&&/^[0-9]{4}-Q[1-4]$/.test(quarter.value)&&!busy.value&&!props.disabled)
let generation=0
function clear(){generation++;items.value=[];message.value='';next.value='';loaded.value=false;emit('invalidate')}
watch([quarter,clinics,()=>props.csrf],clear)
async function search(more=false){
 if(!ready.value||more&&!next.value)return
 const token=++generation,q=quarter.value,scope=recordScope(clinics.value);busy.value=true;message.value='';emit('invalidate')
 if(!more){items.value=[];next.value='';loaded.value=false}
 try{
  const params=new URLSearchParams({quarter:q,clinics:scope.join(','),limit:'20'});if(more)params.set('beforeId',next.value)
  const response=await fetch('/api/v1/staff/quarterly/records/'+props.kind+'?'+params,{headers:{'X-CSRF-Token':props.csrf},cache:'no-store',redirect:'error'}),body=await response.json()
  if(token!==generation)return
  const field=props.kind==='previews'?'statisticId':'calculationId'
  if(!response.ok||body.quarter!==q||!sameRecordScope(body.clinics,scope)||!Array.isArray(body.items)||body.items.length>20||body.items.some(x=>!recordId(x[field])||x.quarter!==q||!sameRecordScope(x.clinics,scope)||(props.kind==='calculations'&&(!Number.isInteger(x.version)||x.version<1)))||body.nextBeforeId&&!recordId(body.nextBeforeId))throw Error('UNAVAILABLE')
  items.value=more?[...items.value,...body.items]:body.items;next.value=body.nextBeforeId||'';loaded.value=true
 }catch{if(token===generation){items.value=[];loaded.value=false;next.value='';message.value='记录读取失败，请重新查询或重新登录。'}}finally{busy.value=false}
}
</script>
<template><section class="record-browser"><h2>{{kind==='previews'?'查找已有统计快照':'查找已有核算记录'}}</h2><p>按季度和诊所查找服务器记录，重新登录后仍可查询。仅显示与所选诊所范围完全一致的记录。</p><QuarterScopePicker v-model:quarter="quarter" v-model:clinics="clinics" :disabled="busy||disabled"/><button type="button" :disabled="!ready" @click="search(false)">{{busy?'正在查询…':'查询历史记录'}}</button><p v-if="message" role="alert">{{message}}</p><p v-if="loaded&&!items.length">该季度和诊所范围下暂无记录，可调整筛选范围后再查。</p><div v-if="items.length" class="record-table"><table><thead><tr><th>季度 / 诊所</th><th>记录</th><th>生成时间</th><th>金额（元）</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.statisticId+'-'+item.calculationId"><td data-label="季度 / 诊所">{{item.quarter}}<br>{{item.clinics.join('、')}}</td><td data-label="记录">{{kind==='previews'?'快照 '+item.statisticId:'核算 '+item.calculationId+' · 版本 '+item.version}}</td><td data-label="生成时间">{{beijingTime(item.generatedAt)}}</td><td data-label="金额（元）">{{kind==='previews'?'应收 '+(item.receivable??'待核实'):'净利润 '+(item.netProfit??'待核实')}}</td><td data-label="状态">{{kind==='previews'?item.readiness:(item.reviewed?'财务已复核':item.status)}}</td><td data-label="操作"><button type="button" :disabled="busy||disabled" @click="emit('select',item)">{{kind==='previews'?'查看并使用快照':'查看本版核算'}}</button></td></tr></tbody></table></div><button v-if="next" type="button" :disabled="!ready" @click="search(true)">加载更多记录</button></section></template>
<style scoped>.record-browser{padding:20px;border:1px solid #d9e5df;border-radius:14px;display:grid;gap:12px;background:#fff}.record-browser p{margin:0;color:#5a7168;font-size:13px}.record-table{max-width:100%;overflow-x:auto;min-width:0}table{min-width:720px;width:100%;border-collapse:collapse}th,td{padding:12px;text-align:left;border-bottom:1px solid #dfebe5}th{background:#f1f6f3}button{justify-self:start}h2{margin:0}@media(max-width:680px){.record-browser{padding:14px}.record-table{overflow:visible}table,tbody,tr,td{display:block;min-width:0;width:auto}thead{display:none}tr{border:1px solid #dce8e1;border-radius:10px;padding:10px;margin-top:10px}td{display:grid;grid-template-columns:5rem minmax(0,1fr);gap:10px;border:0;padding:7px 0;overflow-wrap:anywhere}td::before{content:attr(data-label);color:#6b8077;font-size:12px}td button{width:100%}}</style>
