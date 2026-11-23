<script setup>
import { computed, ref, watch } from 'vue'
import {quarterlyIssueLabel} from '../services/quarterly-display.js'

const csrf = document.querySelector('meta[name="adc-uc18-csrf"]')?.content
const statistic = ref('')
const issue = ref('')
const clinics = ref('')
const mode = ref('correct')
const value = ref('')
const evidence = ref('')
const data = ref(null)
const notice = ref('')
const busy = ref(false)
const pending = ref('')
const storageKey = 'adc.uc18.pending'
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = text => /^[1-9]\d*$/.test(text)
const correction = computed(() => mode.value === 'correct')
const scope = computed(() => clinics.value.split(/[，,]/).map(v => v.trim()))
const ready = computed(() => Boolean(csrf && !busy.value && id(statistic.value) && id(issue.value)
  && scope.value.every(id) && new Set(scope.value).size === scope.value.length
  && (!correction.value || scope.value.length === 1)))
const canSubmit = computed(() => ready.value && !!data.value && !pending.value && storageAvailable
  && (correction.value ? !!value.value && !!evidence.value : data.value.status === '待复核' && !!data.value.historyId))
const route = computed(() => '/api/v1/staff/quarterly/previews/' + statistic.value + '/issues/' + issue.value)
const matchingTarget = row => row?.statisticId === statistic.value && row.issueId === issue.value
watch([statistic, issue, clinics, mode], () => {
  data.value = null
  value.value = evidence.value = notice.value = ''
})

async function query() {
  if (!ready.value) return
  busy.value = true
  data.value = null
  notice.value = ''
  try {
    const params = new URLSearchParams({ clinics: scope.value.join(','), correction: String(correction.value) })
    const response = await fetch(route.value + '?' + params, {
      cache: 'no-store', redirect: 'error', headers: { 'X-CSRF-Token': csrf },
    })
    const row = await response.json()
    if (response.status !== 200 || !matchingTarget(row)) throw new Error('QUERY_UNAVAILABLE')
    data.value = row
  } catch {
    notice.value = '未取得异常，请核对快照、事项编号和授权范围。'
  } finally {
    busy.value = false
  }
}

async function save() {
  if (!canSubmit.value) return
  busy.value = true
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const op = correction.value ? 'QUARTER_ISSUE_CORRECT' : 'QUARTER_ISSUE_REVIEW'
    const response = await fetch(route.value, {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ clinics: scope.value.join(','), correction: correction.value,
        historyId: data.value.historyId ?? null, evidence: correction.value ? evidence.value : null,
        value: correction.value ? value.value : null }),
    })
    const body = await response.json()
    const reference = body.requestReference
    const matches = typeof reference === 'string' && reference.startsWith('UC/v1/UC18/')
      && reference.endsWith('/' + op + '/' + key)
    if (response.status === 200 && matches && matchingTarget(body.issue) && id(body.issue.historyId)) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      data.value = { ...data.value, ...body.issue }
      value.value = evidence.value = ''
      notice.value = correction.value ? '补正历史 ' + body.issue.historyId + ' 已保存，等待总部复核。'
        : '一致性检查已保存 · ' + body.issue.status
    } else if ((response.status === 409 && body.code === 'QUARTER_ISSUE_REJECTED' && (!reference || matches))
        || (response.status === 400 && body.code === 'INVALID_QUARTER_ISSUE')
        || ([401, 403].includes(response.status) && body.code === 'QUARTER_ISSUE_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未保存，请重新查询当前补正记录和依据。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="quarterly-issue">
    <h1>季度异常补正与一致性检查</h1>
    <p v-if="!csrf">请从已授权的季度运营工作台打开。</p>
    <form @submit.prevent="query">
      <fieldset :disabled="!csrf || busy">
        <label>统计快照编号<input v-model.trim="statistic" inputmode="numeric"></label>
        <label>异常事项编号<input v-model.trim="issue" inputmode="numeric"></label>
        <label>诊所范围（多个用逗号分隔）<input v-model.trim="clinics"></label>
        <label>办理入口<select v-model="mode"><option value="correct">诊所负责人补正</option><option value="review">总部运营一致性检查</option></select></label>
        <button :disabled="!ready">查询异常事项</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">保存结果待核对，请保留原请求编号 {{ pending }}。</p>
    <template v-if="data">
      <h2>异常 {{ data.issueId }} · {{ data.status }}</h2>
      <p>来源版本 {{ data.sourceVersion }}</p>
      <p>原始记录 {{ quarterlyIssueLabel(data.original) }}</p>
      <p v-if="data.current">当前补正 {{ data.current }} · 历史 {{ data.historyId }}</p>
      <p v-if="data.consistencyResult">{{ data.consistencyResult }}</p>
      <form @submit.prevent="save">
        <fieldset :disabled="busy || !!pending || !storageAvailable">
          <template v-if="correction">
            <label>核实后的补正值<textarea v-model.trim="value"></textarea></label>
            <label>核实依据编号<input v-model.trim="evidence"></label>
          </template>
          <button :disabled="!canSubmit">{{ correction ? '保存诊所补正' : '执行一致性检查' }}</button>
        </fieldset>
      </form>
    </template>
  </section>
</template>

<style scoped>
.quarterly-issue, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
