<script setup>
import { computed, ref, watch } from 'vue'
import VisitPicker from '../components/VisitPicker.vue'

const csrf = document.querySelector('meta[name="adc-uc07-csrf"]')?.content
const visitQuery=typeof window!=='undefined'?new URLSearchParams(window.location.search):null
const initialId=key=>{const value=visitQuery?.get(key)||'';return /^[1-9]\d*$/.test(value)?value:''}
const patient = ref(initialId('patientId'))
const visit = ref(initialId('visitId'))
const details = ref(null)
const history = ref('')
const allergies = ref('')
const reason = ref('')
const notice = ref('')
const busy = ref(false)
const pendingKey = 'adc.uc07.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(pendingKey) || '' } catch { storageAvailable = false }

const validId = value => /^[1-9]\d*$/.test(value)
const ready = computed(() => Boolean(csrf && validId(patient.value) && validId(visit.value)))
const route = computed(() => '/api/v1/staff/visits/' + visit.value + '/health')
const modified = computed(() => details.value && (
  history.value.trim() !== (details.value.snapshot?.medicalHistory ?? '') ||
  allergies.value.trim() !== (details.value.snapshot?.allergyInformation ?? '')
))
watch([patient, visit], () => {
  details.value = null
  history.value = ''
  allergies.value = ''
  reason.value = ''
  notice.value = ''
})

async function readHealth() {
  if (!ready.value || busy.value) return
  details.value = null
  busy.value = true
  try {
    const response = await fetch(route.value + '?patientId=' + patient.value, {
      headers: { 'X-CSRF-Token': csrf }, cache: 'no-store', redirect: 'error',
    })
    if (!response.ok) throw new Error('READ_FAILED')
    const loaded = await response.json()
    if (!Array.isArray(loaded.priorTreatments) || (loaded.snapshot !== null && !validId(loaded.snapshot?.id))) {
      throw new Error('INVALID_RESPONSE')
    }
    details.value = loaded
    history.value = loaded.snapshot?.medicalHistory ?? ''
    allergies.value = loaded.snapshot?.allergyInformation ?? ''
    reason.value = ''
    notice.value = ''
  } catch {
    notice.value = '无法读取健康资料，请核对就诊编号及访问权限。'
  } finally {
    busy.value = false
  }
}

async function saveHealth() {
  if (!ready.value || !modified.value || !reason.value.trim() || busy.value || pending.value || !storageAvailable) return
  busy.value = true
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(pendingKey, key)
    pending.value = key
    const update = {
      patientId: patient.value, expectedSnapshotId: details.value.snapshot?.id ?? '0',
      medicalHistory: history.value.trim() || null, allergyInformation: allergies.value.trim() || null,
      changeContent: reason.value.trim(),
    }
    const response = await fetch(route.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify(update),
    })
    const result = await response.json()
    if (response.status === 200 && result.visitId === visit.value && validId(result.healthChangeId)) {
      sessionStorage.removeItem(pendingKey)
      pending.value = ''
      details.value = { ...details.value, snapshot: { id: result.healthChangeId,
        medicalHistory: update.medicalHistory, allergyInformation: update.allergyInformation } }
      history.value = update.medicalHistory ?? ''
      allergies.value = update.allergyInformation ?? ''
      reason.value = ''
      notice.value = '健康信息已保存，记录编号 ' + result.healthChangeId + '。'
    } else if ([400, 401, 403, 409].includes(response.status) && typeof result.code === 'string') {
      sessionStorage.removeItem(pendingKey)
      pending.value = ''
      details.value = null
      notice.value = '本次变更未保存，请重新查询并核实。'
    }
  } catch {
    notice.value = pending.value ? '保存结果待核对。' : '无法保存请求标识。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="visit-health">
    <h1>患者健康信息</h1>
    <VisitPicker @selected="patient=$event.patientId;visit=$event.visitId" />
    <p v-if="!csrf">请从已授权的牙医工作台打开。</p>
    <form @submit.prevent="readHealth">
      <fieldset :disabled="!csrf || busy">
        <label>患者编号<input v-model.trim="patient" inputmode="numeric"></label>
        <label>本次就诊编号<input v-model.trim="visit" inputmode="numeric"></label>
        <button :disabled="!ready">查询资料</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">保存结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="details">
      <h2>既往诊疗</h2>
      <p v-if="!details.priorTreatments.length">授权范围内暂无既往诊疗记录。</p>
      <ul v-else>
        <li v-for="row in details.priorTreatments" :key="row.recordId">
          {{ row.treatment || '未实施诊疗' }} · {{ row.result }}
        </li>
      </ul>
      <form @submit.prevent="saveHealth">
        <fieldset :disabled="busy || !!pending || !storageAvailable">
          <legend>本次核实</legend>
          <p>未知信息可留空。已核实无病史或过敏时，请记录明确结论。</p>
          <label>病史<textarea v-model="history"></textarea></label>
          <label>过敏信息<textarea v-model="allergies"></textarea></label>
          <label>变更及核实说明<textarea v-model="reason"></textarea></label>
          <button :disabled="!modified || !reason.trim()">保存已核实变更</button>
        </fieldset>
      </form>
    </template>
  </section>
</template>

<style scoped>
.visit-health { display: grid; gap: 12px; }
fieldset { display: grid; gap: 12px; max-width: 44rem; }
label { display: grid; gap: 4px; }
textarea { min-height: 4rem; }
button { justify-self: start; }
</style>
