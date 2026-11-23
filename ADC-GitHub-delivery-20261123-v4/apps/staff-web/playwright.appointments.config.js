import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './browser-tests', testMatch: 'appointments.spec.js', workers: 1, retries: 0,
  use: { baseURL: 'https://adc-t033.invalid', headless: true,
    ...(process.env.ADC_APPOINTMENT_TEST_BUNDLE ? { channel: 'msedge' } : {}) },
  reporter: [['list'], ['junit', { outputFile: '../../docs/p0-t033-offline-evidence/browser.xml' }]],
})
