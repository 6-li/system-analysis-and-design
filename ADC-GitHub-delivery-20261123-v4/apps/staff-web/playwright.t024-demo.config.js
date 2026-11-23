import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 't024-demo.spec.js',
  workers: 1,
  retries: 0,
  use: { baseURL: 'https://adc-t024.invalid', channel: 'msedge', headless: true },
})
