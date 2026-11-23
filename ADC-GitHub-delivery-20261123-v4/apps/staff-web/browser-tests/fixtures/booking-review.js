import { createApp, h, reactive, ref, shallowRef } from 'vue'
import BookingReviewPanel from '../../src/views/BookingReviewPanel.vue'

const demand = reactive({ patientId: '88001', treatmentRequest: 'SYNTHETIC-UC02', preferences: { clinicId: '81001' } })
const proposal = shallowRef(null)
const source = shallowRef(null)
const confirmation = shallowRef(null)
const panel = ref(true)
let releaseMatch
let version = 0

async function matchProposal(input) {
  if (!input.treatmentRequest.startsWith('SYNTHETIC-UC02')) throw new Error('unexpected synthetic demand')
  version++
  const matched = reactive({
    treatmentRequest: input.treatmentRequest,
    effectiveScheduleRef: { scheduleId: '87001', versionNo: 2 },
    startsAt: '2026-11-08T17:00:00.123456+08:00',
    endsAt: '2026-11-08T17:27:00.123456+08:00',
    durationBasis: { schemaVersion: 1, basisMode: 'INITIAL_CURRENT', primaryProjectId: '85001',
      projectVersion: version, adoptedDurationMinutes: 27 },
  })
  source.value = matched
  if (new URLSearchParams(location.search).has('hold-match')) {
    await new Promise(resolve => { releaseMatch = resolve })
  }
  return matched
}

async function submitProposal(input, key) {
  const response = await fetch('/api/v1/staff/appointments', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-CSRF-Token': 'SYNTHETIC-UC02' },
    body: JSON.stringify(input),
  })
  const text = await response.text()
  return { status: response.status, body: text ? JSON.parse(text) : null }
}

createApp({
  setup: () => () => h('main', [
    h('p', '仅限合成数据'),
    panel.value && h(BookingReviewPanel, {
      demand, matchProposal, submitProposal, confirmation: confirmation.value,
      pendingStorageKey: 'SYNTHETIC-UC02-pending',
      requestReferencePrefix: 'UC/v1/UC02/0123456789abcdef0123456789abcdef/APPOINTMENT_CREATE/',
      onProposal: value => { proposal.value = value },
    }),
    h('button', {
      disabled: !proposal.value,
      onClick: () => {
        confirmation.value = { proposal: proposal.value, reference: `SYNTHETIC-confirmation-${version}` }
      },
    }, '注入合成接受记录'),
    h('button', { onClick: () => { demand.patientId = '88002' } }, '原地修改患者'),
    h('button', { onClick: () => {
      demand.preferences.clinicId = demand.preferences.clinicId === '81001' ? '81002' : '81001'
    } }, '修改嵌套需求'),
    h('button', { onClick: () => { source.value.durationBasis.projectVersion++ } }, '修改嵌套方案'),
    h('button', { onClick: () => releaseMatch?.() }, '释放匹配'),
    h('button', { onClick: () => { panel.value = !panel.value } }, '卸载或重新挂载'),
  ]),
}).mount('#app')
