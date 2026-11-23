import { defineConfig } from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'request-booking.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-inbox.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/request-booking-flow-evidence/browser.xml'}]],
 outputDir:'../../docs/request-booking-flow-evidence/browser-artifacts'})



