<script setup>
import { reactive, ref } from 'vue'
import MiniRequestInbox from '../components/MiniRequestInbox.vue'

const csrf = document.querySelector('meta[name="adc-uc01-csrf"]')?.content
const storageKey = 'adc.uc01.pending.v1'
const form = reactive({ requestId: '', requestVersion: '', basis: '', name: '', identity: '', contact: '' })
const selectedRequest = new URLSearchParams(location.search)
if (/^[a-f0-9-]{36}$/.test(selectedRequest.get('sourceRequest') || '') && /^(0|[1-9]\d*)$/.test(selectedRequest.get('sourceVersion') || '')) {
  form.requestId = selectedRequest.get('sourceRequest'); form.requestVersion = selectedRequest.get('sourceVersion')
}
let sourcePreview = null
try {
  const encoded=document.querySelector('meta[name="adc-uc01-source-preview"]')?.content
  if(encoded) sourcePreview=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded),char=>char.charCodeAt(0))))
  if(sourcePreview){form.identity=sourcePreview.patientIdentification || '';form.contact=sourcePreview.contactInformation || ''}
}catch { sourcePreview=null }
const busy = ref(false)
const message = ref('')
const binding = reactive({patientId:'',requestId:'',version:0,basis:'',confirmed:false})
const bindingMessage=ref('')
const existing=reactive({candidate:null,confirmed:false,basis:'',requestId:'',version:0,linked:false,message:''})
async function lookupExisting(){
  if(!csrf||busy.value||pending.value)return
  const version=Number(form.requestVersion)
  if(!/^\d+$/.test(form.requestVersion)||!Number.isSafeInteger(version)||version>2147483646){existing.message='请刷新并选择当前申请。';return}
  busy.value=true;existing.candidate=null;existing.confirmed=false;existing.linked=false;existing.message=''
  try{
    const requestId=form.requestId
    const response=await fetch('/api/v1/staff/patient-account-bindings/existing-preview',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({requestId,requestVersion:version})})
    const body=await response.json()
    if(!response.ok||!/^\d+$/.test(body.patientId)||!body.patientName)throw Error('MATCH_UNAVAILABLE')
    existing.candidate=body;existing.requestId=requestId;existing.version=version;existing.basis=''
  }catch{existing.message='未找到可供核验的唯一旧档案，或申请已变化。请核对原申请，不要修改身份绕过。'}finally{busy.value=false}
}
async function linkExisting(){
  if(!csrf||busy.value||pending.value||!existing.candidate||!existing.confirmed||!existing.basis.trim())return
  busy.value=true;existing.message=''
  try{
    const response=await fetch('/api/v1/staff/patient-account-bindings/existing',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},
      body:JSON.stringify({requestId:existing.requestId,requestVersion:existing.version,patientId:existing.candidate.patientId,verificationBasis:existing.basis,identityConfirmed:true})})
    const body=await response.json()
    if(!response.ok||body.bound!==true||body.reusedExistingPatient!==true||body.patientId!==existing.candidate.patientId||body.requestId!==existing.requestId||body.requestVersion!==existing.version+1)throw Error('LINK_UNCONFIRMED')
    existing.linked=true;existing.message='已关联原档案并绑定本人账号，请继续办理预约。'
  }catch{existing.message='关联未确认。请刷新待办核对原申请，勿重复建档或改绑其他人。'}finally{busy.value=false}
}
function continueExisting(){if(existing.linked)location.assign('/staff/appointments?'+new URLSearchParams({sourceRequest:existing.requestId,sourceVersion:String(existing.version+1)}))}
async function bindAccount(){
  if(busy.value||!csrf||!binding.confirmed||!binding.basis.trim()||!binding.patientId)return
  busy.value=true
  try{
    const response=await fetch('/api/v1/staff/patient-account-bindings',{method:'POST',credentials:'same-origin',redirect:'error',cache:'no-store',
      headers:{'Content-Type':'application/json','X-CSRF-Token':csrf},body:JSON.stringify({requestId:binding.requestId,requestVersion:binding.version,
        patientId:binding.patientId,verificationBasis:binding.basis,identityConfirmed:true})})
    const body=await response.json()
    bindingMessage.value=response.ok&&body.bound===true?'本人账号绑定已确认，患者重新查询即可查看本人预约。':'绑定未确认，请核对申请账号、身份和当前版本。'
  }catch{bindingMessage.value='绑定结果待核对，请勿改绑其他患者。'}finally{busy.value=false}
}
const pending = ref('')
let canStore = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { canStore = false }

async function create() {
  if (!csrf || busy.value || pending.value || !canStore) return
  const version = Number(form.requestVersion)
  if (!/^\d+$/.test(form.requestVersion) || !Number.isSafeInteger(version) || version > 2147483647) {
    message.value = '请填写有效的申请版本。'
    return
  }
  const key = crypto.randomUUID()
  busy.value = true
  try {
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch('/api/v1/staff/patients', {
      method: 'POST', credentials: 'same-origin', redirect: 'error', cache: 'no-store',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-CSRF-Token': csrf },
      body: JSON.stringify({ patientName: form.name, identityInformation: form.identity, contactInformation: form.contact,
        source: { kind: 'MINI_REQUEST', requestId: form.requestId, expectedRequestVersion: version,
          identityMatchBasis: form.basis } }),
    })
    const result = await response.json()
    const confirmed = response.status === 201 && result.sourceKind === 'MINI_REQUEST' &&
      result.sourceAssociation?.requestId === form.requestId &&
      result.sourceAssociation?.patientId === result.patient?.patientId && !!result.patient?.patientId &&
      result.sourceAssociation.patientAccessGrantedByAssociation === false &&
      result.originalUc01SuccessAudit?.ucCode === 'UC01' &&
      result.originalUc01SuccessAudit?.role === 'COMMITTED_RESULT' && !!result.originalUc01SuccessAudit?.eventId &&
      result.extensionResultEvent?.source === 'MINI_EXTENSION' &&
      result.extensionResultEvent?.role === 'COMMITTED_RESULT' && !!result.extensionResultEvent?.eventId
    if (confirmed || ([400, 401, 403, 404, 409, 422].includes(response.status) && typeof result.code === 'string')) {
      if(confirmed){binding.patientId=result.patient.patientId;binding.requestId=form.requestId;binding.version=result.sourceAssociation.version;binding.confirmed=false;binding.basis='';bindingMessage.value=''}
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      const explanations = {
        FORBIDDEN: '当前会话或课堂身份核验未通过。请核对当前登录岗位及核验资料，不要改写患者身份以绕过检查。',
        AUTHENTICATION_REQUIRED: '登录已失效，请重新登录前台工作台。',
        MINI_PATIENT_SOURCE_STALE: '申请版本已变化或已关联患者，请刷新待办核对原申请。',
        PATIENT_IDENTITY_ALREADY_EXISTS: '相同患者识别信息已有档案，请核对既有患者，不要重复建档。',
        INVALID_INPUT: '填写内容不符合要求，请核对申请编号、版本和必填资料。',
      }
      message.value = confirmed ? '已建档并关联申请，患者编号 ' + result.patient.patientId
        : (explanations[result.code] || '建档未完成，请核对申请和身份。') + '（' + response.status + ' / ' + result.code + '）'
    } else {
      message.value = '建档结果待核对，请勿再次提交。'
      if (typeof result.requestReference === 'string' && result.requestReference) {
        sessionStorage.setItem(storageKey, result.requestReference)
        pending.value = result.requestReference
      }
    }
  } catch {
    message.value = pending.value ? '建档结果待核对，请勿再次提交。' : '无法保存请求标记。'
  } finally { busy.value = false }
}
</script>

<template>
  <section>
    <MiniRequestInbox />
    <h1>从小程序申请建档</h1>
    <aside v-if="sourcePreview"><h2>所选申请的自述信息</h2><p>申请人自述：{{ sourcePreview.applicantInformation }}</p>
      <p>以下身份和联系方式来自申请，尚未核验；患者姓名与核验依据仍需工作人员确认填写。</p></aside>
    <p v-if="!csrf">请先登录接待工作台。</p>
    <p role="status">{{ message }}</p>
    <p v-if="pending" role="alert">待核对请求 {{ pending }}</p>
    <form @submit.prevent="create">
      <fieldset :disabled="busy || !!pending || !csrf || !canStore">
        <label>申请编号 <input v-model.trim="form.requestId" required></label>
        <label>申请版本 <input v-model.trim="form.requestVersion" inputmode="numeric" required></label>
        <label>身份核验依据 <input v-model.trim="form.basis" required placeholder="记录本次如何核对申请人与就诊人（演示请注明课程模拟）"></label>
        <label>患者姓名 <input v-model.trim="form.name" required maxlength="100" placeholder="填写核对后的就诊人姓名，课程演示使用虚构姓名"></label>
        <label>患者识别信息 <input v-model.trim="form.identity" required></label>
        <label>联系方式 <input v-model.trim="form.contact" required></label>
        <button>核验并建档</button>
      </fieldset>
    </form>
    <section aria-label="关联已有患者档案">
      <h2>已有档案的患者</h2>
      <p>识别信息已存在时，核验后关联原档案，无需重复建档。</p>
      <button type="button" :disabled="busy || !!pending || !csrf" @click="lookupExisting">查询当前申请对应的旧档案</button>
      <form v-if="existing.candidate && !existing.linked" @submit.prevent="linkExisting">
        <p>患者 {{existing.candidate.patientName}} · 编号 {{existing.candidate.patientId}} · 联系方式 {{existing.candidate.contactHint}}</p>
        <label>核验依据<input v-model.trim="existing.basis" required maxlength="2000" placeholder="记录如何核对本账号、申请人与原档案为同一人"></label>
        <label><input v-model="existing.confirmed" type="checkbox">已核验为同一患者，确认关联原档案并建立本人读取绑定</label>
        <button :disabled="busy || !existing.confirmed || !existing.basis.trim()">确认关联已有档案</button>
      </form>
      <p role="status">{{existing.message}}</p>
      <button v-if="existing.linked" type="button" @click="continueExisting">继续办理预约</button>
    </section>
    <form v-if="binding.patientId" @submit.prevent="bindAccount">
      <h2>绑定已核验的本人账号</h2>
      <p>患者 {{binding.patientId}}。仅适用于独立患者账号提交的申请；旧共享演示申请不会自动迁入。</p>
      <label>本人核验依据<input v-model.trim="binding.basis" required maxlength="2000"></label>
      <label><input v-model="binding.confirmed" type="checkbox">已核对申请账号、本人与本档案一致，同意建立本人读取绑定</label>
      <button :disabled="busy || !binding.confirmed || !binding.basis.trim()">确认本人绑定</button>
      <p role="status">{{bindingMessage}}</p>
    </form>
  </section>
</template>

<style scoped>
fieldset { display: grid; gap: 1rem; min-width: 0; }
fieldset label { display: grid; gap: .4rem; min-width: 0; }
fieldset input { width: 100%; min-width: 0; box-sizing: border-box; }
fieldset button { justify-self: start; }
</style>
