<script setup>
import { computed, ref, watch } from 'vue'
import VisitPicker from '../components/VisitPicker.vue'

const unified=document.querySelector('meta[name="adc-ui-unification"]')?.content==='true'
const completed=ref([])
const visibleCatalog=computed(()=>catalogItems.value.map((row,index)=>({...row,index})).filter(row=>!unified||!selected.value?.projectScopes?.length||selected.value.projectScopes.some(p=>p.projectKey===row.projectKey)))
const chosenKey=computed(()=>catalogItems.value[Number(catalogChoice.value)]?.projectKey)
const catalogEnabled=document.querySelector('meta[name="adc-clinical-catalog-enabled"]')?.content==='true'
const catalogItems=ref([]),catalogChoice=ref(''),catalogNotice=ref('')
async function loadCatalog(){try{const r=await fetch('/api/v1/staff/course-treatment-catalog',{credentials:'same-origin',cache:'no-store',redirect:'error'});const d=await r.json();if(r.status!==200||d.synthetic!==true||!Array.isArray(d.items)||!d.version)throw Error();catalogItems.value=d.items;catalogNotice.value='合成价格，仅供演示。'}catch{catalogItems.value=[];catalogNotice.value='合成项目目录暂不可用，请重试后记录已实施项目。'}}
function chooseCatalog(){const row=catalogItems.value[Number(catalogChoice.value)];if(row){project.value=row.name;unit.value=row.unit}}
const patientNotesEnabled=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const patientFindings=ref(''),aftercareInstructions=ref('')

const csrf = document.querySelector('meta[name="adc-uc09-csrf"]')?.content
const visitQuery=typeof window!=='undefined'?new URLSearchParams(window.location.search):null
const initialId=key=>{const value=visitQuery?.get(key)||'';return /^[1-9]\d*$/.test(value)?value:''}
const patient = ref(initialId('patientId'))
const visit = ref(initialId('visitId'))
const consents = ref(null)
const consent = ref('')
const performed = ref('')
const project = ref('')
const unit = ref('')
const quantity = ref('')
const special = ref('')
const reason = ref('')
const busy = ref(false)
const notice = ref('')
const storageKey = 'adc.uc09.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const validId = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const route = computed(() => '/api/v1/staff/visits/' + visit.value + '/treatment')
const ready = computed(() => Boolean(csrf && validId(patient.value) && validId(visit.value)))
const selected = computed(() => consents.value?.find(row => row.consentResultId === consent.value))
const canSave = computed(() => ready.value && selected.value && (!unified||selected.value.projectScopes?.length&&chosenKey.value&&selected.value.projectScopes.some(p=>p.projectKey===chosenKey.value)) && (
  performed.value === 'yes' ? selected.value.consentResult === '同意' && project.value && unit.value && (!catalogEnabled || catalogItems.value.some(row=>row.name===project.value&&row.unit===unit.value)) &&
    /^\d{1,15}(\.\d{1,3})?$/.test(quantity.value) && /[1-9]/.test(quantity.value)
    : performed.value === 'no' && reason.value
))

function clearDraft() {
  catalogChoice.value=''
  patientFindings.value=''
  aftercareInstructions.value=''
  consent.value = ''
  performed.value = ''
  project.value = ''
  unit.value = ''
  quantity.value = ''
  special.value = ''
  reason.value = ''
}
watch([patient, visit], () => {
  consents.value = null
  notice.value = ''
  clearDraft()
})

async function readTreatment() {
  if (!ready.value || busy.value) return
  busy.value = true
  consents.value = null
  notice.value = ''
  clearDraft()
  try {
    const response = await fetch(route.value + '?patientId=' + patient.value, {
      headers: { 'X-CSRF-Token': csrf }, cache: 'no-store', redirect: 'error',
    })
    if (response.status !== 200) throw new Error('READ_FAILED')
    const data = await response.json()
    if (data.patientId !== patient.value || data.visitId !== visit.value || !Array.isArray(data.consents)) {
      throw new Error('INVALID_RESPONSE')
    }
    consents.value = data.consents
    completed.value=data.records||[]
  } catch {
    notice.value = '无法读取同意记录，请核对就诊及访问权限。'
  } finally {
    busy.value = false
  }
}

async function saveTreatment() {
  if (!canSave.value || busy.value || pending.value || !storageAvailable) return
  busy.value = true
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const actual = performed.value === 'yes'
    const response = await fetch(route.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...(unified&&selected.value.projectScopes?.length?{projectKey:chosenKey.value}:{}),
        patientId: patient.value, consentResultId: consent.value, performed: actual,
        project: actual ? project.value : null, unit: actual ? unit.value : null,
        quantity: actual ? quantity.value : null, ...(patientNotesEnabled?{patientFindings:patientFindings.value||null,aftercareInstructions:aftercareInstructions.value||null}:{}),
          specialRequirements: special.value || null,
        nonPerformanceReason: reason.value || null,
      }),
    })
    const result = await response.json()
    const matching = typeof result.requestReference === 'string' && result.requestReference.startsWith('UC/v1/UC09/')
      && result.requestReference.endsWith('/ACTUAL_TREATMENT_RECORD/' + key)
    if (response.status === 200 && matching && result.visitId === visit.value &&
        validId(result.clinicalRecordId) && result.recordVersionNo === 1) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      if(unified){const r=await fetch(route.value+'?patientId='+patient.value,{headers:{'X-CSRF-Token':csrf},cache:'no-store',redirect:'error'});if(r.status===200){const d=await r.json();completed.value=d.records||[]}}
      const savedConsent=consent.value
      clearDraft()
      if(unified)consent.value=savedConsent
      notice.value = '诊疗记录已保存，记录编号 ' + result.clinicalRecordId + '，版本 ' + result.recordVersionNo + '。'
    } else if ((response.status === 409 && result.code === 'TREATMENT_REJECTED' && matching)
        || (response.status === 400 && result.code === 'INVALID_TREATMENT_INPUT')
        || ([401, 403].includes(response.status) && result.code === 'TREATMENT_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未登记，请核对同意范围、就诊权限或填写内容。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
if(catalogEnabled)void loadCatalog()
</script>

<template>
  <section class="visit-treatment">
    <h1>实际诊疗记录</h1>
    <VisitPicker @selected="patient=$event.patientId;visit=$event.visitId" />
    <p v-if="!csrf">请从已授权的牙医工作台打开。</p>
    <form @submit.prevent="readTreatment">
      <fieldset :disabled="!csrf || busy">
        <label>患者编号<input v-model.trim="patient" inputmode="numeric"></label>
        <label>本次就诊编号<input v-model.trim="visit" inputmode="numeric"></label>
        <button :disabled="!ready">查询同意记录</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">登记结果待核对，请保留请求编号 {{ pending }}。</p>
    <p v-if="consents && !consents.length">本次就诊尚无同意结果，请先登记实际沟通结果。</p>
    <section v-if="unified&&completed.length"><h2>本次已登记项目</h2><table><thead><tr><th>项目</th><th>实施情况</th><th>数量</th><th>记录</th></tr></thead><tbody><tr v-for="row in completed" :key="row.clinicalRecordId"><td>{{row.project||'未实施'}}</td><td>{{row.implementationResult}}</td><td>{{row.quantity}} {{row.unit}}</td><td>{{row.clinicalRecordId}} · 版本{{row.version}}</td></tr></tbody></table></section>
    <form v-if="consents?.length" @submit.prevent="saveTreatment">
      <fieldset :disabled="busy || !!pending || !storageAvailable">
        <legend>登记本次实际诊疗</legend>
        <label>同意结果
          <select v-model="consent" required>
            <option value="" disabled>请选择已记录的沟通结果</option>
            <option v-for="row in consents" :key="row.consentResultId" :value="row.consentResultId">
              {{ row.consentResult }} · {{ row.proposedTreatment }} · {{ row.consentResultId }}
            </option>
          </select>
        </label>
        <fieldset v-if="unified&&selected?.projectScopes?.length"><legend>本组项目（逐项登记）</legend><button type="button" v-for="scope in selected.projectScopes" :key="scope.projectKey" @click="catalogChoice=String(catalogItems.findIndex(x=>x.projectKey===scope.projectKey));chooseCatalog();performed=''">登记{{scope.name}}{{completed.some(x=>x.projectKey===scope.projectKey)?'（已有记录）':''}}</button><p>每项分别保存，已保存的项目显示在上方。</p></fieldset>
        <p v-if="unified&&selected&&!selected.projectScopes?.length">此旧沟通记录未明确关联项目，请先到“诊疗同意”补充本次项目范围。</p>
        <label>实施情况
          <select v-model="performed" required>
            <option value="" disabled>请选择实施情况</option>
            <option value="yes" :disabled="selected?.consentResult !== '同意'">已实施</option>
            <option value="no">未实施</option>
          </select>
        </label>
        <label v-if="unified&&performed==='no'&&selected?.projectScopes?.length">未实施项目<select v-model="catalogChoice" @change="chooseCatalog"><option value="" disabled>选择本组项目</option><option v-for="row in visibleCatalog" :key="row.projectKey" :value="String(row.index)">{{row.name}}</option></select></label>
        <template v-if="performed === 'yes'">
          <label v-if="catalogEnabled">实际项目<select v-model="catalogChoice" @change="chooseCatalog"><option value="" disabled>选择合成项目</option><option v-for="row in visibleCatalog" :key="row.name+row.unit" :value="String(row.index)">{{row.name}} · {{row.unitPrice}} / {{row.unit}}</option></select></label><label v-else>实际项目<input v-model.trim="project" required maxlength="200"></label>
          <p v-if="catalogEnabled">{{catalogNotice}}</p>
          <label>计费单位<input v-model.trim="unit" :readonly="catalogEnabled" required maxlength="40"></label>
          <label>实际数量<input v-model.trim="quantity" inputmode="decimal" placeholder="例如1，最多三位小数" required></label>
        </template>
        <template v-if="patientNotesEnabled"><label>本次发现及向患者说明的问题<textarea v-model.trim="patientFindings" maxlength="4000" placeholder="医生填写，患者本人可查看；无记录时留空"></textarea></label><label>后续注意事项<textarea v-model.trim="aftercareInstructions" maxlength="4000" placeholder="填写本次实际告知患者的事项"></textarea></label></template>
        <label>特殊需求<textarea v-model.trim="special"></textarea></label>
        <label>未实施说明<textarea v-model.trim="reason" :required="performed === 'no'"></textarea></label>
        <button :disabled="!canSave">保存本项诊疗记录</button>
      </fieldset>
    </form>
  </section>
</template>

<style scoped>
.visit-treatment, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 44rem; }
label { display: grid; gap: 4px; }
textarea { min-height: 4rem; }
button { justify-self: start; }
</style>
