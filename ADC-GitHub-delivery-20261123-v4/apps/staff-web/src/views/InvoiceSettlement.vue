<script setup>
import { computed, ref } from 'vue'

const props=defineProps({initial:{type:Object,default:null}})
const csrf = document.querySelector('meta[name="adc-uc12-csrf"]')?.content
const visit = ref(props.initial?.visitId||'')
const invoice = ref(props.initial?.invoiceId||'')
const version = ref(props.initial?.invoiceVersionNo?String(props.initial.invoiceVersionNo):'')
const needsConfirmation = ref(false)
const applicant = ref('')
const amount = ref(props.initial?.total||'')
const basis = ref('')
const payment = ref(null)
const pending = ref('')
const notice = ref('')
const busy = ref(false)
const storageKey = 'adc.uc12.pending'
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = text => typeof text === 'string' && /^[1-9]\d*$/.test(text)
const ready = computed(() => id(visit.value) && id(invoice.value) && id(version.value) && Number(version.value) <= 2147483647)
const canStart = computed(() => ready.value && (!needsConfirmation.value || (
  applicant.value && /^[0-9]{1,16}\.\d{2}$/.test(amount.value) && basis.value
)))

async function submit(operation) {
  const verifying = operation === 'DEMO_SETTLEMENT_VERIFY'
  if (!csrf || !ready.value || busy.value || pending.value || !storageAvailable ||
      (verifying ? payment.value?.status !== '待核验' : !canStart.value)) return
  busy.value = true
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const paymentId = verifying ? payment.value.paymentId : null
    const response = await fetch('/api/v1/staff/visits/' + visit.value + '/demo-settlement', {
      method: 'POST', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(12000),
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ invoiceId: invoice.value, invoiceVersionNo: Number(version.value), operation, paymentId,
        confirmation: !verifying && needsConfirmation.value
          ? { applicant: applicant.value, amount: amount.value, basis: basis.value } : null }),
    })
    const result = await response.json()
    const match = typeof result.requestReference === 'string' && result.requestReference.startsWith('UC/v1/UC12/')
      && result.requestReference.endsWith('/' + operation + '/' + key)
    const saved = result.payment
    const validStatus = saved?.status === '待核验' ? !verifying
      : saved?.status === '已结算' && saved.receipt === 'SYNTHETIC-UC12-' + saved.paymentId
    if (response.status === 200 && match && saved?.synthetic === true && saved.invoiceId === invoice.value
        && saved.visitId === visit.value && saved.invoiceVersionNo === Number(version.value)
        && id(saved.paymentId) && (!verifying || saved.paymentId === paymentId) && validStatus) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      payment.value = saved
      notice.value = saved.status === '已结算' ? '模拟结算完成' : '模拟交易已登记，等待核验'
    } else if ((response.status === 409 && match && result.code === 'SETTLEMENT_REVIEW_REQUIRED')
        || (response.status === 400 && result.code === 'INVALID_SETTLEMENT_REQUEST')
        || ([401, 403].includes(response.status) && result.code === 'SETTLEMENT_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未办理，请核对账单、确认依据或访问权限。'
    } else {
      notice.value = '暂时无法核对交易结果。请保留请求编号，请勿重复提交。'
    }
  } catch {
    notice.value = pending.value
      ? '连接中断或演示已结束，交易结果暂时无法核对。请保留请求编号，请勿重复提交。'
      : '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}

function switchInvoice() {
  payment.value = null
  applicant.value = amount.value = basis.value = notice.value = ''
  needsConfirmation.value = false
}
</script>

<template>
  <section class="settlement">
    <h1>诊疗费用结算</h1>
    <p>课程模拟支付，不会实际扣款。</p>
    <p v-if="!csrf">请从课程演示用的费用结算工作台打开。</p>
    <form v-if="!payment" @submit.prevent="submit('DEMO_SETTLEMENT_START')">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <label>就诊编号<input v-model.trim="visit" inputmode="numeric"></label>
        <label>账单编号<input v-model.trim="invoice" inputmode="numeric"></label>
        <label>当前账单版本<input v-model.trim="version" inputmode="numeric"></label>
        <label><input type="checkbox" v-model="needsConfirmation">登记首版账单确认</label>
        <template v-if="needsConfirmation">
          <label>申请人<input v-model.trim="applicant"></label>
          <label>确认金额（元）<input v-model.trim="amount" inputmode="decimal" placeholder="例如30.00，须保留两位小数"></label>
          <label>确认依据<textarea v-model.trim="basis"></textarea></label>
        </template>
        <p v-if="!canStart">请填写就诊编号、账单编号和版本。勾选首版确认后，还需填写申请人、两位小数的金额和确认依据。</p>
        <button :disabled="!canStart">发起或查看模拟交易</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="payment">
      <h2>模拟交易 {{ payment.paymentId }}</h2>
      <p>账单 {{ payment.invoiceId }} · 版本 {{ payment.invoiceVersionNo }} · {{ payment.amount }} 元</p>
      <p v-if="payment.receipt">模拟凭据 {{ payment.receipt }}</p>
      <button v-if="payment.status === '待核验'" :disabled="busy || !!pending"
        @click="submit('DEMO_SETTLEMENT_VERIFY')">核验并完成模拟结算</button>
      <button v-else @click="switchInvoice">办理另一笔账单</button>
    </template>
  </section>
</template>
<style scoped>
.settlement, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label:not(:has(input[type='checkbox'])) { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
