import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
function mount(originalBasis){
 const source=fs.readFileSync(new URL('../src/components/BookingSchemePicker.vue',import.meta.url),'utf8').split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const props={originalBasis,patient:'88001'}
 const context={defineProps:()=>props,defineEmits:()=>()=>{},ref:value=>({value}),computed:f=>({get value(){return f()}}),watch:()=>{},localCandidates:()=>['2026-12-21T01:00:00Z'],offsetText:x=>x,Date,URLSearchParams}
 vm.runInNewContext(source+'\nglobalThis.view={options,project,windowKey,localStart,need,scheme}',context)
 const view=context.view
 view.options.value={timeZone:'Asia/Shanghai',projects:[{projectId:'85001',projectVersion:2,minutes:40}],windows:[{scheduleId:'1',versionNo:1,startsAt:'2026-12-21T00:00:00Z',endsAt:'2026-12-21T04:00:00Z'}]}
 view.project.value='85001';view.windowKey.value='1:1';view.localStart.value='2026-12-21T09:00';view.need.value='TEST check'
 return view
}
test('same-project change preserves frozen version and duration after new publication',()=>{
 const view=mount({primaryProjectId:'85001',projectVersion:1,adoptedDurationMinutes:27})
 assert.equal(view.scheme.value.durationBasis.basisMode,'PRESERVED_ORIGINAL');assert.equal(view.scheme.value.durationBasis.projectVersion,1)
 assert.equal(view.scheme.value.endsAt,'2026-12-21T01:27:00.000Z')
})
test('new project uses current duration and initial booking remains initial',()=>{
 const view=mount({primaryProjectId:'85002',projectVersion:1,adoptedDurationMinutes:27})
 assert.equal(view.scheme.value.durationBasis.basisMode,'CHANGED_PROJECT_CURRENT');assert.equal(view.scheme.value.durationBasis.adoptedDurationMinutes,40)
 assert.equal(mount(undefined).scheme.value.durationBasis.basisMode,'INITIAL_CURRENT')
})
