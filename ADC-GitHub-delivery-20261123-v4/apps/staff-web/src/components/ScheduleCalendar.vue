<script setup>
import { computed, ref, watch } from 'vue'
import { addCalendarDays, calendarDate, calendarProjection } from '../services/schedule-calendar.js'
const props = defineProps({ entries: { type: Array, default: () => [] }, timeZone: { type: String, default: '' },
  title: { type: String, default: '每周接诊安排' }, partial: Boolean, focusDate:String, appointmentsOnly:Boolean })
const emit = defineEmits(['select','date-change'])
const displayZone = ref('')
const zone = computed(() => props.timeZone || displayZone.value)
const anchor = ref('')
const dentist = ref('')
const status = ref(props.entries.some(row => row.activationStatus) ? 'EFFECTIVE' : '')
const selected = ref(null)
watch([dentist, status, anchor], () => { selected.value = null })
watch(()=>props.focusDate,value=>{if(value&&/^\d{4}-\d{2}-\d{2}$/.test(value))anchor.value=value},{immediate:true})
watch(anchor,value=>{if(value)emit('date-change',value)})
const errors = ref('')
const dentists = computed(() => [...new Set(props.entries.map(row => row.dentistId).filter(Boolean))])
const visible = computed(() => props.entries.filter(row => (!dentist.value || row.dentistId === dentist.value)
  && (!status.value || row.activationStatus === status.value)))
watch([() => props.entries, zone], () => {
  selected.value = null
  if (anchor.value || !zone.value || !props.entries.length) return
  try { anchor.value = calendarDate(props.entries[0].startsAt, zone.value) } catch { /* Explicit warning below. */ }
}, { immediate: true })
const model = computed(() => {
  if (!zone.value || !anchor.value) return null
  try { errors.value = ''; return calendarProjection(visible.value, zone.value, anchor.value) }
  catch { errors.value = '日期、时区或时间数据无法转换，请核对配置。'; return null }
})
const hours = computed(() => model.value ? Array.from({ length: model.value.endHour - model.value.startHour + 1 }, (_, i) => model.value.startHour + i) : [])
function shift(days) { if (anchor.value) { anchor.value = addCalendarDays(anchor.value, days); selected.value = null } }
function slotStyle(slot) {
  return { top: `${(slot.startMinute - model.value.startHour * 60) * .85}px`,
    height: `${Math.max(26, (slot.endMinute - slot.startMinute) * .85)}px`,
    left: `calc(${slot.lane * 100 / slot.lanes}% + 3px)`, width: `calc(${100 / slot.lanes}% - 6px)` }
}
function choose(slot) { selected.value = slot; emit('select', slot) }
</script>

<template>
  <section class="calendar" :aria-label="title">
    <header class="calendar-toolbar">
      <div><h2>{{ title }}</h2><p>只读日历 · {{ zone || '尚未选择展示时区' }}</p></div>
      <div class="calendar-controls">
        <label v-if="!timeZone">展示时区<select v-model="displayZone"><option value="">请选择</option>
          <option value="Etc/UTC">UTC</option><option value="Asia/Shanghai">中国标准时间</option></select></label>
        <label>周内日期<input v-model="anchor" type="date"></label>
        <button type="button" :disabled="!anchor" @click="shift(-7)">上一周</button>
        <button type="button" :disabled="!zone" @click="anchor=calendarDate(new Date().toISOString(),zone)">本周</button>
        <button type="button" :disabled="!anchor" @click="shift(7)">下一周</button>
      </div>
    </header>
    <p v-if="!timeZone" class="calendar-warning">诊所业务时区未随页面提供；请选择展示时区。此选择只改变显示，不修改业务时间或诊所配置。</p>
    <div class="calendar-filters">
      <label v-if="dentists.length">牙医<select v-model="dentist"><option value="">全部已加载牙医</option>
        <option v-for="id in dentists" :key="id" :value="id">牙医 {{ id }}</option></select></label>
      <label v-if="entries.some(row => row.activationStatus === 'DRAFT' || row.activationStatus === 'SUPERSEDED')">安排状态
        <select v-model="status"><option value="">全部状态</option><option value="EFFECTIVE">已生效</option>
          <option value="DRAFT">待生效草案</option><option value="SUPERSEDED">历史版本</option></select></label>
      <span v-if="appointmentsOnly" class="legend appointment">预约</span>
      <template v-else-if="entries.some(row=>row.kind==='FREE')"><span class="legend effective">空闲</span><span class="legend appointment">已预约 / 跨店占用</span></template>
      <template v-else><span class="legend effective">已生效班次</span><span class="legend draft">待生效草案</span><span class="legend superseded">历史版本</span></template>
    </div>
    <p v-if="status === '' && entries.some(row => row.activationStatus === 'DRAFT' || row.activationStatus === 'SUPERSEDED')" class="calendar-warning">当前含草案或历史版本，同一安排可能重复显示；这些卡片不代表同时接诊的患者。</p>
    <p v-if="partial" class="calendar-warning">仅展示已加载记录，请继续加载其余页面后再核对。空白不代表可预约。</p>
    <p v-if="errors" role="alert">{{ errors }}</p>
    <p v-if="model?.rejected" role="alert">{{ model.rejected }}条时间数据无法展示，请查看原列表核对。</p>
    <div v-if="model" class="calendar-scroll">
      <div class="week-head"><span>时间</span><strong v-for="(day, i) in model.days" :key="day.date">{{ ['周一','周二','周三','周四','周五','周六','周日'][i] }}<small>{{ day.date }}</small></strong></div>
      <div class="week-grid" :style="{ height: `${(model.endHour - model.startHour) * 51}px` }">
        <div class="time-gutter"><span v-for="hour in hours" :key="hour" :style="{ top: `${(hour-model.startHour)*51}px` }">{{ String(hour).padStart(2,'0') }}:00</span></div>
        <div v-for="day in model.days" :key="day.date" class="calendar-day" :aria-label="day.date">
          <button v-for="slot in day.slots" :key="slot.key" type="button" class="calendar-event" :class="slot.status.toLowerCase()"
            :style="slotStyle(slot)" :title="`${slot.statusText} · ${slot.startsAt} 至 ${slot.endsAt}`" @click="choose(slot)">
            <strong>{{ slot.startsLabel.split(' ')[0] }}<br>{{ slot.endsLabel.split(' ')[0] }}</strong><span>{{appointmentsOnly && slot.status==='APPOINTMENT' ? '预约' : slot.statusText}}</span>
            <small v-if="slot.dentistId">牙医 {{ slot.dentistId }}</small><small v-if="slot.clinicId">诊所 {{ slot.clinicId }}</small>
          </button>
        </div>
      </div>
    </div>
    <p v-if="model && !model.days.some(day => day.slots.length)">本周没有已加载的记录。</p>
    <aside v-if="selected" class="calendar-detail" aria-live="polite">
      <strong>{{ selected.statusText }}</strong><p>时间：{{ selected.startsAt }} 至 {{ selected.endsAt }}</p>
      <p v-if="selected.refLabel">安排 / 版本：{{ selected.refLabel }}</p>
      <p v-if="selected.dentistId">牙医：{{ selected.dentistId }}</p><p v-if="selected.clinicId">诊所：{{ selected.clinicId }}</p>
      <p v-if="selected.appointmentId">预约：{{ selected.appointmentId }}</p>
      <p v-if="selected.appointmentCount !== undefined">该班次累计占用预约 {{ selected.appointmentCount }}笔，不代表同时接诊人数，也不是剩余容量。</p>
      <p v-if="selected.offsetChanged">跨夏令时转换，请以完整起止时间及UTC偏移为准。</p>
      <p v-if="selected.status === 'DRAFT'">待生效草案不能用于正式预约。</p>
    </aside>
    <p class="calendar-footnote">空白区域不表示已核验可约；跨店冲突与容量仍由服务端核验。卡片布局不参与业务判定。</p>
  </section>
</template>

<style scoped>
.calendar-event.free{background:#d9eee4;color:#155d4c}.legend.appointment{background:#dceafa;color:#294f7a}
.calendar{background:#fffdf8;border:1px solid #dce6df;border-radius:14px;padding:20px;margin:20px 0;color:#183f3b}
.calendar-toolbar{display:flex;justify-content:space-between;gap:16px;flex-wrap:wrap}.calendar h2{margin:0}.calendar p{margin:8px 0}
.calendar-toolbar p,.calendar-footnote{font-size:13px;color:#61746d}.calendar-controls,.calendar-filters{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.calendar label{display:flex;gap:6px;align-items:center;font-size:13px}.calendar input,.calendar select{width:auto;padding:7px;border:1px solid #ccd9d2;border-radius:6px}
.calendar-controls button{padding:8px 12px;background:#edf3ee;color:#183f3b;border:1px solid #ccd9d2;border-radius:6px}
.calendar-filters{margin:16px 0}.legend{font-size:12px;padding:5px 9px;border-radius:5px}.effective{background:#d9eee4;color:#155d4c}.draft{background:#fff0d5;color:#7f5010}.superseded{background:#e9edf0;color:#5b6871}
.calendar-scroll{overflow:auto;max-height:620px;border:1px solid #dce6df;border-radius:8px}.week-head,.week-grid{display:grid;grid-template-columns:64px repeat(7,minmax(144px,1fr));min-width:1072px}
.week-head{position:sticky;top:0;z-index:2;background:#f0f5ef;min-height:56px;border-bottom:1px solid #ccd9d2}.week-head>*{text-align:center;padding:10px 4px;font-size:13px}.week-head small{display:block;margin-top:4px;font-size:11px;font-weight:normal;color:#61746d}
.time-gutter{position:relative;background:#f8faf7}.time-gutter span{position:absolute;left:8px;font-size:11px;color:#61746d;transform:translateY(-40%)}
.calendar-day{position:relative;border-left:1px solid #dce6df;background:repeating-linear-gradient(to bottom,transparent 0,transparent 50px,#edf1ed 50px,#edf1ed 51px)}
.calendar-event{position:absolute;overflow:hidden;text-align:left;border:0;border-left:3px solid currentColor;border-radius:5px;padding:5px;cursor:pointer;line-height:1.4;min-width:0;box-sizing:border-box}
.calendar-event strong,.calendar-event span,.calendar-event small{display:block;font-size:10px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.calendar-event.effective{background:#d9eee4;color:#155d4c}.calendar-event.draft{background:#fff0d5;color:#7f5010}.calendar-event.superseded{background:#e9edf0;color:#5b6871}.calendar-event.appointment{background:#dceafa;color:#294f7a}.calendar-event.unknown{background:#fce3df;color:#8c3d32}.calendar-event:focus-visible{outline:3px solid #b9784e;outline-offset:1px}
.calendar-detail{margin-top:14px;padding:14px;background:#f0f5ef;border-radius:8px;font-size:13px;overflow-wrap:anywhere}.calendar-warning{font-size:13px;color:#805528}
@media(max-width:600px){.calendar{padding:12px}.calendar-controls{align-items:flex-start}}
</style>
