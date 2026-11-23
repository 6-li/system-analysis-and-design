<script setup>
import { computed, ref } from 'vue'
import {clinicInstant,courseCost} from '../components/consumption-time.js'

const flexible=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const selectedTime=ref(''),verificationBasis=ref(''),costFact=ref(null),costSnapshot=ref(''),costPending=ref('')
const costStorageKey='adc.uc16.cost-confirmation-pending'
try{costPending.value=sessionStorage.getItem(costStorageKey)||''}catch{}
const csrf = document.querySelector('meta[name="adc-uc16-csrf"]')?.content
const clinic = ref('')
const supply = ref('')
const quantity = ref('')
const occurredAt = ref('')
const proof = ref('')
const saved = ref(null)
const busy = ref(false)
const notice = ref('')
const pending = ref('')
const storageKey = 'adc.uc16.pending-command'
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const timestamp=computed(()=>flexible?clinicInstant(selectedTime.value):occurredAt.value)
const previewCost=computed(()=>courseCost(quantity.value))
const snapshot=()=>JSON.stringify([clinic.value,supply.value,quantity.value,timestamp.value,verificationBasis.value])
const costConfirmed=computed(()=>costFact.value&&costSnapshot.value===snapshot())
const canConfirm=computed(()=>flexible&&csrf&&!busy.value&&!pending.value&&!costPending.value&&storageAvailable&&clinic.value==='81001'&&supply.value==='9000001'&&!!previewCost.value&&!!timestamp.value&&Date.parse(timestamp.value)<=Date.now()&&!!verificationBasis.value)
const ready = computed(() => Boolean(csrf && !busy.value && !pending.value && !costPending.value && storageAvailable && id(clinic.value)
  && id(supply.value) && /^\d{1,15}(\.\d{1,3})?$/.test(quantity.value) && /[1-9]/.test(quantity.value)
  && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|[+-]\d\d:\d\d)$/.test(timestamp.value)
  && proof.value && (!flexible||costConfirmed.value)))
function currentTime(){selectedTime.value=new Date(Date.now()+8*3600000).toISOString().slice(0,19)}
async function confirmCost(){
 if(!canConfirm.value||costConfirmed.value)return
 busy.value=true;notice.value=''
 try{
  const key=crypto.randomUUID();sessionStorage.setItem(costStorageKey,key);costPending.value=key
  const response=await fetch('/api/v1/staff/clinics/'+clinic.value+'/consumption-cost-confirmations',{method:'POST',cache:'no-store',redirect:'error',headers:{'X-CSRF-Token':csrf,'Idempotency-Key':key,'Content-Type':'application/json'},body:JSON.stringify({supplyId:supply.value,quantity:quantity.value,occurredAt:timestamp.value,verificationBasis:verificationBasis.value})})
  const result=await response.json(),fact=result.fact
  const refMatch=typeof result.requestReference==='string'&&result.requestReference.startsWith('UC/v1/UC16/')&&result.requestReference.endsWith('/CONSUMPTION_COST_CONFIRM/'+key)
  const norm=value=>value.replace(/^0+(?=\d)/,'').replace(/(\.\d*?)0+$/,'$1').replace(/\.$/,'')
  if(response.status===200&&refMatch&&fact?.synthetic===true&&fact.clinicId===clinic.value&&fact.supplyId===supply.value&&typeof fact.quantity==='string'&&norm(fact.quantity)===norm(quantity.value)&&Date.parse(fact.occurredAt)===Date.parse(timestamp.value)&&fact.cost===previewCost.value&&/^SYNTHETIC-COST-v2-[1-9]\d*$/.test(fact.sourceReference)){
   sessionStorage.removeItem(costStorageKey);costPending.value='';costFact.value=fact;costSnapshot.value=snapshot();proof.value=fact.sourceReference;notice.value='本次成本依据已确认，可以保存耗用。'
  }else if([400,401,403].includes(response.status)||(response.status===409&&refMatch)){
   sessionStorage.removeItem(costStorageKey);costPending.value='';notice.value='本次未确认成本，请核对时间、数量和核实依据。'
  }else notice.value='成本确认结果待核对，请保留请求编号，不要重复提交。'
 }catch{notice.value='成本确认结果待核对，请保留请求编号，不要重复提交。'}finally{busy.value=false}
}

async function save() {
  if (!ready.value) return
  busy.value = true
  notice.value = ''
  saved.value = null
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch('/api/v1/staff/clinics/' + clinic.value + '/supply-consumption', {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ supplyId: supply.value, quantity: quantity.value, occurredAt: timestamp.value,
        proofReference: proof.value }),
    })
    const body = await response.json()
    const reference = body.requestReference
    const matching = typeof reference === 'string' && reference.startsWith('UC/v1/UC16/')
      && reference.endsWith('/SUPPLY_CONSUMPTION_RECORD/' + key)
    const row = body.consumption
    if (response.status === 200 && matching && row?.clinicId === clinic.value && row.supplyId === supply.value
        && id(row.consumptionId)) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      saved.value = row
      quantity.value = occurredAt.value = proof.value = selectedTime.value = verificationBasis.value = '';costFact.value=null;costSnapshot.value=''
      notice.value = '耗用记录 ' + row.consumptionId + ' 已保存'
    } else if ((response.status === 409 && matching && ['CONSUMPTION_REJECTED', 'CONSUMPTION_COST_REVIEW_REQUIRED'].includes(body.code))
        || (response.status === 400 && body.code === 'INVALID_CONSUMPTION_REQUEST')
        || ([401, 403].includes(response.status) && body.code === 'CONSUMPTION_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = body.code === 'CONSUMPTION_COST_REVIEW_REQUIRED'
        ? '成本依据待核实。本次未扣库存，可补正后重新提交。'
        : '本次未登记，请核对库存、发生时间及核实依据。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="consumption">
    <h1>医疗用品实际耗用</h1>
    <p v-if="!csrf">请从已授权的诊所负责人工作台打开。</p>
    <form @submit.prevent="save">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <label>诊所编号<input v-model.trim="clinic" inputmode="numeric"></label>
        <label>用品编号<input v-model.trim="supply" inputmode="numeric"></label>
        <label>实际耗用量<input v-model.trim="quantity" inputmode="decimal" placeholder="例如2，最多三位小数"></label>
        <template v-if="flexible"><label>实际发生时间（北京时间）<input v-model="selectedTime" type="datetime-local" step="1"></label><button type="button" :disabled="busy||!!costPending" @click="currentTime">使用当前时间</button><label>成本核实依据<textarea v-model.trim="verificationBasis" maxlength="4000" placeholder="填写本次耗用已核实的依据"></textarea></label><p>课程合成单价2.00元/件，本次数量对应成本：{{previewCost||'—'}}元。</p><button type="button" :disabled="!canConfirm||!!costConfirmed" @click="confirmCost">{{costConfirmed?'本次成本已确认':'确认本次成本依据'}}</button></template>
        <template v-else><label>实际发生时间（含时区偏移）<input v-model.trim="occurredAt" aria-describedby="consumption-time-format"></label>
        <small id="consumption-time-format">例如 2026-11-17T15:30:00+08:00</small></template>
        <label v-if="!flexible">已核实成本依据编号<input v-model.trim="proof"></label>
        <button :disabled="!ready">保存实际耗用</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="costPending" role="alert">成本确认结果待核对，请保留请求编号 {{costPending}}。</p>
    <p v-if="pending" role="alert">保存结果待核对，请保留原请求编号 {{ pending }}。</p>
    <p v-if="saved">实耗 {{ saved.quantity }} · 实际成本 {{ saved.cost }} 元 · 扣减后库存 {{ saved.stockBalanceAfter }}</p>
  </section>
</template>
<style scoped>
.consumption, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
