const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const wxml=()=>fs.readFileSync(require.resolve('../miniprogram/pages/index/index.wxml'),'utf8');
const css=()=>fs.readFileSync(require.resolve('../miniprogram/app.wxss'),'utf8');
test('home service entries retain existing handlers without introducing a payment action',()=>{
 const source=wxml();
 for(const handler of ['openBooking','openAppointments','openVisitHistory','openProfile'])assert.match(source,new RegExp('bindtap="'+handler+'"'));
 assert.doesNotMatch(source,/bindtap="(?:pay|settle|autoBook)"/);
});
test('slot grid differentiates chosen and full states while keeping server availability guard',()=>{
 const source=wxml();assert.match(source,/class="slot-grid"/);assert.match(source,/selectedSlot.index === item.index \? 'slot-selected'/);
 assert.match(source,/disabled="\{\{busy \|\| item.availabilityStatus === 'FULL'\}\}"/);
 assert.match(source,/data-index="\{\{index\}\}" bindtap="chooseBookingSlot"/);
 assert.match(source,/bindchange="confirmBookingSelection"/);
 assert.match(css(),/\.slot-selected\{/);
});
test('request input remains bound across navigation and notices stay visible',()=>{
 const source=wxml();assert.match(source,/<textarea id="need" value="\{\{need\}\}"/);
 assert.match(source,/loading="\{\{busy\}\}" disabled="\{\{busy/);
 assert.match(source,/wx:if="\{\{message\}\}" class="notice" aria-role="status"/);
 assert.match(source,/课程模拟结算（非实际扣款）/);
 assert.match(source,/申请通过后，才会生成正式预约/);
});
test('detail separates doctor findings, aftercare and invoice without generated advice',()=>{
 const source=wxml();for(const title of ['已实施项目','医生说明','诊疗账单'])assert.ok(source.includes('class="section-heading">'+title));
 assert.match(source,/item.patientFindings/);assert.match(source,/item.aftercareInstructions/);assert.match(source,/医生暂未填写/);
});
test('compact layout avoids fixed content widths and accounts for device safe area',()=>{
 const styles=css();assert.match(styles,/safe-area-inset-bottom/);assert.match(styles,/repeat\(2,minmax\(0,1fr\)\)/);assert.doesNotMatch(styles,/[;{]width:\s*(?:[4-9]\d\d|\d{4,})px/);
});
