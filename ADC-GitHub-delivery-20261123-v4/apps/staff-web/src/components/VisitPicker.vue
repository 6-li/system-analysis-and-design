<script setup>
import {ref} from 'vue'
const emit=defineEmits(['selected'])
const enabled=document.querySelector('meta[name="adc-ui-unification"]')?.content==='true'
const selectedVisit=ref(null)
const appointment=ref(''),lookupResult=ref(null),lookupNotice=ref('')
let lookupEpoch=0
function clearLookup(){lookupEpoch++;lookupResult.value=null;lookupNotice.value=''}
async function findAppointment(){
 if(!enabled||busy.value)return
 clearLookup();const target=appointment.value.trim(),ticket=lookupEpoch
 if(!/^[1-9]\d*$/.test(target)){lookupNotice.value='请输入有效的预约编号（正整数）。';return}
 busy.value=true
 try{
  const r=await fetch('/api/v1/staff/my-visits/by-appointment/'+target,{cache:'no-store',redirect:'error'});
  if(ticket!==lookupEpoch||appointment.value.trim()!==target)return
  if(r.status===404){lookupNotice.value='未找到本人接诊的已到诊记录，请核对预约编号；尚未到诊请先联系前台。';return}
  if(r.status!==200)throw Error('READ_FAILED')
  const body=await r.json()
  if(ticket!==lookupEpoch||appointment.value.trim()!==target)return
  if(body.appointmentId!==target||![body.patientId,body.visitId].every(v=>typeof v==='string'&&/^[1-9]\d*$/.test(v))||typeof body.registeredAt!=='string'||!Number.isFinite(Date.parse(body.registeredAt)))throw Error('INVALID_RESULT')
  lookupResult.value=body
 }catch{if(ticket===lookupEpoch)lookupNotice.value='暂时无法查找，请核对登录和访问权限后重试。'}finally{busy.value=false}
}
const registeredTime=value=>{const d=new Date(value);return !value||Number.isNaN(d.getTime())?'未提供':new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',dateStyle:'short',timeStyle:'short',hour12:false}).format(d)}
function selectVisit(row){selectedVisit.value=row;emit('selected',row)}
const date=ref(new Date(Date.now()+8*3600000).toISOString().slice(0,10)),rows=ref([]),notice=ref(''),busy=ref(false),cursor=ref('')
async function load(more=false){
 if(!enabled||busy.value||!date.value)return
 busy.value=true;notice.value=''
 try{
  const res=await fetch('/api/v1/staff/my-visits?date='+date.value+(more&&cursor.value?'&cursor='+cursor.value:''),{cache:'no-store',redirect:'error'}),body=await res.json()
  if(res.status!==200||body.date!==date.value||!Array.isArray(body.items))throw Error('READ_FAILED')
  rows.value=more?[...rows.value,...body.items]:body.items;cursor.value=body.nextCursor||''
  if(!rows.value.length)notice.value='当天暂无本人已到诊记录。'
 }catch{rows.value=[];notice.value='暂时无法读取接诊记录，请稍后重试。'}finally{busy.value=false}
}
</script>
<template>
 <fieldset v-if="enabled" class="visit-picker"><legend>选择本次接诊</legend>
 <div class="appointment-lookup"><div><h3>按预约编号查找</h3><p class="date-help">输入患者提供的预约编号，定位本人接诊的就诊记录。</p></div><form @submit.prevent="findAppointment" class="lookup-form"><label>预约编号<input v-model="appointment" inputmode="numeric" placeholder="请输入预约编号" autocomplete="off" @input="clearLookup"></label><button type="submit" :disabled="busy">{{busy?'查询中…':'查找就诊'}}</button></form>
 <div v-if="lookupResult" class="lookup-result"><strong>预约 {{lookupResult.appointmentId}} → 就诊 {{lookupResult.visitId}}</strong><p>患者 {{lookupResult.patientId}} · 登记 {{registeredTime(lookupResult.registeredAt)}}</p><button type="button" :disabled="busy" @click="selectVisit(lookupResult)">使用此就诊</button></div><p v-if="lookupNotice" role="status">{{lookupNotice}}</p></div>
 <p v-if="selectedVisit" class="selection-banner" role="status">正在处理：患者{{selectedVisit.patientId}} · 预约{{selectedVisit.appointmentId}} → 就诊{{selectedVisit.visitId}}</p>
 <details class="date-directory"><summary>按登记日期查看本人已到诊患者</summary><label>到诊登记日期（北京时间）<input type="date" v-model="date" :disabled="busy" @change="rows=[];cursor='';selectedVisit=null"></label><button type="button" :disabled="busy" @click="load(false)">查询本人已到诊患者</button>
 <p class="date-help">当前按登记到诊的日期查询；预约日期与登记日期可能不同。</p>

 <div v-if="rows.length" class="visit-table"><table><thead><tr><th>患者</th><th>预约编号</th><th>就诊编号</th><th>登记时间</th><th>本次已登记项目</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.visitId"><td>{{row.patientId}}</td><td>{{row.appointmentId}}</td><td>{{row.visitId}}</td><td>{{registeredTime(row.registeredAt)}}</td><td>{{row.projects||'尚未登记'}}</td><td><button type="button" :disabled="busy" @click="selectVisit(row)">使用此就诊</button><span class="links"><a :href="'/staff/visit-health?patientId='+row.patientId+'&visitId='+row.visitId">健康资料</a> · <a :href="'/staff/visit-consent?patientId='+row.patientId+'&visitId='+row.visitId">沟通同意</a> · <a :href="'/staff/visit-treatment?patientId='+row.patientId+'&visitId='+row.visitId">实际诊疗</a></span></td></tr></tbody></table></div>
 <button v-if="cursor" type="button" :disabled="busy" @click="load(true)">查看更多</button><p role="status">{{notice}}</p></details></fieldset>
</template>

<style scoped>
.visit-picker{max-width:100%;min-width:0}.visit-table{max-width:100%;overflow-x:auto}table{min-width:40rem;width:100%;border-collapse:collapse}td,th{padding:12px;text-align:left;vertical-align:top;border-bottom:1px solid #d6e1da}.links{display:block;margin-top:8px}.date-help{color:#5a6e68;font-size:.95rem}
.appointment-lookup{background:#f3f8f6;border:1px solid #dce8e2;border-radius:14px;padding:22px}.appointment-lookup h3{margin:0}.appointment-lookup p{margin:8px 0}.lookup-form{display:flex;align-items:end;gap:12px;flex-wrap:wrap}.lookup-form label{display:grid;gap:8px;flex:1;min-width:180px}.lookup-form input{width:100%}.lookup-result{background:white;border:1px solid #d4e3db;border-radius:10px;padding:16px;margin-top:16px}.date-directory{margin-top:20px}.date-directory summary{cursor:pointer;color:#46665b;font-weight:600;padding:8px 0}.selection-banner{padding:14px 18px;background:#e4f0e9;border-radius:10px;color:#225942}@media(max-width:600px){.appointment-lookup{padding:16px}.lookup-form button{width:100%}}
</style>
