<script setup>
import { ref } from 'vue'

const props=defineProps({initial:{type:Object,default:null}})
const csrf = document.querySelector('meta[name="adc-uc10-csrf"]')?.content
const visit = ref(props.initial?.visitId||'')
const invoice = ref(null)
const busy = ref(false)
const notice = ref('')
const storageKey = 'adc.uc10.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const validId = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)

async function generate() {
  if (!csrf || !validId(visit.value) || busy.value || pending.value || !storageAvailable) return
  busy.value = true
  invoice.value = null
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch('/api/v1/staff/visits/' + visit.value + '/invoice', {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key },
    })
    const result = await response.json()
    const bill = result.invoice
    const matching = typeof result.requestReference === 'string' && result.requestReference.startsWith('UC/v1/UC10/')
      && result.requestReference.endsWith('/TREATMENT_INVOICE_GENERATE/' + key)
    if (response.status === 200 && matching && bill?.visitId === visit.value && validId(bill.invoiceId)
        && Number.isSafeInteger(bill.invoiceVersionNo) && bill.invoiceVersionNo > 0 && bill.lines?.length > 0) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      invoice.value = bill
      notice.value = '已取得账单 ' + bill.invoiceId + '，版本 ' + bill.invoiceVersionNo + '。'
    } else if ((response.status === 409 && matching && result.code === 'INVOICE_REVIEW_REQUIRED')
        || (response.status === 400 && result.code === 'INVALID_INVOICE_REQUEST')
        || ([401, 403].includes(response.status) && result.code === 'INVOICE_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '未取得账单。请核对权限、诊疗明细和适用价格。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="invoice">
    <h1>生成诊疗账单</h1>
    <p v-if="!csrf">请从已授权的费用结算工作台打开。</p>
    <form @submit.prevent="generate">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <label>本次就诊编号<input v-model.trim="visit" inputmode="numeric" required></label>
        <button :disabled="!validId(visit)">生成或查看账单</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="invoice">
      <h2>账单明细</h2>
      <p>{{ invoice.status }}</p>
      <table>
        <thead><tr><th>项目</th><th>单位</th><th>数量</th><th>单价（元）</th><th>小计（元）</th></tr></thead>
        <tbody>
          <tr v-for="row in invoice.lines" :key="row.clinicalRecordId">
            <td>{{ row.item }}</td><td>{{ row.unit }}</td><td>{{ row.quantity }}</td>
            <td>{{ row.unitPrice }}</td><td>{{ row.amount }}</td>
          </tr>
        </tbody>
      </table>
      <p><strong>合计 {{ invoice.total }} 元</strong></p>
    </template>
  </section>
</template>

<style scoped>
.invoice, fieldset, label { display: grid; gap: 12px; }
fieldset { max-width: 34rem; }
button { justify-self: start; }
table { border-collapse: collapse; }
th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid #dce8ed; }
</style>
