const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
test('appointment detail WXML uses a directly parsable condition without encoded operators',()=>{
 const template=fs.readFileSync(require.resolve('../miniprogram/pages/index/index.wxml'),'utf8');
 const expression=template.match(/wx:elif="\{\{(screen === 'appointment-detail'[^}]*)\}\}"/)?.[1];
 assert.ok(expression);assert.doesNotMatch(expression,/&(?:amp|#\d+);/);
 const condition=new Function('screen','return ('+expression+')');
 assert.equal(condition('appointment-detail'),true);assert.equal(condition('appointments'),false);assert.equal(condition('profile'),false);
});

test('existing appointment request submit does not require choosing another clinic',()=>{
 const template=fs.readFileSync(require.resolve('../miniprogram/pages/index/index.wxml'),'utf8');
 const expression=template.match(/disabled="\{\{([^}]+)\}\}" bindtap="submit"/)?.[1];
 assert.ok(expression);const disabled=new Function('screen','busy','pending','clinic','return ('+expression+')');
 assert.equal(disabled('change',false,null,''),false);assert.equal(disabled('cancel',false,null,''),false);
 assert.equal(disabled('booking',false,null,''),true);assert.equal(disabled('booking',false,null,'81001'),false);
 assert.equal(disabled('change',true,null,''),true);assert.equal(Boolean(disabled('change',false,'pending','')),true);
});
