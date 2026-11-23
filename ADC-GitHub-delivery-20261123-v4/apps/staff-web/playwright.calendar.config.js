import { defineConfig } from '@playwright/test'
export default defineConfig({ testDir: './browser-tests', testMatch: 'schedule-calendar.spec.js', workers: 1, retries: 0,
  use: { baseURL: 'https://adc-calendar.invalid', channel: 'msedge', headless: true },
  reporter: [['list'], ['junit', { outputFile: '../../docs/schedule-calendar-evidence/browser.xml' }]],
  outputDir: '../../docs/schedule-calendar-evidence/browser-artifacts' })
