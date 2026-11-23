<script setup>
import { computed, ref } from 'vue'

const csrf = document.querySelector('meta[name="adc-uc15-csrf"]')?.content
const clinic = ref('')
const requirement = ref('')
const lookup = ref('')
const mode = ref('')
const result = ref('')
const discrepancy = ref('')
const received = ref('')
const cost = ref('')
const expiry = ref('')
const saved = ref(null)
const busy = ref(false)
const notice = ref('')
const storageKey = 'adc.uc15.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = text => /^[1-9]\d*$/.test(text)
const ready = computed(() => Boolean(csrf && !busy.value && !pending.value && storageAvailable &&
  id(clinic.value) && id(requirement.value) && lookup.value && result.value && (
    mode.value === 'accepted'
      ? /^\d{1,15}(\.\d{1,3})?$/.test(received.value) && /[1-9]/.test(received.value)
        && /^\d{1,16}(\.\d{1,2})?$/.test(cost.value) && !!expiry.value
      : mode.value === 'pending' && !!discrepancy.value
  )))

async function save() {
  if (!ready.value) return
  busy.value = true
  notice.value = ''
  saved.value = null
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const accepted = mode.value === 'accepted'
    const response = await fetch('/api/v1/staff/clinics/' + clinic.value + '/purchase-requirements/' + requirement.value + '/receipt', {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ deliveryLookup: lookup.value, accepted, result: result.value, discrepancy: discrepancy.value || null,
        received: accepted ? received.value : null, cost: accepted ? cost.value : null, expiry: accepted ? expiry.value : null }),
    })
    const body = await response.json()
    const reference = body.requestReference
    const matching = typeof reference === 'string' && reference.startsWith('UC/v1/UC15/')
      && reference.endsWith('/SUPPLY_RECEIPT_RECORD/' + key)
    const receipt = body.receipt
    if (response.status === 200 && matching && receipt?.clinicId === clinic.value
        && receipt.requirementId === requirement.value && id(receipt.receiptId)
        && ['差异待处理', '已入库'].includes(receipt.status)) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      saved.value = receipt
      notice.value = '验收记录 ' + receipt.receiptId + ' · ' + receipt.status
    } else if ((response.status === 409 && matching && body.code === 'SUPPLY_RECEIPT_REJECTED')
        || (response.status === 400 && body.code === 'INVALID_SUPPLY_RECEIPT')
        || ([401, 403].includes(response.status) && body.code === 'SUPPLY_RECEIPT_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未保存，请核对交付、验收资料和库存关联。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="receipt">
    <h1>医疗用品验收入库</h1>
    <p v-if="!csrf">请从已授权的采购工作台打开。</p>
    <form @submit.prevent="save">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <label>诊所编号<input v-model.trim="clinic" inputmode="numeric"></label>
        <label>采购需求编号<input v-model.trim="requirement" inputmode="numeric"></label>
        <label>交付核对编号<input v-model.trim="lookup"></label>
        <label>办理结果<select v-model="mode">
          <option value="">请选择</option><option value="accepted">合格入库</option><option value="pending">差异待处理</option>
        </select></label>
        <label>实际验收情况<textarea v-model.trim="result"></textarea></label>
        <label>交付差异说明<textarea v-model.trim="discrepancy"></textarea></label>
        <template v-if="mode === 'accepted'">
          <label>验收实收量<input v-model.trim="received" inputmode="decimal" placeholder="例如6，最多三位小数"></label>
          <label>采购成本（元）<input v-model.trim="cost" inputmode="decimal" placeholder="例如12.00，最多两位小数"></label>
          <label>有效期<input v-model="expiry" type="date"></label>
        </template>
        <button :disabled="!ready">保存验收结果</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">保存结果待核对，请保留请求编号 {{ pending }}。</p>
    <p v-if="saved?.status === '差异待处理'">差异已记录，库存未增加。</p>
    <p v-if="saved?.status === '已入库'">实收 {{ saved.received }} · 采购成本 {{ saved.cost }} 元 · 有效期 {{ saved.expiry }}</p>
    <p v-if="saved?.stockBalanceAfter != null">本次入库后库存 {{ saved.stockBalanceAfter }}</p>
  </section>
</template>
<style scoped>
.receipt, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
