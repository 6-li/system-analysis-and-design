<script setup>
import { computed, reactive, ref, watch, nextTick } from 'vue'
import { appointmentApi, appointmentDisposition, appointmentId, durationBasis, requestReference } from '../api/appointments.js'
import ScheduleCalendar from '../components/ScheduleCalendar.vue'
import MiniRequestInbox from '../components/MiniRequestInbox.vue'
import BookingSchemePicker from '../components/BookingSchemePicker.vue'
import ReceptionCalendar from '../components/ReceptionCalendar.vue'
const bookingBatch=document.querySelector('meta[name="adc-booking-batch-enabled"]')?.content==='true'
const interactiveBooking=document.querySelector('meta[name="adc-booking-interaction-enabled"]')?.content==='true'

const props = defineProps({ context: { type: Object, default: null }, api: { type: Object, default: null } })
function serverContext() {
  try { return JSON.parse(document.querySelector('meta[name="adc-appointments-context"]')?.content ?? 'null') }
  catch { return null }
}
const session = props.context ?? serverContext()
const source = session?.verifiedBookingSource
const validSource = typeof source?.reference === 'string' && !!source.reference.trim()
  && typeof source?.requestId === 'string' && !!source.requestId.trim()
  && typeof source?.patientId === 'string' && /^[1-9]\d*$/.test(source.patientId)
const sourceRequired = Boolean(session && Object.hasOwn(session, 'verifiedBookingSource'))
const hasChangeSource = Boolean(session && Object.hasOwn(session, 'verifiedChangeSource'))
const hasCancelSource = Boolean(session && Object.hasOwn(session, 'verifiedCancelSource'))
const existingAction = hasCancelSource ? 'cancel' : 'change'
const existingSource = hasCancelSource ? session.verifiedCancelSource : session?.verifiedChangeSource
const existingSourceRequired = hasChangeSource || hasCancelSource
const validExistingSource = !(hasChangeSource && hasCancelSource)
  && typeof existingSource?.reference === 'string' && !!existingSource.reference.trim()
  && typeof existingSource?.requestId === 'string' && !!existingSource.requestId.trim()
  && typeof existingSource?.appointmentId === 'string' && /^[1-9]\d*$/.test(existingSource.appointmentId)
let requestPreview=null
try {const encoded=document.querySelector('meta[name="adc-booking-source-preview"]')?.content;if(encoded)requestPreview=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(encoded),c=>c.charCodeAt(0))))}catch{requestPreview=null}
if(requestPreview && !((validSource && requestPreview.requestId===source.requestId && requestPreview.patientId===source.patientId)||(validExistingSource && requestPreview.requestId===existingSource.requestId && requestPreview.patientId===existingSource.patientId)))requestPreview=null
const requestFlow=interactiveBooking && validSource
const inbox=ref(null)
const receipt=ref(null)
const api = props.api ?? appointmentApi()
const actionLabels = { create: '新建预约', change: '改约', cancel: '取消预约', arrival: '登记到诊', noShow: '登记未到诊' }
const allowed = Array.isArray(session?.actions) ? Object.keys(actionLabels).filter(action =>
  session.actions.includes(action) && (!existingSourceRequired || action === existingAction) && (!requestFlow || action === 'create')) : []
const canRead = session?.canRead === true
const actor = typeof session?.requestActorScope === 'string' && /^[a-f0-9]{32}$/.test(session.requestActorScope)
  ? session.requestActorScope : ''
const storageKey = actor && 'adc.appointments.pending:' + actor
const action = ref(allowed[0] ?? '')
const target = ref(validExistingSource ? existingSource.appointmentId : '')
const detail = ref(null)
const support = ref(null)
const calendarZone = document.querySelector('meta[name="adc-clinic-zone"]')?.content || ''
const calendarEntries = computed(() => detail.value?.startsAt && detail.value?.endsAt ? [{
  kind: 'APPOINTMENT', appointmentId: detail.value.appointmentId, ref: detail.value.originScheduleRef,
  startsAt: detail.value.startsAt, endsAt: detail.value.endsAt, statusLabel: detail.value.statusLabel,
}] : [])
const busy = ref(false)
const loading = ref(false)
const pending = ref(null)
const message = ref('')
const storageFailed = ref(false)
const form = reactive({
  patient: validSource ? source.patientId : '', schedule: '', scheduleVersion: '', startsAt: '', endsAt: '', request: '',
  project: '', projectVersion: '', minutes: '', changeBasis: '', confirmation: '',
  verification: '', identity: '', clinic: '', dentist: '', pendingFinding: false,
})
try { pending.value = storageKey ? JSON.parse(sessionStorage.getItem(storageKey)) : null }
catch { storageFailed.value = true }
const receiptStorage=actor && 'adc.appointments.receipt:'+actor
try{const restored=receiptStorage?JSON.parse(sessionStorage.getItem(receiptStorage)):null;if(restored?.appointmentId===new URLSearchParams(location.search).get('receipt')&&appointmentId(restored.appointmentId))receipt.value=restored}catch{ /* No trustworthy success receipt to display. */ }
if (pending.value) message.value = '提交结果待核对。请保留原请求标识。'

const planAction = computed(() => action.value === 'create' || action.value === 'change')
const positiveInteger = value => /^[1-9]\d*$/.test(value) ? Number(value) : NaN
const basis = computed(() => durationBasis(action.value, detail.value?.durationBasis, {
  primaryProjectId: form.project, projectVersion: positiveInteger(form.projectVersion),
  adoptedDurationMinutes: positiveInteger(form.minutes),
}))
const preserved = computed(() => basis.value?.basisMode === 'PRESERVED_ORIGINAL')
const hasOffset = value => /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,6})?)?(Z|[+-]\d{2}:\d{2})$/.test(value)
  && Number.isFinite(Date.parse(value))
const proposal = computed(() => {
  const versionNo = positiveInteger(form.scheduleVersion)
  if (!basis.value || !appointmentId(form.schedule) || !Number.isSafeInteger(versionNo)
      || !hasOffset(form.startsAt) || !hasOffset(form.endsAt) || !form.request.trim()) return null
  return {
    effectiveScheduleRef: { scheduleId: form.schedule, versionNo }, startsAt: form.startsAt, endsAt: form.endsAt,
    treatmentRequest: form.request.trim(), durationBasis: basis.value,
  }
})

watch(() => JSON.stringify([action.value, proposal.value, form.patient, detail.value?.stateToken]), () => {
  form.confirmation = ''
}, { flush: 'sync' })
watch(action, () => {
  form.verification = ''
  form.identity = ''
  form.pendingFinding = false
}, { flush: 'sync' })
watch(target, () => {
  detail.value = null
  support.value = null
  form.verification = ''
  form.identity = ''
}, { flush: 'sync' })

const body = computed(() => {
  const operation = action.value
  if (existingSourceRequired && (!validExistingSource || operation !== existingAction
      || target.value !== existingSource.appointmentId)) return null
  const sourceReference = validExistingSource ? { sourceContextReference: existingSource.reference } : {}
  if (operation !== 'create' && (detail.value?.appointmentId !== target.value || !detail.value?.stateToken)) return null
  const token = { expectedAppointmentToken: detail.value?.stateToken }
  if (planAction.value) {
    if (!proposal.value || !form.confirmation.trim()) return null
    const plan = { scheme: proposal.value, acceptanceEvidenceReference: form.confirmation.trim() }
    if (operation === 'create') {
      if (!appointmentId(form.patient) || (sourceRequired && (!validSource || form.patient !== source.patientId))) return null
      return { patientId: form.patient, ...plan, ...(validSource ? { sourceContextReference: source.reference } : {}) }
    }
    if (!form.changeBasis.trim()) return null
    return { ...token, ...plan, changeBasis: form.changeBasis.trim(), ...sourceReference }
  }
  if (!form.verification.trim()) return null
  if (operation === 'cancel') return { ...token, cancellationVerification: form.verification.trim(), ...sourceReference }
  const finding = { ...token, verificationBasis: form.verification.trim() }
  if (form.pendingFinding) return { ...finding, finding: 'PENDING_VERIFICATION' }
  if (operation === 'noShow') return { ...finding, finding: 'NO_SHOW_VERIFIED' }
  return form.identity.trim() && appointmentId(form.clinic) && appointmentId(form.dentist)
    ? { ...finding, finding: 'ARRIVAL_VERIFIED', verifiedIdentityReference: form.identity.trim(),
      actualClinicId: form.clinic, actualDentistId: form.dentist } : null
})
const canSubmit = computed(() => allowed.includes(action.value) && !!storageKey && !!session?.csrfToken
  && !receipt.value && !pending.value && !storageFailed.value && !busy.value && !loading.value && !!body.value)

function sameRef(left, right) {
  return appointmentId(left?.scheduleId) && Number.isInteger(left?.versionNo)
    && left.scheduleId === right?.scheduleId && left.versionNo === right?.versionNo
}

async function useConfirmed(selection){
  if(action.value==='change'&&bookingBatch&&requestPreview?.selectedScheme)form.changeBasis=requestPreview.changeReason||'患者申请改约';
  const s=selection.scheme;form.patient=selection.patientId;form.schedule=s.effectiveScheduleRef.scheduleId;form.scheduleVersion=String(s.effectiveScheduleRef.versionNo);
  form.startsAt=s.startsAt;form.endsAt=s.endsAt;form.request=s.treatmentRequest;form.project=s.durationBasis.primaryProjectId;
  form.projectVersion=String(s.durationBasis.projectVersion);form.minutes=String(s.durationBasis.adoptedDurationMinutes);form.confirmation=selection.reference;
  if(selection.submit){
    await nextTick();
    if(canSubmit.value)await submit();
    else if(!pending.value)message.value='预约尚未提交，请检查当前登录权限或请求标识存储状态。';
  }
}
const visitLink=ref(null),visitLinkNotice=ref('')
const uiUnification=document.querySelector('meta[name="adc-ui-unification"]')?.content==='true'
async function readVisitLink(id){
 if(!uiUnification)return
 visitLink.value=null;visitLinkNotice.value=''
 try{const res=await fetch('/api/v1/staff/appointments/'+id+'/visit',{cache:'no-store',redirect:'error'}),body=await res.json();if(res.status!==200||body.appointmentId!==id)throw Error('READ_FAILED');if(target.value===id)visitLink.value=body.visit}
 catch{if(target.value===id)visitLinkNotice.value='就诊关联暂时无法读取，可重新查询。'}
}
async function lookup() {
  if (!canRead || !appointmentId(target.value) || loading.value || busy.value) return
  const id = target.value
  loading.value = true
  form.verification = ''
  form.identity = ''
  detail.value = null
  support.value = null
  try {
    const [found, current] = await Promise.all([api.read(id), api.read(id, true)])
    await readVisitLink(id)
    if (id !== target.value) return
    if (found.http !== 200 || current.http !== 200 || found.data?.appointmentId !== id
        || current.data?.appointmentId !== id || !found.data?.stateToken
        || found.data.currentSupport?.appointmentId !== id
        || !sameRef(found.data.originScheduleRef, current.data.originScheduleRef)
        || !sameRef(found.data.currentSupport.originScheduleRef, current.data.originScheduleRef)
        || !['SUPPORTED', 'NOT_SUPPORTED', 'UNRESOLVED'].includes(current.data.result)
        || found.data.currentSupport.result !== current.data.result
        || (current.data.result === 'SUPPORTED'
          && !sameRef(found.data.currentSupport.effectiveScheduleRef, current.data.effectiveScheduleRef))) {
      if (!pending.value) message.value = '无法读取预约，请核对权限和预约编号。'
      return
    }
    detail.value = found.data
    support.value = current.data
    if (!pending.value) message.value = '预约已读取。'
  } catch {
    if (!pending.value) message.value = '无法读取预约，请稍后重试。'
  } finally { loading.value = false }
}

async function submit() {
  if (!canSubmit.value) return
  const operation = action.value
  const id = target.value
  const payload = body.value
  const key = crypto.randomUUID()
  const marker = { key, reference: requestReference(operation, actor, key) }
  busy.value = true
  try {
    try {
      const existing = JSON.parse(sessionStorage.getItem(storageKey))
      if (existing) {
        pending.value = existing
        message.value = '提交结果待核对。请保留原请求标识。'
        return
      }
      sessionStorage.setItem(storageKey, JSON.stringify(marker))
      pending.value = marker
    } catch {
      storageFailed.value = true
      message.value = '无法保存请求标识，提交已停止。'
      return
    }
    const result = await api.write(operation, id, payload, key, session.csrfToken)
    const disposition = appointmentDisposition(operation, result, marker.reference, id)
    if (disposition === 'unknown') {
      message.value = '提交结果待核对。请保留原请求标识。'
      return
    }
    sessionStorage.removeItem(storageKey)
    pending.value = null
    form.confirmation = ''
    form.verification = ''
    if (disposition !== 'rejected') {
      if(operation!=='arrival')detail.value = null
      support.value = null
      if(disposition==='saved'&&operation==='arrival')await readVisitLink(id)
    }
    if (disposition === 'saved' && operation === 'create') {
      target.value=result.data.links.appointmentId
      receipt.value={appointmentId:target.value,requestId:validSource?source.requestId:null,requestReference:marker.reference,startsAt:payload.scheme.startsAt,endsAt:payload.scheme.endsAt,treatmentRequest:payload.scheme.treatmentRequest}
      try {sessionStorage.setItem(receiptStorage,JSON.stringify(receipt.value));history.replaceState(null,'',location.pathname+'?receipt='+encodeURIComponent(target.value))}catch{/* Confirmed server result remains visible even if local storage fails. */}
      await nextTick(); await inbox.value?.refresh()
    }
    message.value = {
      stale: '预约或时段已失鲜，请重新查询并取得申请人确认。',
      saved: operation === 'arrival' ? '办理已确认。就诊编号 ' + result.data.links.visitId
        : operation === 'create' ? '办理已确认。预约编号 ' + result.data.links.appointmentId : '办理已确认。',
      pending: '待核实记录已保存。',
      rejected: '本次办理未获确认。',
    }[disposition]
  } catch {
    message.value = '提交结果待核对。请保留原请求标识。'
  } finally { busy.value = false }
}
if(bookingBatch && validExistingSource)void lookup()
</script>

<template>
  <section class="appointments" aria-labelledby="appointments-title">
    <h1 id="appointments-title">{{ requestFlow ? '办理患者预约申请' : '预约与到诊工作台' }}</h1>
    <aside v-if="uiUnification && detail"><p>患者 {{detail.patientId}} · 预约 {{detail.appointmentId}}</p><p v-if="visitLink">就诊编号 <strong>{{visitLink.visitId}}</strong> · 实际接诊医生 {{visitLink.dentistId}}</p><p v-else>{{visitLinkNotice||'尚无关联就诊；登记到诊后可重新查询。'}}</p></aside>
    <template v-if="requestFlow && !receipt">
      <a href="/staff/appointments">返回申请待办与预约工作台</a>
      <aside class="request-summary"><h2>本次申请</h2><p>患者编号 {{source.patientId}} · 申请 {{source.requestId}}</p>
        <p>意向日期：{{requestPreview?.preferredDate || '未提供'}} · 诊疗需求：{{requestPreview?.treatmentNeed || '待与申请人核实'}}</p>
        <p>{{ requestPreview?.patientConfirmed ? '患者已选定预约时间，尚未生成正式预约。请核验后按原预约安排确认。' : '申请已关联患者，尚未生成正式预约。请选择预约时间并核实申请人确认，再确认预约。' }}</p></aside>
    </template>
    <section v-if="receipt" aria-label="预约成功回执" class="success-receipt"><h2>预约已确认</h2>
      <p>预约编号 {{receipt.appointmentId}} · 保存时状态：待到诊</p><p>{{receipt.startsAt}} 至 {{receipt.endsAt}}</p>
      <p>本次预约已保存；医生刷新个人日程可查看预约占用。患者到店后由前台登记到诊。</p>
      <p>请求引用 {{receipt.requestReference}}</p><a href="/staff/appointments">返回待办与预约查询</a>
    </section>
    <MiniRequestInbox v-if="!requestFlow || receipt" ref="inbox" />
    <p v-if="!canRead && !allowed.length">当前会话没有预约办理权限。</p>
    <ReceptionCalendar v-if="interactiveBooking && allowed.includes('create')" :initial-date="requestPreview?.preferredDate"/>
    <form v-if="canRead && !requestFlow && !receipt" class="lookup" @submit.prevent="lookup">
      <label>预约编号<input v-model.trim="target" :disabled="busy" :readonly="existingSourceRequired" inputmode="numeric"></label>
      <button type="submit" :disabled="loading || busy || !appointmentId(target)">查询预约</button>
    </form>
    <dl v-if="detail">
      <dt>预约状态</dt><dd>{{ detail.statusLabel }}</dd>
      <dt>历史安排</dt><dd>{{ detail.originScheduleRef?.scheduleId }} / {{ detail.originScheduleRef?.versionNo }}</dd>
      <dt>当前支持</dt><dd>{{ { SUPPORTED: '支持', NOT_SUPPORTED: '不支持', UNRESOLVED: '待核对' }[support?.result] ?? '待核对' }}</dd>
    </dl>
    <template v-if="calendarEntries.length">
      <ScheduleCalendar :entries="calendarEntries" :time-zone="calendarZone" title="当前预约的每周时段" />
      <p>这里只展示当前查询的预约，不是诊所全部排班或可预约时段。</p>
    </template>
    <form v-if="allowed.length && !receipt" @submit.prevent="submit">
      <fieldset :disabled="busy || !!pending">
        <p v-if="validExistingSource">已核验{{ hasCancelSource ? '取消' : '改约' }}申请 {{ existingSource.requestId }}</p>
        <p v-else-if="existingSourceRequired" role="alert">{{ hasCancelSource ? '取消' : '改约' }}申请核验信息不可用，请返回核验后再办理。</p>
        <label v-if="!requestFlow">办理事项<select v-model="action"><option v-for="option in allowed" :key="option" :value="option">{{ actionLabels[option] }}</option></select></label>
        <template v-if="planAction">
          <template v-if="action === 'create'">
            <p v-if="validSource && !requestFlow">已核验申请 {{ source.requestId }}</p>
            <p v-else-if="sourceRequired && !validSource" role="alert">申请核验信息不可用，请返回核验后再办理。</p>
            <label v-if="!requestFlow">患者编号<input v-model.trim="form.patient" :readonly="sourceRequired" inputmode="numeric"></label>
          </template>
          <p v-if="bookingBatch && action === 'change' && !requestPreview?.selectedScheme" role="alert">这条旧申请未选择具体时间，请拒绝并说明原因，由患者重新选择时间后提交。</p>
          <BookingSchemePicker v-if="interactiveBooking && (action === 'create' || action === 'change') && (!bookingBatch || action !== 'change' || requestPreview?.selectedScheme)" :patient="action === 'change' ? detail?.patientId : form.patient" :source-reference="validExistingSource ? existingSource.reference : validSource ? source.reference : undefined" :original-basis="action === 'change' ? detail?.durationBasis : undefined" :csrf="session?.csrfToken" :initial-date="requestPreview?.preferredDate" :initial-need="requestPreview?.treatmentNeed" :locked-scheme="requestPreview?.patientConfirmed === true ? requestPreview.selectedScheme : undefined" :auto-submit="requestFlow && requestPreview?.patientConfirmed === true" :disabled="busy || !!pending || storageFailed || !actor" @invalidate="form.confirmation=''" @confirmed="useConfirmed" />
          <label v-if="interactiveBooking && action === 'change' && !bookingBatch">改约理由<input v-model="form.changeBasis"></label>
          <template v-if="!interactiveBooking || !['create','change'].includes(action)">
          <label>生效安排编号<input v-model.trim="form.schedule" inputmode="numeric"></label>
          <label>安排版本<input v-model.trim="form.scheduleVersion" inputmode="numeric"></label>
          <label>开始时间（含偏移）<input v-model.trim="form.startsAt" placeholder="2026-11-12T09:00:00+08:00"></label>
          <label>结束时间（含偏移）<input v-model.trim="form.endsAt" placeholder="2026-11-12T09:27:00+08:00"></label>
          <label>诊疗需求<input v-model="form.request"></label>
          <label>主要项目编号<input v-model.trim="form.project" inputmode="numeric"></label>
          <template v-if="!preserved">
            <label>已发布项目版本<input v-model.trim="form.projectVersion" inputmode="numeric"></label>
            <label>已发布项目时长（分钟）<input v-model.trim="form.minutes" inputmode="numeric"></label>
          </template>
          <p v-if="basis" class="duration" data-testid="duration-basis">
            {{ preserved ? '沿用原预约' : '采用当前项目' }}版本 {{ basis.projectVersion }} · {{ basis.adoptedDurationMinutes }} 分钟
          </p>
          <label v-if="action === 'change'">改约理由<input v-model="form.changeBasis"></label>
          <label>申请人确认依据<input v-model="form.confirmation" :disabled="!proposal"></label>
          </template>
        </template>
        <template v-else>
          <label>人工核实依据<input v-model="form.verification"></label>
          <template v-if="action === 'arrival' || action === 'noShow'">
            <label class="inline"><input v-model="form.pendingFinding" type="checkbox">仍待核实</label>
            <template v-if="action === 'arrival' && !form.pendingFinding">
              <label>已核实身份依据<input v-model="form.identity"></label>
              <label>实际诊所编号<input v-model.trim="form.clinic" inputmode="numeric"></label>
              <label>实际牙医编号<input v-model.trim="form.dentist" inputmode="numeric"></label>
            </template>
          </template>
        </template>
        <button v-if="!(requestFlow && requestPreview?.patientConfirmed === true)" type="submit" :disabled="!canSubmit">{{ requestFlow ? '确认预约' : '提交办理' }}</button>
      </fieldset>
    </form>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="pending">请求标识 {{ pending.reference }}</p>
  </section>
</template>

<style scoped>
.request-summary,.success-receipt {padding:20px;border:1px solid #cadfd5;border-radius:10px;background:#f0f8f3;overflow-wrap:anywhere}
.appointments { display: grid; gap: 16px; }
h1, p { margin: 0; }
.lookup, fieldset { display: flex; flex-wrap: wrap; gap: 14px; align-items: end; }
fieldset { border: 1px solid #cbdce2; border-radius: 8px; padding: 16px; margin: 0; }
label { display: grid; gap: 3px; min-width: 180px; flex: 1; }
label.inline { display: flex; align-items: center; gap: 6px; }
input, select { border: 1px solid #b0c7d0; border-radius: 5px; padding: 8px; font: inherit; min-width: 0; }
dl { display: grid; grid-template-columns: auto 1fr; gap: 2px 12px; margin: 0; }
dd { margin: 0; }
.duration { flex-basis: 100%; color: #126778; }
</style>


