<script setup>
import { ref } from 'vue'
import { resolutionDisposition, scheduleApi } from '../api/schedules.js'
import ScheduleActivationPanel from './ScheduleActivationPanel.vue'

const api = scheduleApi()
const schedule = ref('')
const version = ref('')
const loaded = ref(null)
const impacts = ref([])
const histories = ref([])
const appointment = ref('')
const predecessor = ref('')
const subject = ref('')
const note = ref('')
const reason = ref('')
const evidenceUc = ref('')
const evidenceEvent = ref('')
const evidenceBasis = ref('')
const positiveId = value => /^[1-9][0-9]*$/.test(value)
const assessmentLabels = { VERIFIED: '当前核验通过', STALE: '依据已失鲜', PENDING: '待核实', INCONSISTENT: '证据不一致' }
const message = ref('')
const busy = ref(false)
const storageKey = 'adc.uc22.resolution-pending.v1'
const csrf = document.querySelector('meta[name="adc-uc22-csrf"]')?.content
const pending = ref(null)
let storageAvailable = true
try { pending.value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') }
catch { storageAvailable = false }

function clearEvidence() {
  evidenceUc.value = ''
  evidenceEvent.value = ''
  evidenceBasis.value = ''
}

async function load() {
  if (busy.value) return
  loaded.value = null
  impacts.value = []
  histories.value = []
  appointment.value = ''
  predecessor.value = ''
  clearEvidence()
  if (![schedule.value, version.value].every(value => /^[1-9][0-9]*$/.test(value))) return
  const target = [schedule.value, version.value]
  busy.value = true
  try {
    const [impact, history] = await Promise.all([
      csrf ? api.checkImpact(...target, crypto.randomUUID(), csrf) : api.impacts(...target),
      api.histories(...target),
    ])
    if (impact.http !== 200 || history.http !== 200 || !Array.isArray(impact.data.items) ||
        !Array.isArray(history.data)) {
      message.value = '暂时无法核对该排班及处理历史。'
      return
    }
    loaded.value = target
    impacts.value = impact.data.items
    histories.value = history.data
    message.value = ''
  } finally { busy.value = false }
}

async function save() {
  if (busy.value || pending.value || !loaded.value || !csrf || !storageAvailable) return
  const observed = impacts.value.find(item => item.appointmentId === appointment.value)
  const history = histories.value.find(item => item.currentHeadReference === predecessor.value)
  if (!observed?.observedStateToken || !subject.value.trim() || !note.value.trim() ||
      (evidenceUc.value && (!positiveId(evidenceEvent.value) || !evidenceBasis.value.trim() ||
        (evidenceUc.value === 'UC03' && !positiveId(evidenceBasis.value)))) ||
      (predecessor.value && (!reason.value.trim() || history?.chainIntegrity !== 'VALID' ||
        history.originalRecord.appointment_id !== appointment.value || !history.expectedResolutionToken))) return
  const target = [...loaded.value]
  const payload = { appointmentId: appointment.value, expectedAppointmentToken: observed.observedStateToken,
    subject: subject.value.trim(), claimedResult: evidenceUc.value ? 'VERIFIED' : 'PENDING',
    ...(evidenceUc.value ? { basis: note.value.trim(), formalEvidence: {
      ucCode: evidenceUc.value, auditSuccessEventId: evidenceEvent.value,
      [evidenceUc.value === 'UC03' ? 'appointmentChangeId' : 'cancellationBasisReference']: evidenceBasis.value.trim(),
    } } : { pendingReason: note.value.trim() }),
    ...(history && { resolutionId: history.currentHeadReference, expectedResolutionToken: history.expectedResolutionToken,
      revisionReason: reason.value.trim() }) }
  const key = crypto.randomUUID()
  try { sessionStorage.setItem(storageKey, JSON.stringify({ target, key })) }
  catch { message.value = '无法保留请求引用，暂缓提交。'; return }
  pending.value = { target, key }
  busy.value = true
  const reply = await api.saveResolution(...target, payload, key, csrf)
  const outcome = resolutionDisposition(reply)
  if (outcome === 'unknown') {
    pending.value = { target, key, reference: reply.data.requestReference }
    try { sessionStorage.setItem(storageKey, JSON.stringify(pending.value)) } catch { storageAvailable = false }
    busy.value = false
    return
  }
  try { sessionStorage.removeItem(storageKey) } catch { storageAvailable = false }
  pending.value = null
  busy.value = false
  if (outcome === 'stale') {
    loaded.value = null
    message.value = '依据已变化。请重新读取预约及修订历史后确认。'
    return
  }
  if (outcome === 'saved') {
    await load()
    message.value = '已保存处理记录 ' + reply.data.links.resolutionId + '。排班生效须另外确认。'
  } else message.value = '本次处理记录未保存。'
}
</script>

<template>
  <section>
    <h1>排班影响与处理记录</h1>
    <form @submit.prevent="load">
      <label>安排 ID <input v-model.trim="schedule" inputmode="numeric" :disabled="busy" @input="loaded = null"></label>
      <label>版本 <input v-model.trim="version" inputmode="numeric" :disabled="busy" @input="loaded = null"></label>
      <button :disabled="busy">读取影响和历史</button>
    </form>
    <p role="status">{{ message }}</p>
    <p v-if="pending" role="alert">提交待核对，请勿重发。请求引用 {{ pending.reference || pending.key }}</p>
    <template v-if="loaded">
      <h2>受影响预约</h2>
      <form @submit.prevent="save">
        <label>预约 <select v-model="appointment" @change="predecessor = ''; clearEvidence()">
          <option value="">请选择</option>
          <option v-for="item in impacts" :key="item.appointmentId" :value="item.appointmentId">
            {{ item.appointmentId }} · {{ item.handlingStatus }}
          </option>
        </select></label>
        <label>处理主题 <input v-model="subject" required></label>
        <label>处理依据 <input v-model="note" required></label>
        <label>业务凭证 <select v-model="evidenceUc" @change="evidenceEvent = ''; evidenceBasis = ''">
          <option value="">待核实</option>
          <option value="UC03">UC03 改约成功</option>
          <option value="UC04">UC04 取消成功</option>
        </select></label>
        <template v-if="evidenceUc">
          <label>成功审计 ID <input v-model.trim="evidenceEvent" inputmode="numeric" required></label>
          <label>{{ evidenceUc === 'UC03' ? '改约历史 ID' : '原取消请求引用' }}
            <input v-model.trim="evidenceBasis" required>
          </label>
        </template>
        <label>更正记录 <select v-model="predecessor">
          <option value="">新增记录</option>
          <template v-for="history in histories" :key="history.rootResolutionId">
            <option v-if="history.originalRecord.appointment_id === appointment && history.chainIntegrity === 'VALID'"
                    :value="history.currentHeadReference">{{ history.currentHeadReference }}</option>
          </template>
        </select></label>
        <label v-if="predecessor">更正原因 <input v-model="reason" required></label>
        <button :disabled="busy || !!pending || !csrf || !storageAvailable">保存处理记录</button>
      </form>
      <h2>处理历史</h2>
      <article v-for="history in histories" :key="history.rootResolutionId">
        <h3>原始记录 {{ history.rootResolutionId }}</h3>
        <p>{{ history.originalRecord.resolution_subject }}</p>
        <ul><li v-for="revision in history.revisions" :key="revision.resolution_id">
          修订 {{ revision.resolution_id }} · {{ revision.resolution_subject }}
        </li></ul>
        <p>当前链头 {{ history.currentHeadReference || '待核对' }}</p>
        <p>当前核验结果 {{ assessmentLabels[history.currentAssessment] || '待核对' }}</p>
        <h4>历史成功凭证</h4>
        <ul v-if="history.formalOperationFacts.length">
          <li v-for="fact in history.formalOperationFacts" :key="fact.auditSuccessEventId">
            {{ fact.ucCode === 'UC03' ? '改约' : '取消' }}成功 · 审计 {{ fact.auditSuccessEventId }}
          </li>
        </ul>
        <p v-else>暂无可展示凭证</p>
      </article>
    </template>
    <ScheduleActivationPanel />
  </section>
</template>
