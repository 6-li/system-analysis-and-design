import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'schedule-workspace.spec.js',
  workers: 1,
  retries: 0,
  use: { baseURL: 'https://adc-uc21.invalid', channel: 'msedge', headless: true },
})
