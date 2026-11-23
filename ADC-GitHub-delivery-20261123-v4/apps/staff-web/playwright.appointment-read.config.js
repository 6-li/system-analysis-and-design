import { defineConfig } from '@playwright/test'

const edge = process.env.ADC_APPOINTMENT_BROWSER !== 'chromium'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'appointment-read.spec.js',
  workers: 1,
  retries: 0,
  use: { baseURL: 'https://adc-uc02.invalid', ...(edge ? { channel: 'msedge' } : {}), headless: true },
  reporter: [['list'], ['junit', { outputFile: '../../docs/p0-t028-read-evidence/' + (edge ? 'edge' : 'chromium') + '-run01.xml' }]],
})
