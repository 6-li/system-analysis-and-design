<script setup>
import { computed, ref } from 'vue'
import QuarterlyRecordBrowser from '../components/QuarterlyRecordBrowser.vue'
import FinanceSummary from '../components/FinanceSummary.vue'
const courseQuarterly=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const selected=ref(null)
const csrf = document.querySelector('meta[name="adc-uc20-csrf"]')?.content
const calculation = ref(''), version = ref(''), clinics = ref(''), notes = ref('')
const snapshot = ref(null), busy = ref(false), message = ref('')
const storageKey = 'adc.uc20.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = text => /^[1-9]\d*$/.test(text)
const scope = computed(() => clinics.value.split(/[,，]/).map(v => v.trim()))
const target = computed(() => id(calculation.value) && id(version.value) && +version.value <= 2147483647 &&
  scope.value.every(id) && new Set(scope.value).size === scope.value.length
  ? '/api/v1/staff/quarterly/financial-reviews/' + calculation.value + '?' + new URLSearchParams({ version: version.value, clinics: scope.value.join(',') })
  : '')
const loadedFrom = ref('')
const ready = computed(() => csrf && !busy.value && !pending.value && storageAvailable && target.value &&
  target.value === loadedFrom.value && snapshot.value && notes.value.trim())
const headers = { 'X-CSRF-Token': csrf }
function invalidateSelection(){if(pending.value)return;snapshot.value=null;selected.value=null;loadedFrom.value='';calculation.value='';version.value='';clinics.value='';notes.value='';message.value=''}
async function selectRecord(item){if(busy.value||pending.value)return;invalidateSelection();calculation.value=item.calculationId;version.value=String(item.version);clinics.value=item.clinics.join(',');selected.value=item;await load()}
async function load() {
  if (!csrf || busy.value || !target.value || pending.value) return
  busy.value = true
  snapshot.value = null
  message.value = ''
  try {
    const res = await fetch(target.value, { headers, cache: 'no-store', redirect: 'error' })
    const body = await res.json()
    if (res.status !== 200 || body.calculationId !== calculation.value || body.version !== +version.value ||
        !/^[a-f0-9]{64}$/.test(body.basisHash)) throw new Error()
    snapshot.value = body
    loadedFrom.value = target.value
  } catch { message.value = '无法读取可复核版本，请核对权限、范围和版本。' }
  finally { busy.value = false }
}
async function approve() {
  if (!ready.value) return
  busy.value = true
  message.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const res = await fetch(target.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { ...headers, 'Content-Type': 'application/json', 'Idempotency-Key': key },
      body: JSON.stringify({ basisHash: snapshot.value.basisHash, notes: notes.value.trim() }),
    })
    const body = await res.json()
    const ref = body.requestReference
    const matches = typeof ref === 'string' && ref.startsWith('UC/v1/UC20/') && ref.endsWith('/QUARTER_FINANCIAL_PASS/' + key)
    if (res.status === 200 && matches && body.review?.result === '通过' && body.review.calculationId === calculation.value &&
        body.review.version === +version.value && typeof body.review.reviewId === 'string' && id(body.review.reviewId)) {
      snapshot.value = null
      notes.value = ''
      if(selected.value)selected.value={...selected.value,reviewed:true}
      message.value = '本版本财务复核已通过。复核编号：' + body.review.reviewId
    } else if (res.status === 409 && matches && body.code === 'FINANCIAL_REVIEW_REJECTED') {
      snapshot.value = null
      message.value = '复核未保存，请重新读取版本及来源。发现差异时请反馈总部登记待核实。'
    } else { return }
    sessionStorage.removeItem(storageKey)
    pending.value = ''
  } catch { if (!pending.value) message.value = '无法保存请求编号。' }
  finally { busy.value = false }
}
</script>
<template>
  <section class="financial-review">
    <h1>复核季度经营核算结果</h1>
    <p v-if="!csrf">请从已授权的总部财务工作台打开。</p>
    <QuarterlyRecordBrowser v-if="courseQuarterly&&csrf" kind="calculations" :csrf="csrf" :disabled="busy||!!pending" @select="selectRecord" @invalidate="invalidateSelection" />
    <p v-if="selected">已选择 {{selected.quarter}} · 诊所 {{selected.clinics.join('、')}} · 核算 {{selected.calculationId}} · 版本 {{selected.version}}<span v-if="selected.reviewed"> · 财务已复核</span></p>
    <form @submit.prevent="approve">
      <fieldset :disabled="!csrf || busy || !!pending">
        <details v-if="courseQuarterly"><summary>手动编号读取（兼容入口）</summary>
        <label>核算编号<input v-model.trim="calculation" inputmode="numeric" @input="snapshot = null;selected=null;notes=''"></label>
        <label>核算版本<input v-model.trim="version" inputmode="numeric" @input="snapshot = null;selected=null;notes=''"></label>
        <label>诊所范围（多个用逗号分隔）<input v-model.trim="clinics" @input="snapshot = null;selected=null;notes=''"></label>
        <button type="button" :disabled="!target" @click="load">读取本版本核算</button>
        </details>
        <template v-if="!courseQuarterly">        <label>核算编号<input v-model.trim="calculation" inputmode="numeric" @input="snapshot = null;selected=null;notes=''"></label>
        <label>核算版本<input v-model.trim="version" inputmode="numeric" @input="snapshot = null;selected=null;notes=''"></label>
        <label>诊所范围（多个用逗号分隔）<input v-model.trim="clinics" @input="snapshot = null;selected=null;notes=''"></label>
        <button type="button" :disabled="!target" @click="load">读取本版本核算</button>
        </template>
        <template v-if="snapshot">
          <FinanceSummary :report="snapshot" />
          <label v-if="!selected?.reviewed">复核说明<textarea v-model="notes" maxlength="2000"></textarea></label>
          <button v-if="!selected?.reviewed" :disabled="!ready">确认复核通过</button>
          <p>存在差异时请反馈总部运营人员登记待核实事项。</p>
        </template>
      </fieldset>
    </form>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="pending" role="alert">复核结果待核对，请保留请求编号 {{ pending }}，不要重复提交。</p>
  </section>
</template>
<style scoped>
.financial-review, fieldset, label { display: grid; gap: 10px; }
fieldset { max-width: 42rem; min-width: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
button { justify-self: start; }
</style>
