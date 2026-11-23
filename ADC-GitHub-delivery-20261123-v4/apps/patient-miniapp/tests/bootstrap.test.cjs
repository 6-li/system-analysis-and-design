const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');
const root=path.join(__dirname,'../miniprogram');
test('every declared page has native assets',()=>{const app=JSON.parse(fs.readFileSync(path.join(root,'app.json'),'utf8'));for(const page of app.pages){for(const ext of ['js','json','wxml'])assert.ok(fs.existsSync(path.join(root,page+'.'+ext)))}});
test('bootstrap performs no request and reports unavailable business',()=>{const {bootstrapMessage}=require('../miniprogram/services/bootstrap.js');assert.match(bootstrapMessage(),/尚未连接/)});
test('unconfigured page stays unavailable and does not make requests on load',()=>{let definition;global.Page=x=>definition=x;global.getApp=()=>({globalData:{}});try{delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');definition.onLoad.call({setData:()=>assert.fail('unconfigured page must not update availability')});assert.equal(definition.data.available,false);assert.equal(definition.data.message,'')}finally{delete global.Page;delete global.getApp}});
