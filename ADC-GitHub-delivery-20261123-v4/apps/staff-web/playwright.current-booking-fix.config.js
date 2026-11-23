import {defineConfig} from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'current-booking-fix.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-booking-fix.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/current-booking-fix-evidence/browser.xml'}]],outputDir:'../../docs/current-booking-fix-evidence/browser-artifacts'})
