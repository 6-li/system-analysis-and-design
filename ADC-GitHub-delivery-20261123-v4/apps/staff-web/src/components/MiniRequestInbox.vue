<script setup>
import { onMounted, ref } from 'vue'
const enabled = document.querySelector('meta[name="adc-mini-inbox-enabled"]')?.content === 'true'
const batch=document.querySelector('meta[name="adc-booking-batch-enabled"]')?.content==='true'
const reasons=ref({}),keys=new Map()
const items = ref([]), cursor = ref(''), busy = ref(false), notice = ref('')
const types = { NEW:'新预约申请',CHANGE:'改约申请',CANCEL:'取消申请' }
async function load(more=false) {
  if (!enabled || busy.value) return
  busy.value=true; notice.value=''
  if(!more){items.value=[];cursor.value=''}
  try {
    const response=await fetch('/api/v1/staff/mini-requests'+(more&&cursor.value?'?cursor='+encodeURIComponent(cursor.value):''),
      { credentials:'same-origin',cache:'no-store',redirect:'error' })
    const data=await response.json()
    if(response.status!==200 || !Array.isArray(data.items) || data.items.some(row =>
      !/^[a-f0-9-]{36}$/.test(row.requestId) || !Object.hasOwn(types,row.type) || !Number.isInteger(row.requestVersion)||row.requestVersion<0))throw Error('INBOX_INVALID')
    items.value=more?[...items.value,...data.items]:data.items;cursor.value=data.nextCursor||''
    notice.value=items.value.length?'申请仍须人工核验，提交申请不等于正式预约。':'本页没有待处理申请。'
  }catch {items.value=[];cursor.value='';notice.value='暂时无法读取待处理申请，请检查登录、授权或收件服务。'}
  finally{busy.value=false}
}
function choose(row) {
  const route=row.type==='NEW'&&!row.associatedPatientId?'/staff/patients':'/staff/appointments'
  location.assign(route+'?'+new URLSearchParams({sourceRequest:row.requestId,sourceVersion:String(row.requestVersion)}))
}
async function reject(row){
 if(busy.value||!reasons.value[row.requestId]?.trim())return;
 let context;try{context=JSON.parse(document.querySelector('meta[name="adc-appointments-context"]')?.content||'null')}catch{}
 if(!context?.csrfToken){notice.value='请重新登录后拒绝申请。';return;}
 const reason=reasons.value[row.requestId].trim();const body={expectedRequestVersion:row.requestVersion,reason};
 const serialized=JSON.stringify(body);const previous=keys.get(row.requestId);
 if(previous&&previous.body!==serialized){notice.value='上一拒绝提交待核对，请先刷新待办，不要修改原因重发。';return;}
 const entry=previous||{body:serialized,key:crypto.randomUUID()};keys.set(row.requestId,entry);busy.value=true;
 try{
  const r=await fetch('/api/v1/staff/mini-requests/'+row.requestId+'/rejections',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':context.csrfToken,'Idempotency-Key':entry.key},body:entry.body});
  const result=await r.json();if(r.status!==200||result.status!=='REJECTED')throw Error();
  items.value=items.value.filter(x=>x.requestId!==row.requestId);keys.delete(row.requestId);notice.value='已拒绝申请，原预约保持。患者可查看原因后重新申请。';
 }catch{notice.value='拒绝结果未确认，请刷新待办核对；保留原请求标识，不要重复新建拒绝。';}
 finally{busy.value=false;}
}
defineExpose({refresh:()=>load()})
onMounted(()=>load())
</script>
<template>
  <section v-if="enabled" aria-label="小程序待处理申请" class="mini-inbox">
    <h2>小程序待处理申请</h2>
    <button type="button" :disabled="busy" @click="load()">刷新待办</button>
    <p role="status">{{ notice }}</p>
    <table v-if="items.length"><thead><tr><th>申请类型</th><th>申请人摘要</th><th>意向日期</th><th>接收时间</th><th>办理</th></tr></thead>
      <tbody><tr v-for="row in items" :key="row.requestId"><td>{{ types[row.type] }}</td><td>{{ row.applicantSummary }} · {{ row.contactSummary }}</td>
        <td>{{ row.preferredDate || '待沟通' }}</td><td>{{ row.receivedAt }}</td><td><button type="button" @click="choose(row)">
          {{ row.type==='NEW'&&!row.associatedPatientId?'核验并建档':'核验并办理' }}</button><template v-if="batch"><label>拒绝原因<input v-model="reasons[row.requestId]" :disabled="busy" maxlength="2000"></label><button type="button" :disabled="busy || !reasons[row.requestId]?.trim()" @click="reject(row)">拒绝申请</button></template><small>{{ row.requestId }} · 版本{{ row.requestVersion }}</small></td></tr></tbody>
    </table>
    <button v-if="cursor" type="button" :disabled="busy" @click="load(true)">继续读取待办</button>
    <p v-if="cursor">还有未读取的申请，本页为空也不代表全部办结。</p>
  </section>
</template>
<style scoped>.mini-inbox{padding:20px;margin:16px 0;background:#f0f5ef;border:1px solid #dce4df;border-radius:12px}small{display:block;font-size:11px;overflow-wrap:anywhere;color:#607674}table{width:100%;font-size:13px}button{white-space:nowrap}</style>
