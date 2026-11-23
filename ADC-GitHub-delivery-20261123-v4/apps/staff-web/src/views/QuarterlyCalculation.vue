<script setup>
import { computed, ref, watch } from 'vue'
import {useRoute} from 'vue-router'
import QuarterlyRecordBrowser from '../components/QuarterlyRecordBrowser.vue'
import {readQuarterRecord,recordId} from '../services/quarterly-records.js'
import FinanceSummary from '../components/FinanceSummary.vue'
import {reportHtml} from '../services/quarterly-report.js'

const courseQuarterly=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const routeState=useRoute()
const mode=ref(''),manual=ref(false),selectedPreview=ref(null)
const csrf = document.querySelector('meta[name="adc-uc19-csrf"]')?.content
const statistic = ref('')
const clinics = ref('')
const calculation = ref('')
const version = ref('')
const notes = ref('')
const result = ref(null)
const notice = ref('')
const busy = ref(false)
const pending = ref('')
const storageKey = 'adc.uc19.pending'
let storageAvailable = true
try { pending.value = sessionStorage.getItem(storageKey) || '' } catch { storageAvailable = false }
const id = value => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const scope = computed(() => clinics.value.split(/[，,]/).map(value => value.trim()))
const targetReady = computed(() => scope.value.every(id) && new Set(scope.value).size === scope.value.length)
const revision = computed(() => id(calculation.value) && id(version.value) && Number(version.value) <= 2147483647)
const ready = computed(() => Boolean(csrf && !busy.value && !pending.value && storageAvailable && id(statistic.value)
  && (!courseQuarterly||mode.value==='new'||mode.value==='revise'||manual.value)&&targetReady.value && (revision.value || !calculation.value && !version.value)))
const route = '/api/v1/staff/quarterly/calculations'

function invalidateSelection(){if(pending.value)return;statistic.value='';calculation.value='';version.value='';clinics.value='';result.value=null;selectedPreview.value=null;mode.value='';notice.value=''}
async function choosePreview(item){
 if(busy.value||pending.value)return;invalidateSelection();busy.value=true
 try{const saved=await readQuarterRecord('previews',item.statisticId,item.clinics,csrf);statistic.value=saved.statisticId;clinics.value=saved.clinics.join(',');selectedPreview.value=saved;mode.value='new';notice.value='已选择 '+saved.quarter+' · 诊所 '+saved.clinics.join('、')}catch{notice.value='无法读取所选快照，请重新查询。'}finally{busy.value=false}
}
async function chooseCalculation(item){
 if(busy.value||pending.value)return;invalidateSelection();busy.value=true
 try{const saved=await readQuarterRecord('calculations',item.calculationId,item.clinics,csrf,item.version);result.value=saved;statistic.value=saved.statisticId;clinics.value=saved.clinics.join(',');calculation.value=saved.calculationId;version.value=String(saved.version);mode.value='view';notice.value='已读取核算 '+saved.calculationId+' · 版本 '+saved.version}catch{notice.value='无法读取所选核算，请重新查询。'}finally{busy.value=false}
}
watch(()=>routeState.query,query=>{if(!courseQuarterly||pending.value)return;if(recordId(query.statisticId)&&typeof query.clinics==='string')choosePreview({statisticId:query.statisticId,clinics:query.clinics});else if(recordId(query.calculationId)&&typeof query.clinics==='string')chooseCalculation({calculationId:query.calculationId,version:Number(query.version),clinics:query.clinics})},{immediate:true})
async function generate() {
  if (!ready.value) return
  busy.value = true
  result.value = null
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(storageKey, key)
    pending.value = key
    const response = await fetch(route, { method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ statisticId: statistic.value, clinics: scope.value.join(','),
        calculationId: calculation.value || null, expectedVersion: version.value ? Number(version.value) : null,
        pendingNotes: notes.value || null }),
    })
    const body = await response.json()
    const reference = body.requestReference
    const matches = typeof reference === 'string' && reference.startsWith('UC/v1/UC19/')
      && reference.endsWith('/QUARTER_CALCULATE/' + key)
    const saved = body.calculation
    if (response.status === 200 && matches && saved?.statisticId === statistic.value && id(saved.calculationId)
        && (revision.value ? saved.calculationId === calculation.value && saved.version === Number(version.value) + 1 : saved.version === 1)
        && ['待复核', '待核实'].includes(saved.status)) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      result.value = saved
      mode.value='view'
      if(!Array.isArray(saved.performance)){
        try{result.value=await readQuarterRecord('calculations',saved.calculationId,scope.value,csrf,saved.version)}catch{notice.value='核算已保存，牙医业绩明细请从历史记录重新读取。'}
      }
      calculation.value = saved.calculationId
      version.value = String(saved.version)
      notice.value = '核算 ' + saved.calculationId + ' · 版本 ' + saved.version + ' · ' + saved.status
    } else if ((response.status === 409 && matches && body.code === 'QUARTER_CALCULATION_REJECTED')
        || (response.status === 400 && body.code === 'INVALID_QUARTER_CALCULATION')
        || ([401, 403].includes(response.status) && body.code === 'QUARTER_CALCULATION_ACCESS_DENIED')) {
      sessionStorage.removeItem(storageKey)
      pending.value = ''
      notice.value = '本次未保存，请核对快照、版本及归集权限。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}

async function exportReport(format='html') {
  if (!csrf || !revision.value || !targetReady.value || busy.value || pending.value) return
  busy.value = true
  notice.value = ''
  try {
    const params = new URLSearchParams({ version: version.value, clinics: scope.value.join(',') })
    const key = crypto.randomUUID()
    const response = await fetch(route + '/' + calculation.value + '/reviewed-report?' + params, {
      method: 'POST', headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key }, cache: 'no-store', redirect: 'error',
    })
    const body = await response.json()
    const report = body.report
    if (response.status !== 200 || !body.requestReference?.startsWith('UC/v1/UC19/')
        || !body.requestReference.endsWith('/QUARTER_REVIEWED_EXPORT/' + key)
        || report?.calculationId !== calculation.value || report.version !== Number(version.value)
        || !id(report.reviewId)) throw new Error('REVIEW_REQUIRED')
    const url = URL.createObjectURL(new Blob([format==='json'?JSON.stringify(report,null,2):reportHtml(report)], { type: format==='json'?'application/json':'text/html;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'quarterly-report-' + report.basis.quarter + '-' + report.calculationId + '-v' + report.version + (format==='json'?'.json':'.html')
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
    notice.value = '已导出经财务复核的本版本报告。'
  } catch {
    notice.value = '未导出，请核对当前核算版本和财务复核记录。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="quarter-calculation">
    <h1>季度经营及牙医业绩核算</h1>
    <p v-if="!csrf">请从已授权的总部运营工作台打开。</p>
    <template v-if="courseQuarterly&&csrf">
      <QuarterlyRecordBrowser kind="calculations" :csrf="csrf" :disabled="busy||!!pending" @select="chooseCalculation" @invalidate="invalidateSelection" />
      <details><summary>从已有统计快照新建核算</summary><QuarterlyRecordBrowser kind="previews" :csrf="csrf" :disabled="busy||!!pending" @select="choosePreview" @invalidate="invalidateSelection" /></details>
      <div v-if="selectedPreview||result" class="selected-record"><strong>{{selectedPreview?.quarter||result?.basis?.quarter}} · 诊所 {{clinics}}</strong><p>统计快照 {{statistic}}<template v-if="calculation"> · 核算 {{calculation}} · 版本 {{version}}</template></p><p>{{mode==='new'?'已自动带入快照，点击下方保存即可新建核算。':'正在查看已有核算，不会自动生成新版本。'}}</p></div>
    </template>
    <form @submit.prevent="generate">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <details v-if="courseQuarterly" :open="manual" @toggle="manual=$event.target.open"><summary>手动编号办理（兼容入口）</summary><p>通常从上方历史记录选择即可。编号和版本请按原记录填写。</p>
        <label>统计快照编号<input v-model.trim="statistic" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        <label>诊所范围（多个用逗号分隔）<input v-model.trim="clinics" @input="result=null;selectedPreview=null"></label>
        <label>原核算编号（新建时留空）<input v-model.trim="calculation" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        <label>原核算版本（新建时留空）<input v-model.trim="version" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        </details>
        <template v-if="!courseQuarterly">        <label>统计快照编号<input v-model.trim="statistic" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        <label>诊所范围（多个用逗号分隔）<input v-model.trim="clinics" @input="result=null;selectedPreview=null"></label>
        <label>原核算编号（新建时留空）<input v-model.trim="calculation" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        <label>原核算版本（新建时留空）<input v-model.trim="version" inputmode="numeric" @input="result=null;selectedPreview=null"></label>
        </template>
        <label v-if="!courseQuarterly||mode==='new'||mode==='revise'||manual">暂缓及待核实说明（选填）<textarea v-model.trim="notes"></textarea></label>
        <button v-if="!courseQuarterly||mode==='new'||mode==='revise'||manual" :disabled="!ready">保存本次核算</button>
        <button v-if="courseQuarterly&&mode==='view'" type="button" @click="mode='revise'">明确修订此核算并生成新版本</button>
        <button type="button" :disabled="!revision || !targetReady" @click="exportReport('html')">导出可读报告（可打印PDF）</button>
        <button type="button" :disabled="!revision || !targetReady" @click="exportReport('json')">导出技术来源附件（JSON）</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">核算结果待核对，请保留请求编号 {{ pending }}。</p>
    <template v-if="result">
      <h2>核算 {{ result.calculationId }} · 版本 {{ result.version }}</h2>
      <p v-if="courseQuarterly">{{result.reviewed?'本版本财务已复核，可导出。':'本版本尚未确认财务复核；请财务人员从其岗位的核算记录列表读取并复核。'}}</p>
      <label v-if="result.versions?.length>1">查看历史版本<select :value="result.version" @change="chooseCalculation({calculationId:result.calculationId,version:Number($event.target.value),clinics:result.clinics})"><option v-for="item in result.versions" :key="item.version" :value="item.version">版本 {{item.version}}</option></select></label>
      <FinanceSummary :report="result" />
      <template v-if="!courseQuarterly"><p>{{ result.basis.quarter }} · 来源版本 {{ result.basis.sourceVersion }} · {{ result.status }}</p>
      <p>应收 {{ result.basis.receivable ?? '待核实' }} 元 · 实收 {{ result.basis.received ?? '待核实' }} 元</p>
      <p>运营费用 {{ result.basis.expense ?? '待核实' }} 元 · 实际耗用成本 {{ result.basis.consumption ?? '待核实' }} 元</p>
      <p>净利润 {{ result.netProfit == null ? '待核实' : result.netProfit + ' 元' }} ·
        激励资金池 {{ result.incentivePool == null ? '待核实' : result.incentivePool + ' 元' }}</p>
      </template>
      <p v-if="result.pendingNotes">{{ result.pendingNotes }}</p>
    </template>
  </section>
</template>

<style scoped>
.quarter-calculation, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
</style>
