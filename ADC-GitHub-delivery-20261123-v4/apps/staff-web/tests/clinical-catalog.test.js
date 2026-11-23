import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
function mount(fetch){
 const source=fs.readFileSync(new URL('../src/views/VisitTreatment.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const context={ref:value=>({value}),computed:f=>({get value(){return f()}}),watch:()=>{},sessionStorage:{getItem:()=>null},document:{querySelector:s=>({content:s.includes('csrf')?'TEST-csrf':s.includes('ui-unification')?'false':'true'})},fetch}
 vm.runInNewContext(source+'\nglobalThis.panel={catalogItems,catalogChoice,chooseCatalog,loadCatalog,project,unit,quantity,canSave,patient,visit,consents,consent,performed,catalogNotice}',context)
 return context.panel
}
test('performed treatment uses explicit loaded catalogue and blocks unknown item/unit',async()=>{
 const page=mount(async()=>({status:200,json:async()=>({synthetic:true,version:'SYNTHETIC test v1',items:[{name:'TEST check',unit:'次',unitPrice:'30.00'}]})}))
 await page.loadCatalog();page.patient.value='1';page.visit.value='2';page.consents.value=[{consentResultId:'3',consentResult:'同意'}];page.consent.value='3';page.performed.value='yes';page.quantity.value='1'
 assert.equal(Boolean(page.canSave.value),false);page.catalogChoice.value='0';page.chooseCatalog();assert.equal(page.project.value,'TEST check');assert.equal(page.unit.value,'次');assert.equal(Boolean(page.canSave.value),true)
 page.unit.value='other';assert.equal(Boolean(page.canSave.value),false)
})
test('unavailable catalogue does not default a price or enable performed save',async()=>{
 const page=mount(async()=>{throw Error('TEST offline')});await page.loadCatalog();assert.equal(page.catalogItems.value.length,0);assert.match(page.catalogNotice.value,/暂不可用/)
})
