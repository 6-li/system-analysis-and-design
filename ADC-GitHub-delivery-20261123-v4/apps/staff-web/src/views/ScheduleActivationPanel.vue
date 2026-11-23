<script setup>
import { ref } from 'vue'
import { scheduleApi } from '../api/schedules.js'

const api = scheduleApi()
const id = ref('')
const version = ref('')
const candidate = ref(null)
const message = ref('')
const busy = ref(false)
const csrf = document.querySelector('meta[name="adc-uc22-csrf"]')?.content
const storageKey = 'adc.uc22.activation-pending.v1'
const pending = ref(null)
let storageAvailable = true
try { pending.value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') }
catch { storageAvailable = false }

async function review() {
  candidate.value = null
  if (busy.value || !csrf || !/^[1-9]\d*$/.test(id.value) || !/^[1-9]\d*$/.test(version.value)) return
  busy.value = true
  try {
    const target = [id.value, version.value]
    const draft = await api.version(...target)
    if (draft.http !== 200 || draft.data.activationStatus !== 'DRAFT' || !draft.data.stateToken) {
      message.value = '无法读取待生效草案。'
      return
    }
    const impact = await api.checkImpact(...target, crypto.randomUUID(), csrf)
    if (impact.http !== 200 || !impact.data.checkToken) {
      message.value = '无法检查预约影响。'
      return
    }
    candidate.value = { target, draft: draft.data, impact: impact.data }
    message.value = ''
  } finally { busy.value = false }
}

function showReply(reply) {
  const data = reply.data
  const requested = pending.value
  if (!requested) return
  const matches = !requested.reference || requested.reference === data.requestReference
  const reference = data.links?.scheduleRef
  const confirmed = matches && reply.http === 200 && data.ucCode === 'UC22' && data.operationKind === 'ACTIVATE' &&
    data.status === 'COMPLETED' && data.businessOutcome === 'SUCCEEDED' && data.auditPersistence === 'CONFIRMED' &&
    typeof data.requestReference === 'string' && data.requestReference === data.requestReference.trim() &&
    !!data.requestReference && reference?.scheduleId === requested.target[0] &&
    String(reference.versionNo) === requested.target[1]
  const rejected = matches && typeof data.code === 'string' && ([400, 409, 422].includes(reply.http) ||
    (reply.http === 503 && data.code === 'OPERATION_FAILED' && !!data.requestReference))
  if (confirmed || rejected) {
    if (confirmed) message.value = '排班版本 ' + reference.versionNo + ' 已确认生效。'
    else message.value = '本次未能生效，请重新读取草案和预约影响。'
    candidate.value = null
    try { sessionStorage.removeItem(storageKey) } catch { storageAvailable = false }
    pending.value = null
  } else {
    if (matches && typeof data.requestReference === 'string' && data.requestReference.trim()) {
      pending.value = { ...requested, reference: data.requestReference }
      try { sessionStorage.setItem(storageKey, JSON.stringify(pending.value)) } catch { storageAvailable = false }
    }
    message.value = '提交结果待核对，请勿再次提交。'
  }
}

async function activate() {
  if (!candidate.value || busy.value || pending.value || !storageAvailable || !csrf) return
  const { target, draft, impact } = candidate.value
  const key = crypto.randomUUID()
  try { sessionStorage.setItem(storageKey, JSON.stringify({ target, key })) }
  catch { storageAvailable = false; message.value = '无法保存请求标记，暂缓提交。'; return }
  pending.value = { target, key }
  busy.value = true
  try {
    showReply(await api.activate(...target, { expectedDraftToken: draft.stateToken,
      previousImpactCheckToken: impact.checkToken }, key, csrf))
  } finally { busy.value = false }
}

async function recover() {
  if (busy.value || !pending.value?.reference) return
  busy.value = true
  try { showReply(await api.activationResult(pending.value.reference)) }
  finally { busy.value = false }
}
</script>

<template>
  <section>
    <h2>确认排班生效</h2>
    <form @submit.prevent="review">
      <label>生效安排 ID <input v-model.trim="id" :disabled="busy" @input="candidate = null"></label>
      <label>生效版本 <input v-model.trim="version" :disabled="busy" @input="candidate = null"></label>
      <button :disabled="busy || !csrf">核对待生效版本</button>
    </form>
    <p aria-live="polite">{{ message }}</p>
    <p v-if="pending" role="alert">提交待核对 · {{ pending.reference || pending.key }}</p>
    <button v-if="pending?.reference" :disabled="busy" @click="recover">查询原提交结果</button>
    <template v-if="candidate">
      <p>已读取预约影响。生效时将重新核验。</p>
      <ul v-if="candidate.impact.hiddenConflicts?.length">
        <li v-for="(conflict, index) in candidate.impact.hiddenConflicts" :key="index">{{ conflict.message }}</li>
      </ul>
      <button :disabled="busy || !!pending || !storageAvailable" @click="activate">确认生效</button>
    </template>
  </section>
</template>
