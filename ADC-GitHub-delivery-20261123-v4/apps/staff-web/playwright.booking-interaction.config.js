import { defineConfig } from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'booking-interaction.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-inbox.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/booking-interactive-evidence/browser.xml'}]],
 outputDir:'../../docs/booking-interactive-evidence/browser-artifacts'})


