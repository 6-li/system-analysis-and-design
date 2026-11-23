<script setup>
import { computed, shallowRef, ref, watch } from 'vue'

const props = defineProps({
  demand: { type: Object, required: true },
  matchProposal: { type: Function, required: true },
  submitProposal: { type: Function, required: true },
  confirmation: { type: Object, default: null },
  pendingStorageKey: { type: String, required: true },
  // Supplied by the trusted staff session. The opaque actor scope is the one used by the command journal.
  requestReferencePrefix: { type: String, required: true },
})
const emit = defineEmits(['proposal'])

const phase = ref('ready')
const proposal = shallowRef(null)
const matchedDemand = shallowRef(null)
const matchedSource = shallowRef(null)
const pending = shallowRef(null)
const message = ref('')
const appointmentId = ref('')
const demandJson = () => JSON.stringify(props.demand)
const schemeJson = () => JSON.stringify(matchedSource.value)
const changedMessage = '预约需求或方案已变化，请重新匹配并取得申请人确认。'
let matchAttempt = 0

function restorePending() {
  const stored = JSON.parse(sessionStorage.getItem(props.pendingStorageKey))
  if (!stored?.requestKey) return false
  pending.value = stored
  unknown()
  return true
}

try {
  restorePending()
} catch {
  phase.value = 'rejected'
  message.value = '无法读取请求核对标识，请先核对已有请求。'
}
const canMatch = computed(() => ['ready', 'stale', 'awaiting'].includes(phase.value))
const canSubmit = computed(() => phase.value === 'awaiting' && proposal.value
  && unchanged()
  && props.confirmation?.proposal === proposal.value
  && typeof props.confirmation?.reference === 'string'
  && props.confirmation.reference.trim() !== '')

function unchanged() {
  return matchedDemand.value?.json === demandJson() && JSON.stringify(proposal.value) === schemeJson()
}

function invalidate(text) {
  matchAttempt++
  phase.value = 'stale'
  proposal.value = null
  matchedDemand.value = null
  matchedSource.value = null
  message.value = text
}

watch(demandJson, () => {
  if (['matching', 'awaiting', 'preparing'].includes(phase.value)) invalidate(changedMessage)
}, { flush: 'sync' })
watch(schemeJson, () => {
  if (['awaiting', 'preparing'].includes(phase.value) && !unchanged()) invalidate(changedMessage)
}, { flush: 'sync' })

function freezeJson(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(freezeJson)
    Object.freeze(value)
  }
  return value
}

async function match() {
  if (!canMatch.value) return
  const attempt = ++matchAttempt
  phase.value = 'matching'
  proposal.value = null
  matchedDemand.value = null
  matchedSource.value = null
  message.value = ''
  try {
    const json = demandJson()
    const value = freezeJson(JSON.parse(json))
    const matched = await props.matchProposal(value)
    if (attempt !== matchAttempt) return
    if (!matched || json !== demandJson()) throw new Error('proposal unavailable')
    matchedDemand.value = { json, value }
    proposal.value = freezeJson(JSON.parse(JSON.stringify(matched)))
    phase.value = 'awaiting'
    matchedSource.value = matched
    emit('proposal', proposal.value)
  } catch {
    if (attempt !== matchAttempt) return
    phase.value = 'ready'
    message.value = '无法重新匹配，请稍后查询可用方案。'
  }
}

function unknown() {
  phase.value = 'unknown'
  message.value = '提交结果待核对。请保留请求标识，不要重发。'
}

const nonempty = value => typeof value === 'string' && value.length > 0
const id = value => typeof value === 'string' && /^-?[0-9]+$/.test(value)
const fields = (value, allowed) => value && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).every(key => allowed.includes(key))

function businessLinks(links) {
  return fields(links, ['scheduleRef', 'issueId', 'appointmentId', 'appointmentChangeId',
    'visitId', 'originalUcSuccessEventId', 'resolutionId'])
    && id(links.appointmentId) && id(links.originalUcSuccessEventId)
    && ['issueId', 'appointmentChangeId', 'visitId'].every(key => !(key in links) || id(links[key]))
    && (!('resolutionId' in links) || typeof links.resolutionId === 'string')
    && (!('scheduleRef' in links) || (fields(links.scheduleRef, ['scheduleId', 'versionNo'])
      && id(links.scheduleRef.scheduleId) && Number.isInteger(links.scheduleRef.versionNo)))
}

function classify(status, body, expectedReference) {
  // The 409 Error contract permits an absent reference for a direct, known stale rejection.
  if (status === 409 && fields(body, ['code', 'message', 'requestReference'])
    && ['STALE_DURATION_BASIS', 'STALE_SCHEDULE'].includes(body.code) && nonempty(body.message)
    && (!('requestReference' in body) || body.requestReference === expectedReference)) return 'stale'
  if (status !== 200 || !fields(body, ['requestReference', 'ucCode', 'operationKind', 'status',
    'businessOutcome', 'message', 'auditPersistence', 'links', 'existingResult'])
    || body.requestReference !== expectedReference || body.ucCode !== 'UC02'
    || body.operationKind !== 'APPOINTMENT_CREATE' || !nonempty(body.message)
    || body.auditPersistence !== 'CONFIRMED') return 'unknown'
  if (body.status === 'COMPLETED' && body.businessOutcome === 'SUCCEEDED'
    && typeof body.existingResult === 'boolean' && businessLinks(body.links)) return 'saved'
  if (['FAILED', 'REJECTED'].includes(body.status) && body.businessOutcome === body.status
    && !('links' in body) && (!('existingResult' in body) || typeof body.existingResult === 'boolean')) return 'rejected'
  return 'unknown'
}

async function submit() {
  if (phase.value === 'awaiting' && !unchanged()) {
    invalidate(changedMessage)
    return
  }
  if (!canSubmit.value) return
  const input = Object.freeze({
    patientId: matchedDemand.value.value.patientId,
    scheme: proposal.value,
    acceptanceEvidenceReference: props.confirmation.reference,
  })
  const attempt = matchAttempt
  phase.value = 'preparing'
  try {
    if (restorePending()) return
    if (!/^UC\/v1\/UC02\/[a-f0-9]{32}\/APPOINTMENT_CREATE\/$/.test(props.requestReferencePrefix)) {
      throw new Error('missing trusted request scope')
    }
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(input)))
    if (phase.value !== 'preparing' || attempt !== matchAttempt) return
    if (restorePending()) return
    if (!unchanged() || input.acceptanceEvidenceReference !== props.confirmation?.reference
      || input.scheme !== props.confirmation?.proposal) {
      invalidate(changedMessage)
      return
    }
    const requestKey = crypto.randomUUID()
    pending.value = {
      requestKey,
      requestReference: props.requestReferencePrefix + requestKey,
      payloadSha256: Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join(''),
    }
    sessionStorage.setItem(props.pendingStorageKey, JSON.stringify(pending.value))
    phase.value = 'submitting'
  } catch {
    phase.value = 'rejected'
    message.value = '无法保留请求核对标识，本次未发送。'
    return
  }
  try {
    const { status, body } = await props.submitProposal(input, pending.value.requestKey)
    const outcome = classify(status, body, pending.value.requestReference)
    if (outcome === 'unknown') {
      unknown()
      return
    }
    sessionStorage.removeItem(props.pendingStorageKey)
    pending.value = null
    if (outcome === 'stale') {
      invalidate(body.code === 'STALE_SCHEDULE'
        ? '接诊安排已变化，请重新匹配并取得申请人确认。'
        : '项目时长配置已更新，请重新匹配并取得申请人确认。')
    } else if (outcome === 'saved') {
      phase.value = 'saved'
      appointmentId.value = body.links.appointmentId
      message.value = '预约已确认。'
    } else {
      phase.value = 'rejected'
      message.value = '本次预约未获确认，请核对反馈。'
    }
  } catch {
    unknown()
  }
}
</script>

<template>
  <section aria-label="首次预约方案复核">
    <h2>首次预约方案复核</h2>
    <button type="button" :disabled="!canMatch" @click="match">重新匹配</button>
    <p v-if="proposal && phase === 'awaiting'">
      项目配置版本 {{ proposal.durationBasis.projectVersion }}，方案时长
      {{ proposal.durationBasis.adoptedDurationMinutes }} 分钟，
      {{ proposal.startsAt }} 至 {{ proposal.endsAt }}。请取得申请人确认。
    </p>
    <button type="button" :disabled="!canSubmit" @click="submit">提交预约</button>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="phase === 'unknown'">请求标识 {{ pending.requestReference || pending.requestKey }}</p>
    <p v-if="phase === 'saved'">预约编号 {{ appointmentId }}</p>
  </section>
</template>
