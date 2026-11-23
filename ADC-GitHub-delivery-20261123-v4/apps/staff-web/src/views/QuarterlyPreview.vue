<script setup>
import { computed, ref } from 'vue'
import QuarterlyRecordBrowser from '../components/QuarterlyRecordBrowser.vue'
import {readQuarterRecord} from '../services/quarterly-records.js'
import QuarterScopePicker from '../components/QuarterScopePicker.vue'
import {quarterlyIssueLabel} from '../services/quarterly-display.js'

const csrf = document.querySelector('meta[name="adc-uc17-csrf"]')?.content
const courseQuarterly=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const expenseReview=ref(false)
const quarter = ref('')
const scope = ref('')
const preview = ref(null)
const busy = ref(false)
const notice = ref('')
const pendingKey = 'adc.uc17.pending'
const pending = ref('')
let storageAvailable = true
try { pending.value = sessionStorage.getItem(pendingKey) || '' } catch { storageAvailable = false }
const clinics = computed(() => scope.value.split(/[，,]/).map(value => value.trim()))
const ready = computed(() => Boolean(csrf && !busy.value && !pending.value && storageAvailable && quarter.value &&
  clinics.value.every(id => /^[1-9]\d*$/.test(id)) && new Set(clinics.value).size === clinics.value.length))
const display = value => value == null ? '待核实' : value

async function selectPreview(item){
 if(busy.value||pending.value)return
 busy.value=true;preview.value=null;notice.value=''
 try{const saved=await readQuarterRecord('previews',item.statisticId,item.clinics,csrf);quarter.value=saved.quarter;scope.value=saved.clinics.join(',');preview.value=saved;notice.value='已读取统计快照 '+saved.statisticId}catch{notice.value='快照读取失败，请重新查询。'}finally{busy.value=false}
}
async function generate() {
  if (!ready.value) return
  busy.value = true
  preview.value = null
  notice.value = ''
  try {
    const key = crypto.randomUUID()
    sessionStorage.setItem(pendingKey, key)
    pending.value = key
    const response = await fetch('/api/v1/staff/quarterly/previews'+(courseQuarterly&&expenseReview.value?'?expenseReview=true':''), {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ quarter: quarter.value, clinics: clinics.value }),
    })
    const body = await response.json()
    const reference = body.requestReference
    const match = typeof reference === 'string' && reference.startsWith('UC/v1/UC17/')
      && reference.endsWith('/QUARTERLY_PREVIEW_GENERATE/' + key)
    const saved = body.preview
    if (response.status === 200 && match && saved?.quarter === quarter.value && /^[1-9]\d*$/.test(saved.statisticId)
        && Array.isArray(saved.clinics) && saved.clinics.length === clinics.value.length
        && new Set(saved.clinics).size === clinics.value.length && saved.clinics.every(id => clinics.value.includes(id))) {
      sessionStorage.removeItem(pendingKey)
      pending.value = ''
      preview.value = saved
      notice.value = '统计快照 ' + saved.statisticId + ' 已保存'
    } else if ((response.status === 409 && match && body.code === 'QUARTER_PREVIEW_REJECTED')
        || (response.status === 400 && body.code === 'INVALID_QUARTER_PREVIEW')
        || ([401, 403].includes(response.status) && body.code === 'QUARTER_PREVIEW_ACCESS_DENIED')) {
      sessionStorage.removeItem(pendingKey)
      pending.value = ''
      notice.value = '本次未生成预览，请核对季度、诊所范围、权限和来源。'
    }
  } catch {
    if (!pending.value) notice.value = '无法保存请求编号。'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="quarterly-preview">
    <h1>季度运营数据汇总</h1>
    <p v-if="!csrf">请从已授权的总部运营工作台打开。</p>
    <QuarterlyRecordBrowser v-if="courseQuarterly&&csrf" kind="previews" :csrf="csrf" :disabled="busy||!!pending" @select="selectPreview" />
    <h2>生成新的季度统计</h2>
    <form @submit.prevent="generate">
      <fieldset :disabled="!csrf || busy || !!pending || !storageAvailable">
        <QuarterScopePicker v-if="courseQuarterly" v-model:quarter="quarter" v-model:clinics="scope" :disabled="busy||!!pending" />
        <template v-else><label>统计季度<input v-model.trim="quarter" maxlength="16"></label><label>诊所编号（多个用逗号分隔）<input v-model.trim="scope"></label></template>
        <label v-if="courseQuarterly"><input type="checkbox" v-model="expenseReview">检查受控费用补正依据（缺少对应依据时保持待核实）</label>
        <button :disabled="!ready">生成统计预览</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="pending" role="alert">预览结果待核对，请保留原请求编号 {{ pending }}。</p>
    <template v-if="preview">
      <h2>季度统计预览 · 快照 {{preview.statisticId}}</h2>
      <RouterLink :to="{path:'/staff/quarterly-calculation',query:{statisticId:preview.statisticId,clinics:preview.clinics.join(',')}}">使用此快照新建核算</RouterLink>
      <p>{{ preview.quarter }} · 诊所 {{ preview.clinics.join('、') }} · {{ preview.readiness }}</p>
      <p>范围起点 {{ display(preview.basis?.start) }} · 结束边界（不含）{{ display(preview.basis?.endExclusive) }}</p>
      <details><summary>查看统计来源</summary><p>按已确认账单、已核验模拟收款及实际预约时间归集；运营费用与耗用成本分别列示。</p>
      <p>来源版本 {{ preview.sourceVersion }} · 来源清单 {{ preview.manifestReference }}</p>
      </details>
      <dl>
        <dt>应收</dt><dd>{{ display(preview.receivable) }}</dd>
        <dt>实收</dt><dd>{{ display(preview.received) }}</dd>
        <dt>运营费用</dt><dd>{{ display(preview.expense) }}</dd>
        <dt>实际耗用成本</dt><dd>{{ display(preview.consumption) }}</dd>
        <dt>取消预约</dt><dd>{{ display(preview.cancelled) }}</dd>
        <dt>应到诊</dt><dd>{{ display(preview.due) }}</dd>
        <dt>已确认未到诊</dt><dd>{{ display(preview.noShow) }}</dd>
        <dt>未到诊比例</dt><dd>{{ preview.due === 0 ? '不适用（分母为零）' : display(preview.noShowRate) }}</dd>
      </dl>
      <template v-if="preview.clinicSummaries?.length"><h3>分诊所汇总</h3><div class="clinic-summary"><table><thead><tr><th>诊所</th><th>应收</th><th>模拟实收</th><th>运营费用</th><th>耗用成本</th></tr></thead><tbody><tr v-for="row in preview.clinicSummaries" :key="row.clinicId"><td>{{row.clinicId}}</td><td>{{display(row.receivable)}}</td><td>{{display(row.received)}}</td><td>{{display(row.expense)}}</td><td>{{display(row.consumption)}}</td></tr></tbody></table></div><p class="summary-help">金额单位：元。未核实指标不按零处理；当前按同一统计来源展示。</p></template>
      <h3>牙医业绩预览（所选诊所合计）</h3>
      <ul><li v-for="item in preview.performance" :key="item.dentistId">牙医 {{ item.dentistId }} · {{ item.amount }}</li></ul>
      <h3>异常清单</h3>
      <p v-if="!preview.issues?.length">本次未发现待核实项。</p>
      <ul v-else>
        <li v-for="(issue, index) in preview.issues" :key="issue.issueId ?? index">
          <template v-if="issue.issueId">事项 {{ issue.issueId }} · </template>{{ quarterlyIssueLabel(issue.reason) }}
        </li>
      </ul>
    </template>
  </section>
</template>
<style scoped>
.quarterly-preview, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 36rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
dl { display: grid; grid-template-columns: minmax(8rem, 1fr) 1fr; max-width: 32rem; }
.clinic-summary{max-width:100%;overflow-x:auto;min-width:0}.clinic-summary table{min-width:480px}.summary-help{font-size:12px;color:#70877b}
</style>
