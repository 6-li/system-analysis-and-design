<script setup>
import {ref,onMounted,watch} from 'vue'
import ScheduleCalendar from './ScheduleCalendar.vue'
import {calendarDate} from '../services/schedule-calendar.js'
import {receptionAvailability} from '../services/reception-availability.js'
const props=defineProps({initialDate:String})
const zone=ref(document.querySelector('meta[name="adc-clinic-zone"]')?.content||'')
const date=ref(props.initialDate||(zone.value?calendarDate(new Date().toISOString(),zone.value):''))
const entries=ref([]),busy=ref(false),notice=ref(''),complete=ref(false)
let pendingReload=false;
async function load(){
 if(!date.value)return;if(busy.value){pendingReload=true;return;}const requestedDate=date.value;busy.value=true;entries.value=[];notice.value='';complete.value=false;
 try{
  const r=await fetch('/api/v1/staff/booking-calendar?date='+encodeURIComponent(date.value),{credentials:'same-origin',cache:'no-store',redirect:'error'});
  if(r.status===404){
   const old=await fetch('/api/v1/staff/booking-options?date='+encodeURIComponent(date.value),{credentials:'same-origin',cache:'no-store',redirect:'error'});const d=await old.json();
   if(old.status!==200||!Array.isArray(d.windows)||!d.timeZone)throw Error();
   zone.value=d.timeZone;entries.value=d.windows.map(w=>({...w,activationStatus:'EFFECTIVE'}));notice.value='当前服务只能提供所选日期的接诊班次，尚未提供完整预约占用；暂不标注空闲。完整读取接口需下次加载后端版本，当前数据保留。';return;
  }
  const d=await r.json();if(r.status!==200||!d.timeZone)throw Error();entries.value=receptionAvailability(d);zone.value=d.timeZone;complete.value=true;
 }catch{entries.value=[];notice.value='日程读取失败，不能据此判断医生空闲；请重试。';}finally{busy.value=false;if(pendingReload||date.value!==requestedDate){pendingReload=false;void load();}}
}
function changeDate(value){if(value&&value!==date.value){date.value=value;void load();}}
watch(()=>props.initialDate,value=>{if(value)date.value=value})
onMounted(load)
</script>
<template><details class="reception-calendar"><summary>查看医生日程与空闲时间</summary>
 <p>前台只读查看本店医生。绿色为空闲，蓝色为已预约或跨店占用；具体项目须完整放入空闲区间，并由服务端再次核验。</p>
 <label>查看日期<input type="date" v-model="date" :disabled="busy"></label><button type="button" :disabled="busy||!date" @click="load">读取医生日程</button>
 <p role="status">{{notice}}</p>
 <ScheduleCalendar v-if="zone" :entries="entries" :time-zone="zone" :focus-date="date" :partial="!complete" title="前台医生日程" @date-change="changeDate"/>
 </details></template>
<style scoped>.reception-calendar{margin:16px 0;padding:16px;border:1px solid #ccd9d2;border-radius:8px}.reception-calendar summary{cursor:pointer;font-weight:600}.reception-calendar label{display:inline-flex;gap:8px;margin-right:12px}.reception-calendar p{margin:12px 0}</style>
