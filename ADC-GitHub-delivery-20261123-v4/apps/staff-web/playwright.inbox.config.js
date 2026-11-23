import { defineConfig } from '@playwright/test'
export default defineConfig({testDir:'./browser-tests',testMatch:'mini-request-inbox.spec.js',workers:1,retries:0,
 use:{baseURL:'https://adc-inbox.invalid',channel:'msedge',headless:true},
 reporter:[['list'],['junit',{outputFile:'../../docs/mini-reception-inbox-evidence/browser.xml'}]],
 outputDir:'../../docs/mini-reception-inbox-evidence/browser-artifacts'})
