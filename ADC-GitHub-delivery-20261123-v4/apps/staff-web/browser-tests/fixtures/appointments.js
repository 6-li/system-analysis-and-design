import { createApp, h } from 'vue'
import Appointments from '../../src/views/Appointments.vue'

const query = new URLSearchParams(location.search)
const source = query.get('source')
const changeSource = query.get('changeSource')
const context = globalThis.syntheticAppointmentContext ?? (query.has('anonymous') ? null : {
  actions: source ? ['create'] : query.has('clinic') ? ['arrival', 'noShow'] : ['change'],
  canRead: true, csrfToken: 'SYNTHETIC-CSRF', requestActorScope: '0123456789abcdef0123456789abcdef',
  ...(source ? { verifiedBookingSource: source === 'verified'
    ? { reference: 'SYNTHETIC-verified-context', requestId: 'SYNTHETIC-request', patientId: '88001' }
    : { requestId: 'SYNTHETIC-request' } } : {}),
  ...(changeSource ? { verifiedChangeSource: changeSource === 'verified'
    ? { reference: 'SYNTHETIC-change-context', requestId: 'SYNTHETIC-change', appointmentId: '901' }
    : { requestId: 'SYNTHETIC-change' } } : {}),
})
createApp({ render: () => h(Appointments, { context }) }).mount('#app')
