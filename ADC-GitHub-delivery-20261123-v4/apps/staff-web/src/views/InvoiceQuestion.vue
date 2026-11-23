<script setup>
import { computed, nextTick, ref, watch } from 'vue'

const unified=document.querySelector('meta[name="adc-ui-unification"]')?.content==='true'
const catalogItems=ref([])
async function loadCatalog(){try{const r=await fetch('/api/v1/staff/course-treatment-catalog',{cache:'no-store',redirect:'error'});const d=await r.json();if(r.status===200)catalogItems.value=d.items||[]}catch{catalogItems.value=[]}}
function chooseProject(row){const item=catalogItems.value.find(x=>x.projectKey===row.projectKey);if(item){row.project=item.name;row.unit=item.unit}}
if(unified)void loadCatalog()
const csrf = document.querySelector('meta[name="adc-uc11-csrf"]')?.content
const patient = ref('')
const visit = ref('')
const invoice = ref('')
const version = ref('')
const clinical = ref(document.querySelector('meta[name="adc-invoice-clinical"]')?.content === 'true')
const scope = ref('PRICE')
const note = ref('')
const corrections = ref({})
const applicant = ref('')
const acceptedAmount = ref('')
const correctionOpen = ref(false)
const data = ref(null)
const busy = ref(false)
const notice = ref('')
const storageKey = 'adc.uc11.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }

const OPEN = 'INVOICE_QUESTION_OPEN'
const CONFIRM = 'INVOICE_CLINICAL_CONFIRM'
const RECALCULATE = 'INVOICE_QUESTION_RECALCULATE'
const CORRECT = 'INVOICE_CLINICAL_CORRECT'
const ACCEPT = 'INVOICE_APPLICANT_CONFIRM'
const ready = computed(() => Boolean(csrf && [patient.value, visit.value, invoice.value, version.value]
  .every(id => /^[1-9]\d*$/.test(id)) && Number(version.value) <= 2147483647))
const route = computed(() => '/api/v1/staff/visits/' + visit.value + '/invoice-question')
const handling = computed(() => data.value?.handling)
const clinicalReady = computed(() => handling.value?.scope === 'CLINICAL' && handling.value.phase === 'PENDING')
const recalculateReady = computed(() => (handling.value?.scope === 'PRICE' && handling.value.phase === 'PENDING')
  || handling.value?.phase === 'CLINICAL_CONFIRMED')
const selectedCorrections = computed(() => Object.values(corrections.value))
const correctionReady = computed(() => selectedCorrections.value.length > 0 &&
  selectedCorrections.value.every(row => row.consent && row.project && row.unit && row.quantity))

function toggleCorrection(line, enabled) {
  if (enabled) corrections.value[line.clinicalRecordId] = {
    record: line.clinicalRecordId, consent: '', project: line.item, unit: line.unit, quantity: line.quantity,
  }
  else delete corrections.value[line.clinicalRecordId]
}

watch([patient, visit, invoice, version, clinical], () => {
  data.value = null
  note.value = ''
  notice.value = ''
  correctionOpen.value = false
  corrections.value = {}
  applicant.value = acceptedAmount.value = ''
})

async function read() {
  if (!ready.value || busy.value) return
  busy.value = true
  data.value = null
  notice.value = ''
  try {
    const query = new URLSearchParams({ patientId: patient.value, invoiceId: invoice.value,
      invoiceVersionNo: version.value, clinical: String(clinical.value) })
    const response = await fetch(route.value + '?' + query, {
      cache: 'no-store', redirect: 'error', headers: { 'X-CSRF-Token': csrf },
    })
    const loaded = await response.json()
    if (response.status !== 200 || loaded.invoice?.invoiceId !== invoice.value ||
        loaded.invoice?.visitId !== visit.value || loaded.invoice?.invoiceVersionNo !== Number(version.value)) throw new Error()
    data.value = loaded
  } catch {
    notice.value = '未取得账单，请核对编号、版本及权限。'
  } finally {
    busy.value = false
  }
}

async function submit(operation) {
  if (!ready.value || !data.value || !note.value || busy.value || pending.value || !storageAvailable) return
  busy.value = true
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch(route.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ patientId: patient.value, invoiceId: invoice.value,
        invoiceVersionNo: Number(version.value), operation, note: note.value,
        clinicalQuestion: operation === OPEN && scope.value === 'CLINICAL',
        questionReference: operation === OPEN ? null : handling.value.questionReference,
        ...(operation === CORRECT ? { corrections: selectedCorrections.value } : {}),
        ...(operation === ACCEPT ? { applicant: { identity: applicant.value, acceptedAmount: acceptedAmount.value } } : {}),
      }),
    })
    const result = await response.json()
    const matching = typeof result.requestReference === 'string' && result.requestReference.startsWith('UC/v1/UC11/')
      && result.requestReference.endsWith('/' + operation + '/' + key)
    const review = result.review
    const expected = Number(version.value) + (operation === RECALCULATE ? 1 : 0)
    if (response.status === 200 && matching && review?.invoice?.invoiceId === invoice.value
        && review.invoice.visitId === visit.value && review.invoice.invoiceVersionNo === expected) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      if (operation === RECALCULATE) {
        version.value = String(expected)
        await nextTick()
      }
      data.value = review
      note.value = ''
      notice.value = operation === RECALCULATE ? '同一账单已重算，请重新取得申请人确认。'
        : operation === CONFIRM ? '已保存牙医核实依据。'
        : operation === CORRECT ? '诊疗修正已保存，等待费用结算人员重算。'
        : operation === ACCEPT ? '申请人已确认当前账单，状态为待结算。'
        : '费用疑问已登记，账单待核实。'
    } else if ((response.status === 409 && matching && result.code === 'INVOICE_REVIEW_REJECTED')
        || (response.status === 400 && result.code === 'INVALID_INVOICE_REVIEW_INPUT')
        || ([401, 403].includes(response.status) && result.code === 'INVOICE_REVIEW_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未处理，请重新查询账单和核实依据。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="invoice-review">
    <h1>账单费用疑问</h1>
    <p v-if="!csrf">请从已授权的工作台打开。</p>
    <form @submit.prevent="read">
      <fieldset :disabled="!csrf || busy">
        <label>患者编号<input v-model.trim="patient" inputmode="numeric"></label>
        <label>就诊编号<input v-model.trim="visit" inputmode="numeric"></label>
        <label>账单编号<input v-model.trim="invoice" inputmode="numeric"></label>
        <label>当前账单版本<input v-model.trim="version" inputmode="numeric"></label>
        <label><input type="checkbox" v-model="clinical">牙医核实视图</label>
        <button :disabled="!ready">查询账单及疑问</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">提交结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="data">
      <h2>账单 {{ data.invoice.invoiceId }} · 版本 {{ data.invoice.invoiceVersionNo }}</h2>
      <p>{{ data.status || data.invoice.status }} · 合计 {{ data.invoice.total }} 元</p>
      <ul>
        <li v-for="line in data.invoice.lines" :key="line.clinicalRecordId">
          {{ line.item }} · {{ line.quantity }} {{ line.unit }} · 单价 {{ line.unitPrice }} 元 · 小计 {{ line.amount }} 元
        </li>
      </ul>
      <p v-if="data.question">费用疑问 {{ data.question }}</p>
      <p v-if="handling?.clinicalConfirmation">牙医核实 {{ handling.clinicalConfirmation.conclusion }} · {{ handling.clinicalConfirmation.basis }}</p>
      <fieldset :disabled="busy || !!pending || !storageAvailable">
        <label v-if="!clinical">疑问类型<select v-model="scope"><option value="PRICE">价格或金额</option><option value="CLINICAL">治疗项目或数量</option></select></label>
        <label>疑问或核实说明<textarea v-model.trim="note"></textarea></label>
        <button v-if="!clinical" :disabled="!note" @click="submit(OPEN)">登记疑问</button>
        <button v-if="clinical && clinicalReady" :disabled="!note" @click="submit(CONFIRM)">确认诊疗无需修正</button>
        <template v-if="clinical && clinicalReady">
          <label><input type="checkbox" v-model="correctionOpen">登记诊疗修正</label>
          <div v-if="correctionOpen">
            <div v-for="line in data.invoice.lines" :key="line.clinicalRecordId">
              <label><input type="checkbox" :checked="!!corrections[line.clinicalRecordId]"
                @change="toggleCorrection(line, $event.target.checked)">
                修正 {{ line.item }} · {{ line.clinicalRecordId }}
              </label>
              <template v-if="corrections[line.clinicalRecordId]">
                <label>对应同意结果编号<input v-model.trim="corrections[line.clinicalRecordId].consent" inputmode="numeric"></label>
                <label v-if="unified">更换项目（未更换时保留原项目）<select v-model="corrections[line.clinicalRecordId].projectKey" @change="chooseProject(corrections[line.clinicalRecordId])"><option :value="undefined">保留原项目</option><option v-for="item in catalogItems" :key="item.projectKey" :value="item.projectKey">{{item.name}}</option></select></label>
                <label>核实后的实际项目<input v-model.trim="corrections[line.clinicalRecordId].project" :readonly="unified"></label>
                <label>核实后的计费单位<input v-model.trim="corrections[line.clinicalRecordId].unit" :readonly="unified"></label>
                <label>核实后的数量<input v-model.trim="corrections[line.clinicalRecordId].quantity" inputmode="decimal" placeholder="例如1，最多三位小数"></label>
              </template>
            </div>
            <button :disabled="!note || !correctionReady" @click="submit(CORRECT)">保存诊疗修正</button>
          </div>
        </template>
        <template v-if="!clinical && (data.status || data.invoice.status) === '待再次确认'">
          <label>实际确认的申请人<input v-model.trim="applicant"></label>
          <label>申请人确认的金额<input v-model.trim="acceptedAmount" inputmode="decimal" placeholder="例如30.00，须保留两位小数"></label>
          <button :disabled="!note || !applicant || acceptedAmount !== data.invoice.total" @click="submit(ACCEPT)">登记申请人再次确认</button>
        </template>
        <button v-if="!clinical && recalculateReady" :disabled="!note" @click="submit(RECALCULATE)">按核实依据重算</button>
      </fieldset>
    </template>
  </section>
</template>

<style scoped>
.invoice-review, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
textarea { display: block; width: 100%; }
button { justify-self: start; }
</style>
