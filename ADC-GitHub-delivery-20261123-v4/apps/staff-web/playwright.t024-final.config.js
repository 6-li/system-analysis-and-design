import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './browser-tests',
  testMatch: 't024-final.spec.js',
  workers: 1,
  retries: 0,
  use: { channel: 'msedge', headless: true },
})
