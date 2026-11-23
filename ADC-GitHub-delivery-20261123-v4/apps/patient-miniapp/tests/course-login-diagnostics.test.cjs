const test=require('node:test'),assert=require('node:assert/strict');
const {createHttpClient}=require('../miniprogram/services/http.js');
test('phone network errors expose only a safe category, never raw URL or response secrets',async()=>{
 for(const [errMsg,category] of [['request:fail url not in domain list SECRET_VALUE','DOMAIN'],['request:fail ssl hand shake error SECRET_VALUE','TLS'],['request:fail connection closed SECRET_VALUE','CONNECTION']]){
  const client=createHttpClient({baseUrl:'https://106.55.173.42',request:o=>o.fail({errMsg})});
  await assert.rejects(client.post('/api/v1/mini/sessions/wechat',{code:'SYNTHETIC-code'}),error=>error.code==='NETWORK_ERROR'&&error.networkCategory===category&&!JSON.stringify(error).includes('SECRET_VALUE'));
 }
});
test('synthetic page displays diagnostic category without changing normal login copy',async()=>{
 let def;global.Page=x=>def=x;delete require.cache[require.resolve('../miniprogram/pages/index/index.js')];require('../miniprogram/pages/index/index.js');delete global.Page;
 const p={...def,syntheticLogin:true,data:{...def.data},setData(x){Object.assign(this.data,x)},client:{login:async()=>{throw Object.assign(Error('SECRET_VALUE'),{code:'NETWORK_ERROR',networkCategory:'TLS'})}}};
 await p.login();assert.match(p.data.message,/TLS/);assert(!p.data.message.includes('SECRET_VALUE'));assert.equal(p.data.busy,false);
 p.syntheticLogin=false;await p.login();assert.equal(p.data.message,'登录未完成。');
});
