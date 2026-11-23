<script setup>
import {ref,computed,watch} from 'vue'
import {localCandidates,localText,offsetText} from '../api/booking-time.js'
const props=defineProps({patient:String,sourceReference:String,csrf:String,disabled:Boolean,initialDate:String,initialNeed:String,originalBasis:Object,lockedScheme:Object,autoSubmit:Boolean})
const emit=defineEmits(['confirmed','invalidate'])
const date=ref(props.initialDate||''),need=ref(props.initialNeed||''),options=ref(null),project=ref(''),windowKey=ref(''),localStart=ref(''),instant=ref(''),accepted=ref(false),basis=ref(''),busy=ref(false),message=ref('')
let attempt=0
const selectedProject=computed(()=>options.value?.projects.find(p=>p.projectId===project.value))
const window=computed(()=>options.value?.windows.find(w=>w.scheduleId+':'+w.versionNo===windowKey.value))
const candidates=computed(()=>{try{return options.value?localCandidates(localStart.value,options.value.timeZone):[]}catch{return []}})
const start=computed(()=>candidates.value.length===1?candidates.value[0]:candidates.value.includes(instant.value)?instant.value:'')
const scheme=computed(()=>{if(props.lockedScheme)return props.lockedScheme;const p=selectedProject.value,w=window.value;if(!p||!w||!start.value||!need.value.trim())return null;const old=props.originalBasis;const same=old&&old.primaryProjectId===p.projectId;const minutes=same?old.adoptedDurationMinutes:p.minutes;const version=same?old.projectVersion:p.projectVersion;const end=new Date(Date.parse(start.value)+minutes*60000).toISOString();if(Date.parse(start.value)<Date.parse(w.startsAt)||Date.parse(end)>Date.parse(w.endsAt))return null;return {effectiveScheduleRef:{scheduleId:w.scheduleId,versionNo:w.versionNo},startsAt:offsetText(start.value,options.value.timeZone),endsAt:offsetText(end,options.value.timeZone),treatmentRequest:need.value.trim(),durationBasis:{schemaVersion:1,basisMode:old?(same?'PRESERVED_ORIGINAL':'CHANGED_PROJECT_CURRENT'):'INITIAL_CURRENT',primaryProjectId:p.projectId,projectVersion:version,adoptedDurationMinutes:minutes}}})
function invalidate(){attempt++;accepted.value=false;emit('invalidate')}
watch(()=>JSON.stringify([props.patient,props.sourceReference,date.value]),()=>{options.value=null;project.value='';windowKey.value='';localStart.value='';invalidate()},{flush:'sync'})
watch(()=>JSON.stringify([project.value,windowKey.value,localStart.value,instant.value,need.value,basis.value]),invalidate,{flush:'sync'})
async function load(){if(props.disabled||busy.value||!date.value||!props.patient)return;invalidate();const ticket=attempt;busy.value=true;message.value='';try{const q=new URLSearchParams({date:date.value});if(props.sourceReference)q.set('sourceContextReference',props.sourceReference);const r=await fetch('/api/v1/staff/booking-options?'+q,{credentials:'same-origin',redirect:'error',cache:'no-store'});const d=await r.json();if(ticket!==attempt)return;if(r.status!==200||d.synthetic!==true||!Array.isArray(d.projects)||!Array.isArray(d.windows)||!d.timeZone)throw Error();options.value=d;message.value=d.windows.length?'请选择项目、牙医正式时段和开始时间。':'这一天没有正式生效的接诊安排，请选择其他日期或联系负责人安排。'}catch{if(ticket===attempt)message.value='无法读取可用时段，请核对前台权限和申请状态。'}finally{busy.value=false}}
function chooseWindow(){const w=window.value;if(w&&options.value)localStart.value=localText(w.startsAt,options.value.timeZone)}
watch(()=>[props.initialDate,props.initialNeed],([d,n])=>{date.value=d||'';need.value=n||''})
async function confirm(){if(props.disabled||busy.value||!scheme.value||!accepted.value||!basis.value.trim()||!props.csrf)return;const snapshot=JSON.stringify(scheme.value),ticket=attempt;busy.value=true;message.value='';try{const r=await fetch('/api/v1/staff/booking-confirmations',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':props.csrf},body:JSON.stringify({patientId:props.patient,scheme:scheme.value,confirmed:true,actingMode:'SELF',verificationBasis:basis.value.trim(),...(props.sourceReference?{sourceContextReference:props.sourceReference}:{})})});const d=await r.json();if(ticket!==attempt)return;if(r.status===409){options.value=null;invalidate();message.value='预约安排已变化或被占用，请重新查询并取得申请人确认。';return}if(r.status!==200||d.synthetic!==true||d.patientId!==props.patient||JSON.stringify(d.scheme)!==snapshot||!d.acceptanceEvidenceReference?.startsWith('SYNTHETIC-confirmation:'))throw Error();emit('confirmed',{patientId:props.patient,scheme:d.scheme,reference:d.acceptanceEvidenceReference,submit:props.autoSubmit});message.value=props.autoSubmit?'预约核验已记录，正在保存，请以最终预约回执为准。':'预约确认已记录，请点击下方按钮完成预约。'}catch{if(ticket===attempt){emit('invalidate');message.value='确认未完成，请核对登录和核验依据。不会自动创建预约。'}}finally{busy.value=false}}
</script>
<template>
 <section aria-label="确认预约安排" class="booking-picker">
  <p>此处确认门诊、医生和预约时间。到诊后的诊疗方案由医生检查后说明，并另行登记患者同意结果。</p>
  <p v-if="lockedScheme">患者已选择并确认以下预约安排。前台核验后办理，不可替换时间；失鲜或约满时请患者重新选择。</p>
  <p v-else>演示数据 · 请选择预约安排，核实申请人接受后再确认预约。无可用时段时请联系负责人安排。</p>
  <p v-if="lockedScheme">{{lockedScheme.startsAt}} — {{lockedScheme.endsAt}} · {{lockedScheme.durationBasis.adoptedDurationMinutes}}分钟 · {{lockedScheme.treatmentRequest}}</p>
  <template v-if="!lockedScheme">
  <label>预约日期<input type="date" v-model="date" :disabled="disabled||busy"></label>
  <label>诊疗需求<input v-model="need" :disabled="disabled||busy"></label>
  <button type="button" @click="load" :disabled="disabled||busy||!date||!patient">查询可用时段</button>
  </template>
  <template v-if="options||lockedScheme">
   <template v-if="!lockedScheme">
   <p>诊所时区：{{options.timeZone}}。查询结果不预占容量。</p>
   <label>诊疗项目<select aria-label="诊疗项目" v-model="project" :disabled="disabled||busy"><option value="">请选择项目</option><option v-for="p in options.projects" :key="p.projectId" :value="p.projectId">{{p.projectName}} · {{p.minutes}}分钟 · 版本{{p.projectVersion}}</option></select></label>
   <label>牙医及正式时段<select aria-label="牙医及正式时段" v-model="windowKey" @change="chooseWindow" :disabled="disabled||busy"><option value="">请选择正式时段</option><option v-for="w in options.windows" :key="w.scheduleId+':'+w.versionNo" :value="w.scheduleId+':'+w.versionNo">牙医 {{w.dentistId}} · {{localText(w.startsAt,options.timeZone)}} — {{localText(w.endsAt,options.timeZone)}}</option></select></label>
   <label>开始时间（诊所当地时间）<input type="datetime-local" step="1" v-model="localStart" :disabled="disabled||busy"></label>
   <label v-if="candidates.length>1">重复时刻选择<select v-model="instant"><option value="">请选择绝对时刻</option><option v-for="t in candidates" :key="t" :value="t">{{t}}</option></select></label>
   <p v-if="localStart&&!candidates.length">该当地时间不存在或格式不正确，请重新选择。</p>
   <p v-if="scheme">预约安排：{{localText(scheme.startsAt,options.timeZone)}} 至 {{localText(scheme.endsAt,options.timeZone)}}，{{scheme.durationBasis.adoptedDurationMinutes}}分钟。</p>
   <p v-else-if="project&&windowKey&&localStart">当前时间无法完整放入所选正式时段，请调整。</p>
   </template>
   <label>{{lockedScheme ? '预约信息核验说明' : '申请人确认核验依据'}}<input v-model="basis" :disabled="disabled||busy" :placeholder="lockedScheme ? '例如：已核对门诊、医生及患者选定时段' : '填写本次模拟沟通和核验情况'"></label>
   <label class="inline"><input type="checkbox" v-model="accepted" :disabled="disabled||busy||!scheme">{{lockedScheme ? '已核对门诊、医生及预约时间，按患者原选择办理' : '已核实申请人本人接受以上预约日期、医生及时段'}}</label>
   <button type="button" @click="confirm" :disabled="disabled||busy||!scheme||!accepted||!basis.trim()">{{autoSubmit ? '核验并通过预约' : '记录预约确认'}}</button>
  </template>
  <p role="status">{{message}}</p>
 </section>
</template>
<style scoped>.booking-picker{display:grid;gap:12px;flex-basis:100%;min-width:0;padding:12px;background:#f3f8f5;border-radius:8px}label{display:grid;gap:6px}input,select{min-width:0;width:100%;box-sizing:border-box}.inline{display:flex;align-items:center}.inline input{width:auto}button{justify-self:start}</style>



