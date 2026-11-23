import {defineConfig} from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'patient-slots.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-slots.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/patient-slot-final-evidence/browser.xml'}]],outputDir:'../../docs/patient-slot-final-evidence/browser-artifacts'})
