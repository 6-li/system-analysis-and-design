import { createApp, h } from 'vue'
import AppointmentReadPanel from '../../src/views/AppointmentReadPanel.vue'

async function request(path) {
  const response = await fetch(path)
  return { status: response.status, body: await response.json() }
}

createApp({ render: () => h(AppointmentReadPanel, { request }) }).mount('#app')
