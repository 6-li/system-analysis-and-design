<script setup>
import { computed, ref } from 'vue'

const csrf = document.querySelector('meta[name="adc-uc14-csrf"]')?.content
const clinic = ref('')
const supply = ref('')
const quantity = ref('')
const confirmation = ref('')
const requirement = ref('')
const explanation = ref('')
const saved = ref(null)
const notice = ref('')
const busy = ref(false)
const pending = ref('')
const storageKey = 'adc.uc14.pending'
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }

const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const ready = computed(() => Boolean(csrf && id(clinic.value) && !busy.value && !pending.value && storageAvailable))
const canRegister = computed(() => ready.value && id(supply.value) && !!confirmation.value
  && /^\d{1,15}(\.\d{1,3})?$/.test(quantity.value) && /[1-9]/.test(quantity.value))
const canReport = computed(() => ready.value && id(requirement.value) && !!explanation.value)

async function submit(unavailable) {
  if (unavailable ? !canReport.value : !canRegister.value) return
  busy.value = true
  notice.value = ''
  saved.value = null
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const route = '/api/v1/staff/clinics/' + clinic.value + '/purchase-requirements'
      + (unavailable ? '/' + requirement.value + '/unavailable' : '')
    const operation = unavailable ? 'PURCHASE_REQUIREMENT_UNAVAILABLE' : 'PURCHASE_REQUIREMENT_REGISTER'
    const response = await fetch(route, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify(unavailable ? { explanation: explanation.value }
        : { supplyId: supply.value, quantity: quantity.value, confirmationReference: confirmation.value }),
    })
    const result = await response.json()
    const matching = typeof result.requestReference === 'string' && result.requestReference.startsWith('UC/v1/UC14/')
      && result.requestReference.endsWith('/' + operation + '/' + key)
    const receipt = result.requirement
    if (response.status === 200 && matching && receipt?.clinicId === clinic.value && id(receipt.requirementId)
        && (unavailable ? receipt.requirementId === requirement.value && receipt.status === '无法供货待调整'
          : receipt.supplyId === supply.value && receipt.status === '待联系供应商')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      saved.value = receipt
      requirement.value = receipt.requirementId
      if (unavailable) explanation.value = ''
      else supply.value = quantity.value = confirmation.value = ''
      notice.value = '采购需求 ' + receipt.requirementId + ' · ' + receipt.status
    } else if ((response.status === 409 && matching && result.code === 'PURCHASE_REQUIREMENT_REJECTED')
        || (response.status === 400 && result.code === 'INVALID_PURCHASE_REQUEST')
        || ([401, 403].includes(response.status) && result.code === 'PURCHASE_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未登记，请核对负责人确认依据、诊所授权或采购需求状态。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="purchase">
    <h1>医疗用品采购需求</h1>
    <p v-if="!csrf">请从已授权的采购工作台打开。</p>
    <label>诊所编号<input v-model.trim="clinic" inputmode="numeric" :disabled="!csrf || busy || !!pending"></label>
    <form @submit.prevent="submit(false)">
      <fieldset :disabled="!ready">
        <legend>登记负责人已确认的需求</legend>
        <label>用品编号<input v-model.trim="supply" inputmode="numeric"></label>
        <label>采购需求数量<input v-model.trim="quantity" inputmode="decimal" placeholder="例如6，最多三位小数"></label>
        <label>负责人确认依据编号<input v-model.trim="confirmation"></label>
        <button :disabled="!canRegister">登记采购需求</button>
      </fieldset>
    </form>
    <form @submit.prevent="submit(true)">
      <fieldset :disabled="!ready">
        <legend>记录人工联系供应商的结果</legend>
        <label>采购需求编号<input v-model.trim="requirement" inputmode="numeric"></label>
        <label>无法供货及待调整说明<textarea v-model.trim="explanation"></textarea></label>
        <button :disabled="!canReport">保存无法供货情况</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="saved?.explanation">{{ saved.explanation }}</p>
    <p v-if="pending" role="alert">登记结果待核对，请保留请求编号 {{ pending }}。</p>
  </section>
</template>

<style scoped>
.purchase, form, fieldset { display: grid; gap: 12px; }
fieldset, .purchase > label { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
