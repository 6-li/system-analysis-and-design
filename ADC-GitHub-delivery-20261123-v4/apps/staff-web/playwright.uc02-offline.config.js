import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 'booking-review.spec.js',
  workers: 1,
  retries: 0,
  use: { baseURL: 'https://adc-uc02.invalid', channel: 'msedge', headless: true },
})
