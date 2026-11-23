<script setup>
import { ref } from 'vue'

const props = defineProps({ request: { type: Function, required: true } })
const id = ref('')
const detail = ref(null)
const support = ref(null)
const message = ref('')
let lookup = 0

function invalidate() {
  lookup++
  detail.value = null
  support.value = null
  message.value = ''
}

async function read() {
  invalidate()
  const current = lookup
  const target = id.value.trim()
  if (!/^-?\d+$/.test(target)) {
    message.value = '请输入预约编号。'
    return
  }
  message.value = '正在核对预约。'
  try {
    const path = '/api/v1/staff/appointments/' + encodeURIComponent(target)
    const [d, s] = await Promise.all([props.request(path), props.request(path + '/schedule-support')])
    if (current !== lookup) return
    const statuses = [d.status, s.status]
    if (statuses.some(status => status === 401 || status === 403)) {
      message.value = '当前无权查看预约。'
      return
    }
    if (statuses.includes(404) && statuses.every(status => status === 200 || status === 404)) {
      message.value = '未找到可查看的预约。'
      return
    }
    if (statuses.some(status => status !== 200) || d.body?.appointmentId !== target
        || s.body?.appointmentId !== target || d.body?.currentSupport?.appointmentId !== target
        || !sameReference(d.body.originScheduleRef, s.body.originScheduleRef)
        || !sameReference(d.body.currentSupport.originScheduleRef, s.body.originScheduleRef)
        || d.body.currentSupport.result !== s.body.result
        || (s.body.result === 'SUPPORTED'
          && !sameReference(d.body.currentSupport.effectiveScheduleRef, s.body.effectiveScheduleRef))
        || !['SUPPORTED', 'NOT_SUPPORTED', 'UNRESOLVED'].includes(s.body.result)) {
      message.value = '暂时无法核对预约，请稍后重新查询。'
      return
    }
    detail.value = d.body
    support.value = s.body
    message.value = '预约已读取。'
  } catch {
    if (current === lookup) message.value = '暂时无法核对预约，请稍后重新查询。'
  }
}

function sameReference(a, b) {
  return typeof a?.scheduleId === 'string' && Number.isInteger(a?.versionNo)
    && a.scheduleId === b?.scheduleId && a.versionNo === b?.versionNo
}

const supportText = { SUPPORTED: '当前安排支持', NOT_SUPPORTED: '当前安排不支持', UNRESOLVED: '当前支持待核对' }
</script>

<template>
  <section>
    <h1>核对预约</h1>
    <form @submit.prevent="read">
      <label>预约编号 <input v-model="id" @input="invalidate"></label>
      <button type="submit">查询</button>
    </form>
    <p role="status">{{ message }}</p>
    <dl v-if="detail && support">
      <dt>预约状态</dt><dd>{{ detail.statusLabel }}</dd>
      <dt>历史办理依据</dt><dd>{{ support.originScheduleRef.scheduleId }} / 版本 {{ support.originScheduleRef.versionNo }}</dd>
      <dt>当前支持</dt><dd>{{ supportText[support.result] }}</dd>
      <template v-if="support.result === 'SUPPORTED'">
        <dt>当前生效安排</dt><dd>{{ support.effectiveScheduleRef.scheduleId }} / 版本 {{ support.effectiveScheduleRef.versionNo }}</dd>
      </template>
      <dt>冻结时长</dt><dd>{{ detail.durationBasis ? detail.durationBasis.adoptedDurationMinutes + ' 分钟' : '无可核验依据' }}</dd>
    </dl>
  </section>
</template>
