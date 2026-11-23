import { defineConfig } from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'patient-intake-repair.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-inbox.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/patient-intake-ui-repair/browser.xml'}]],
 outputDir:'../../docs/patient-intake-ui-repair/browser-artifacts'})

