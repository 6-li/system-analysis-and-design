import { defineConfig } from '@playwright/test'

export default defineConfig({ testDir: './browser-tests', testMatch: 'uc01-patient.spec.js',
  workers: 1, retries: 0, reporter: 'list' })
