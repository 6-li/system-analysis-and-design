<script setup>
import { computed, ref, watch } from 'vue'
import VisitPicker from '../components/VisitPicker.vue'

const unified=document.querySelector('meta[name="adc-ui-unification"]')?.content==='true'
const catalog=ref(null),projectKeys=ref([])
async function loadCatalog(){try{const r=await fetch('/api/v1/staff/course-treatment-catalog',{cache:'no-store',redirect:'error'});const d=await r.json();if(r.status!==200||!d.version||!d.items?.every(x=>x.projectKey))throw Error();catalog.value=d}catch{catalog.value=null;notice.value='项目目录暂不可用，请稍后重试。'}}
const csrf = document.querySelector('meta[name="adc-uc08-csrf"]')?.content
const visitQuery=typeof window!=='undefined'?new URLSearchParams(window.location.search):null
const initialId=key=>{const value=visitQuery?.get(key)||'';return /^[1-9]\d*$/.test(value)?value:''}
const patient = ref(initialId('patientId'))
const visit = ref(initialId('visitId'))
const entries = ref(null)
const party = ref('')
const treatment = ref('')
const guardianRequired = ref(false)
const guardian = ref('')
const outcome = ref('')
const comments = ref('')
const busy = ref(false)
const notice = ref('')
const storageKey = 'adc.uc08.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }

const validId = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const route = computed(() => '/api/v1/staff/visits/' + visit.value + '/consent')
const ready = computed(() => Boolean(csrf && validId(patient.value) && validId(visit.value)))
const canSave = computed(() => ready.value && entries.value !== null && party.value && treatment.value &&
  (!unified || catalog.value && projectKeys.value.length) && outcome.value && (!guardianRequired.value || guardian.value) && (outcome.value === '同意' || comments.value))

function clearDraft() {
  projectKeys.value=[]
  party.value = ''
  treatment.value = ''
  guardianRequired.value = false
  guardian.value = ''
  outcome.value = ''
  comments.value = ''
}

watch([patient, visit], () => {
  entries.value = null
  notice.value = ''
  clearDraft()
})

async function fetchEntries() {
  const response = await fetch(route.value + '?patientId=' + patient.value, {
    headers: { 'X-CSRF-Token': csrf }, cache: 'no-store', redirect: 'error',
  })
  if (response.status !== 200) throw new Error('READ_FAILED')
  const data = await response.json()
  if (data.patientId !== patient.value || data.visitId !== visit.value || !Array.isArray(data.entries)) {
    throw new Error('INVALID_RESPONSE')
  }
  entries.value = data.entries
}

async function readConsent() {
  if (!ready.value || busy.value) return
  busy.value = true
  entries.value = null
  notice.value = ''
  try { await fetchEntries(); if(unified)await loadCatalog() }
  catch { notice.value = '无法读取沟通记录，请核对就诊和访问权限。' }
  finally { busy.value = false }
}

async function saveConsent() {
  if (!canSave.value || busy.value || pending.value || !storageAvailable) return
  busy.value = true
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch(route.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...(unified?{projectKeys:projectKeys.value,catalogVersion:catalog.value.version}:{}),
        patientId: patient.value, communicationParty: party.value, guardianRequired: guardianRequired.value,
        guardianParticipation: guardian.value || null, proposedTreatment: treatment.value,
        consentResult: outcome.value, comments: comments.value || null,
      }),
    })
    const result = await response.json()
    const matchingReference = typeof result.requestReference === 'string' &&
      result.requestReference.startsWith('UC/v1/UC08/') &&
      result.requestReference.endsWith('/TREATMENT_CONSENT_RECORD/' + key)
    if (response.status === 200 && result.visitId === visit.value && validId(result.consentResultId) && matchingReference) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      clearDraft()
      notice.value = '诊疗同意结果已保存，记录编号 ' + result.consentResultId + '。'
      entries.value = null
      try { await fetchEntries(); if(unified)await loadCatalog() }
      catch { notice.value += ' 暂时无法刷新记录。' }
    } else if (
      (response.status === 409 && result.code === 'CONSENT_REJECTED' && matchingReference) ||
      (response.status === 400 && result.code === 'INVALID_CONSENT_INPUT') ||
      ([401, 403].includes(response.status) && result.code === 'CONSENT_ACCESS_DENIED')
    ) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未登记，请核对输入或就诊权限。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="visit-consent">
    <h1>诊疗同意结果</h1>
    <VisitPicker @selected="patient=$event.patientId;visit=$event.visitId" />
    <p v-if="!csrf">请从已授权的牙医工作台打开。</p>
    <form @submit.prevent="readConsent">
      <fieldset :disabled="!csrf || busy">
        <label>患者编号<input v-model.trim="patient" inputmode="numeric"></label>
        <label>本次就诊编号<input v-model.trim="visit" inputmode="numeric"></label>
        <button :disabled="!ready">查询沟通记录</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">登记结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="entries !== null">
      <h2>本次就诊的沟通记录</h2>
      <p v-if="!entries.length">暂无沟通记录。</p>
      <ul v-else>
        <li v-for="entry in entries" :key="entry.consentResultId">
          <strong>{{ entry.consentResult }}</strong> · {{ entry.proposedTreatment }} · {{ entry.communicationParty }}
          <div v-if="entry.guardianParticipation">监护人参与情况 {{ entry.guardianParticipation }}</div>
          <div v-if="entry.projectScopes?.length">项目：{{entry.projectScopes.map(x=>x.name).join('、')}}</div>
          <div v-if="entry.comments">{{ entry.comments }}</div>
          <div>{{ entry.recordedBy }} · {{ entry.recordedAt }}</div>
        </li>
      </ul>
      <form @submit.prevent="saveConsent">
        <fieldset :disabled="busy || !!pending || !storageAvailable">
          <legend>登记实际沟通结果</legend>
          <label>实际沟通对象<input v-model.trim="party" required></label>
          <fieldset v-if="unified"><legend>本次沟通的项目（可多选）</legend><label class="check" v-for="item in catalog?.items||[]" :key="item.projectKey"><input type="checkbox" v-model="projectKeys" :value="item.projectKey">{{item.name==='洁牙'?'洁牙（洗牙）':item.name}}</label></fieldset>
          <label>拟治疗内容及范围<textarea v-model.trim="treatment" required></textarea></label>
          <label class="check"><input type="checkbox" v-model="guardianRequired">本次需要监护人参与或同意</label>
          <label>监护人参与情况<input v-model.trim="guardian" :required="guardianRequired"></label>
          <label>实际同意结论
            <select v-model="outcome" required>
              <option value="" disabled>请选择结论</option>
              <option>同意</option><option>拒绝</option><option>待确认</option>
            </select>
          </label>
          <label>相关意见或处理说明<textarea v-model.trim="comments" :required="!!outcome && outcome !== '同意'"></textarea></label>
          <button :disabled="!canSave">保存同意结果</button>
        </fieldset>
      </form>
    </template>
  </section>
</template>

<style scoped>
.visit-consent, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 44rem; }
label { display: grid; gap: 4px; }
.check { display: flex; align-items: center; gap: 8px; }
textarea { min-height: 4rem; }
button { justify-self: start; }
</style>
