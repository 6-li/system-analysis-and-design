import { defineConfig } from '@playwright/test'

export default defineConfig({ testDir: './browser-tests', testMatch: 'schedule-resolutions.spec.js',
  workers: 1, retries: 0, reporter: 'list' })
