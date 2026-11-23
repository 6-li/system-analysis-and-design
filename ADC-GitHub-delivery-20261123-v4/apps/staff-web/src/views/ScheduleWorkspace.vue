<script setup>
import { reactive, ref } from 'vue'
import { scheduleApi, writeDisposition } from '../api/schedules.js'
import ScheduleCalendar from '../components/ScheduleCalendar.vue'

const api = scheduleApi()
const csrf = document.querySelector('meta[name="adc-uc21-csrf"]')?.content
const calendarZone = document.querySelector('meta[name="adc-clinic-zone"]')?.content || ''
const storageKey = 'adc.uc21.unresolved.v1'
const pending = ref(null)
let canRemember = true
try { pending.value = JSON.parse(sessionStorage.getItem(storageKey) || 'null') } catch { canRemember = false }
const busy = ref(false)
const note = ref('')
const savedImpact = ref(null)
const entries = ref([])
const cursor = ref('')
const detail = ref(null)
const staleBasis = ref(false)
let versionRead = 0
const issue = ref(null)
const checked = ref(null)
const lookup = reactive({ id: '', version: '', issueId: '' })
const form = reactive({ clinic: '', dentist: '', start: '', end: '', coordination: '', change: '', reason: '', previous: '', revisionPrevious: '' })
const validId = value => /^-?[0-9]+$/.test(value)

function showError(data) {
  note.value = data.message || '暂时无法读取，请核对身份、授权及数据来源。'
}
async function readList(next = '') {
  const query = {}
  if (validId(form.clinic)) query.clinicId = form.clinic
  if (validId(form.dentist)) query.dentistId = form.dentist
  if (next) query.cursor = next
  const result = await api.list(query)
  if (result.http !== 200 || !Array.isArray(result.data.items)) return showError(result.data)
  entries.value = next ? [...entries.value, ...result.data.items] : result.data.items
  cursor.value = result.data.nextCursor || ''
}
async function readVersion() {
  const read = ++versionRead
  detail.value = null
  if (!validId(lookup.id) || !/^[1-9][0-9]*$/.test(lookup.version)) return
  const result = await api.version(lookup.id, lookup.version)
  if (read !== versionRead) return
  if (result.http !== 200) return showError(result.data)
  detail.value = result.data
  staleBasis.value = false
}
function clearBasis() {
  versionRead++
  detail.value = null
  staleBasis.value = false
}
function selectCalendarVersion(row) {
  clearBasis()
  lookup.id = row.ref.scheduleId
  lookup.version = String(row.ref.versionNo)
  note.value = '已选择安排，请读取指定版本后再核对；点击日历不会修改排班。'
}
async function readIssue() {
  issue.value = null
  if (!validId(lookup.issueId)) return
  const result = await api.issue(lookup.issueId)
  if (result.http !== 200) return showError(result.data)
  issue.value = result.data
}
function context() {
  return {
    clinicId: form.clinic,
    ...(form.dentist && { dentistId: form.dentist }),
    ...(form.start && { submittedStartsAt: form.start }),
    ...(form.end && { submittedEndsAt: form.end }),
    ...(form.coordination && { coordinationBasis: form.coordination }),
  }
}
async function check() {
  if (busy.value || !csrf || !validId(form.clinic)) return
  busy.value = true
  try {
    const body = context()
    if (detail.value?.ref) body.basisRef = detail.value.ref
    const result = await api.check(body, crypto.randomUUID(), csrf)
    checked.value = result.http === 200 ? result.data : null
    if (!checked.value) showError(result.data)
  } finally { busy.value = false }
}
function remember(marker) {
  try {
    if (marker) sessionStorage.setItem(storageKey, JSON.stringify(marker))
    else sessionStorage.removeItem(storageKey)
    pending.value = marker
    return true
  } catch { return false }
}
async function submit(operation) {
  if (busy.value || pending.value || !csrf || !canRemember || !validId(form.clinic)) return
  const version = detail.value
  const previous = operation === 'revise' ? form.revisionPrevious : form.previous
  if (operation !== 'issue' && (!validId(form.dentist) || !form.start || !form.end)) return
  if (operation === 'revise' && (!version?.stateToken || staleBasis.value || !form.change)) return
  if (operation === 'issue' && !form.reason) return
  if (previous && !validId(previous)) return
  const key = crypto.randomUUID()
  if (!remember({ key })) {
    note.value = '无法保存请求恢复标识，尚未发送操作。'
    return
  }
  busy.value = true
  savedImpact.value = null
  try {
    const proposal = { clinicId: form.clinic, dentistId: form.dentist, startsAt: form.start, endsAt: form.end }
    if (form.coordination) proposal.coordinationBasis = form.coordination
    let result
    if (operation === 'create') {
      if (previous) proposal.previousIssueId = previous
      result = await api.create(proposal, key, csrf)
    } else if (operation === 'revise') {
      result = await api.revise(version.ref.scheduleId, {
        ...proposal, basisRef: version.ref, expectedStateToken: version.stateToken, changeBasis: form.change,
        ...(previous && { previousIssueId: previous }),
      }, key, csrf)
    } else {
      const submittedContext = context()
      if (version?.ref) submittedContext.basisRef = version.ref
      result = await api.recordIssue({
        submittedContext, failureReason: form.reason, ...(previous && { previousIssueId: previous }),
      }, key, csrf)
    }
    const outcome = writeDisposition(operation, result)
    if (outcome === 'unknown') {
      const marker = { key, requestReference: result.data.requestReference }
      pending.value = marker
      remember(marker)
      note.value = '提交结果待核对。请保留请求引用或客户端请求键，勿自动重试。'
      return
    }
    if (outcome === 'rejected') {
      showError(result.data)
      if (operation === 'revise' && result.http === 409) staleBasis.value = true
    } else if (outcome === 'draft') {
      const r = result.data.links.scheduleRef
      note.value = `已保存待生效草案 ${r.scheduleId} / ${r.versionNo}。`
      savedImpact.value = { ref: r, ids: result.data.appointmentImpact.appointmentIds }
      if (operation === 'revise') clearBasis()
    } else {
      note.value = `已登记未完成事项 ${result.data.links.issueId}。`
    }
    remember(null)
  } finally { busy.value = false }
}
</script>

<template>
  <section>
    <h1>接诊安排维护</h1>
    <p>仅办理草案和未完成事项。待生效草案不可用于正式预约。</p>
    <p v-if="!csrf || !canRemember">写入需要可信会话和可用的本地恢复标识存储。</p>
    <p v-if="pending" role="alert">有尚待核对的提交。请求标识 {{ pending.requestReference || pending.key }}。</p>
    <p role="status">{{ note }}</p>
    <div v-if="savedImpact">
      <h2>本次草案保存的受影响预约</h2>
      <p>草案 {{ savedImpact.ref.scheduleId }} / {{ savedImpact.ref.versionNo }}</p>
      <p v-if="savedImpact.ids.length === 0">授权诊所范围内没有需要复核的预约。</p>
      <ul v-else><li v-for="id in savedImpact.ids" :key="id">预约 {{ id }}</li></ul>
    </div>

    <h2>安排和事项查询</h2>
    <div class="fields">
      <label>诊所编号 <input v-model.trim="form.clinic"></label>
      <label>牙医编号 <input v-model.trim="form.dentist" placeholder="查询可留空，查看授权范围内全部牙医"></label>
      <label>安排编号 <input v-model.trim="lookup.id"></label>
      <label>版本号 <input v-model.trim="lookup.version"></label>
      <label>事项编号 <input v-model.trim="lookup.issueId"></label>
    </div>
    <div class="actions">
      <button @click="readList()">查询安排</button>
      <button v-if="cursor" @click="readList(cursor)">下一页</button>
      <button :disabled="busy" @click="readVersion">读取指定版本</button>
      <button @click="readIssue">查询未完成事项</button>
    </div>
    <p>查全部牙医时请留空牙医编号；结果仅限当前身份获授权的诊所。下方维护草案仍必须指定牙医。</p>
    <ul v-if="entries.length">
      <li v-for="row in entries" :key="row.ref.scheduleId + ':' + row.ref.versionNo">
        {{ row.ref.scheduleId }} / {{ row.ref.versionNo }} · {{ row.statusLabel }} · {{ row.startsAt }} 至 {{ row.endsAt }}
      </li>
    </ul>
    <ScheduleCalendar v-if="entries.length" :entries="entries" :time-zone="calendarZone" :partial="!!cursor"
      title="诊所每周排班" @select="selectCalendarVersion" />
    <p v-if="detail">已读取 {{ detail.ref.scheduleId }} / {{ detail.ref.versionNo }} · {{ detail.statusLabel }}。
      <button :disabled="busy" @click="clearBasis">清除基础版本</button>
    </p>
    <p v-if="issue">事项 {{ issue.issueId }} · {{ issue.failureReason }}</p>

    <h2>检查、草案和未完成事项</h2>
    <p>按诊所当地时间填写，并明确写入 UTC 偏移，精度最高为微秒。</p>
    <div class="fields">
      <label>开始时间 <input v-model.trim="form.start" placeholder="2026-12-01T09:00:00+08:00"></label>
      <label>结束时间 <input v-model.trim="form.end" placeholder="2026-12-01T10:00:00+08:00"></label>
      <label>协调依据 <input v-model.trim="form.coordination"></label>
      <label>调整依据 <input v-model.trim="form.change"></label>
      <label>未完成原因 <input v-model.trim="form.reason"></label>
      <label>前一事项编号（首次草案或登记） <input v-model.trim="form.previous"></label>
      <label>补正事项编号（修订，可选） <input v-model.trim="form.revisionPrevious"></label>
    </div>
    <div class="actions">
      <button :disabled="busy || !csrf" @click="check">初检</button>
      <button :disabled="busy || pending || !csrf || !canRemember || Boolean(detail)" @click="submit('create')">保存首次草案</button>
      <button :disabled="busy || pending || !csrf || !canRemember || !detail || staleBasis" @click="submit('revise')">保存修订草案</button>
      <button :disabled="busy || pending || !csrf || !canRemember" @click="submit('issue')">登记未完成事项</button>
    </div>
    <div v-if="checked">
      <p>初检 {{ checked.conclusion }}</p>
      <ul><li v-for="(conflict, i) in checked.conflicts" :key="i">{{ conflict.message }}</li></ul>
    </div>
  </section>
</template>

<style scoped>
.fields { display:grid; grid-template-columns:repeat(auto-fit,minmax(215px,1fr)); gap:12px }
label { display:flex; flex-direction:column }
input { padding:8px; border:1px solid #9aacb5; border-radius:4px; font:inherit }
.actions { display:flex; flex-wrap:wrap; gap:8px; margin:16px 0 }
</style>
