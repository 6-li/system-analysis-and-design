import { defineConfig } from '@playwright/test';
export default defineConfig({
 testDir:'./browser-tests', fullyParallel:false, workers:1, retries:0, timeout:20000,
 use:{baseURL:'http://127.0.0.1:15173',channel:'msedge',headless:true,trace:'off',screenshot:'off',video:'off'},
 reporter:[['list']],
 webServer:[
  {command:'node ../../tools/run-technical-server.cjs backend',url:'http://127.0.0.1:18080/actuator/health',timeout:45000,reuseExistingServer:false},
  {command:'node ../../tools/run-technical-server.cjs frontend',url:'http://127.0.0.1:15173',timeout:30000,reuseExistingServer:false}
 ]
});
