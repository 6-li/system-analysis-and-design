<script setup>
import { computed, onMounted, ref } from 'vue'
import VisitPicker from '../components/VisitPicker.vue'
import { readDentistSchedule } from '../api/dentist-schedule.js'
import { scheduleDays } from '../services/schedule-display.js'
import ScheduleCalendar from '../components/ScheduleCalendar.vue'
import {localText} from '../api/booking-time.js'
import {calendarDate} from '../services/schedule-calendar.js'
const zone = document.querySelector('meta[name="adc-clinic-zone"]')?.content || ''
const selectedDate = ref(zone ? calendarDate(new Date().toISOString(),zone) : '')
const binding=ref(null)
const entries = ref([])
const cursor = ref('')
const busy = ref(false)
const notice = ref('')
const days = computed(() => {
  try { return scheduleDays(entries.value, zone) }
  catch { return [] }
})
const selected = computed(() => days.value.find(day => day.date === selectedDate.value))
const calendarEntries = computed(() => entries.value.flatMap(row => (row.appointments||[]).map(a=>({...a,kind:'APPOINTMENT',activationStatus:'EFFECTIVE',clinicId:row.clinicId,ref:{scheduleId:row.scheduleId,versionNo:row.versionNo}}))))
async function load(more = false) {
  if (busy.value) return
  busy.value = true
  notice.value = ''
  try {
    const result = await readDentistSchedule(more ? cursor.value : '',fetch,true)
    entries.value = more ? [...entries.value, ...result.items] : result.items
    binding.value=result.dentistId ? {dentistId:result.dentistId,clinicId:result.clinicId}:null
    cursor.value = result.nextCursor || ''
    if (!zone) notice.value = '诊所时区尚未配置，请联系管理员。'
    else if (!entries.value.length) notice.value = '暂无正式接诊安排。'
    else if (!selectedDate.value) selectedDate.value = calendarDate(new Date().toISOString(),zone)
  } catch {
    entries.value = []
    cursor.value = ''
    notice.value = '无法读取接诊安排，请检查登录状态或稍后重试。'
  } finally { busy.value = false }
}
onMounted(() => load())
function goToVisit(row){window.location.assign('/staff/visit-health?patientId='+encodeURIComponent(row.patientId)+'&visitId='+encodeURIComponent(row.visitId))}
</script>

<template>
  <VisitPicker @selected="goToVisit" />
  <main class="schedule-page" :aria-busy="busy">
    <div class="schedule-heading"><div><h1>我的预约日程</h1><p v-if="zone">诊所当地时间 · {{ zone }}</p></div>
      <button :disabled="busy" @click="load()">刷新</button></div>
    <p>当前为医生个人账号，只显示本账号绑定医生的预约，不是整个门诊的共享日程。</p>
    <p v-if="binding">医生编号 {{binding.dentistId}} · 门诊 {{binding.clinicId}}</p>
    <p role="status">{{ notice }}</p>
    <ScheduleCalendar :entries="calendarEntries" :time-zone="zone" :partial="!!cursor" :focus-date="selectedDate" appointments-only title="我的每周预约" @date-change="selectedDate=$event" />
    <nav v-if="days.length" class="day-list" aria-label="接诊日期">
      <button v-for="day in days" :key="day.date" :aria-pressed="selectedDate === day.date"
        @click="selectedDate = day.date">{{ day.date }}</button>
    </nav>
    <details v-if="selected" class="day-panel">
      <summary>{{ selected.date }} · 查看本人接诊班次</summary>
      <article v-for="item in selected.entries" :key="item.scheduleId" class="schedule-slot">
        <div class="time-range"><strong>{{ item.startLabel }}</strong><span>{{ item.endLabel }}</span></div>
        <div><strong>诊所 {{ item.clinicId }}</strong><p>该班次累计占用预约 {{ item.appointmentCount }} 笔，不代表同时接诊。</p>
          <small>正式安排 {{ item.scheduleId }} · 版本 {{ item.versionNo }}</small>
          <ul v-if="item.appointments?.length" aria-label="该班次预约"><li v-for="a in item.appointments" :key="a.appointmentId">{{localText(a.startsAt,zone)}} — {{localText(a.endsAt,zone)}} · 预约 {{a.appointmentId}} · {{a.statusLabel}}</li></ul></div>
      </article>
    </details>
    <p v-if="cursor">当前仅显示已加载的安排，可继续读取更多日期。</p>
    <button v-if="cursor" :disabled="busy" @click="load(true)">加载更多安排</button>
  </main>
</template>

<style scoped>
.schedule-heading { display:flex; justify-content:space-between; align-items:center; gap:24px; }
h1 { margin:0; } h2 { margin-top:0; } p { margin:6px 0; }
.schedule-heading p, small { color:var(--adc-muted); }
.day-list { display:flex; gap:8px; overflow-x:auto; padding:12px 0 20px; }
.day-list button { white-space:nowrap; background:var(--adc-surface); color:var(--adc-ink); border:1px solid var(--adc-border); }
.day-list button[aria-pressed="true"] { background:var(--adc-primary); color:white; border-color:var(--adc-primary); }
.day-panel { padding:24px; }
.schedule-slot { display:flex; gap:24px; border-top:1px solid var(--adc-border); padding:22px 0; }
.time-range { display:flex; flex-direction:column; min-width:90px; color:var(--adc-primary);
  border-left:3px solid var(--adc-accent); padding-left:14px; }
@media (max-width:600px) { .schedule-heading { align-items:flex-start; } .schedule-slot { gap:12px; } }
</style>
