<script setup>
import { computed, ref } from 'vue'

const csrf = document.querySelector('meta[name="adc-uc13-csrf"]')?.content
const clinic = ref('')
const supply = ref('')
const stock = ref(null)
const notice = ref('')
const busy = ref(false)
const validId = value => /^[1-9]\d*$/.test(value)
const ready = computed(() => Boolean(csrf && validId(clinic.value) && (!supply.value || validId(supply.value))))

async function query() {
  if (!ready.value || busy.value) return
  busy.value = true
  stock.value = null
  notice.value = ''
  const key = crypto.randomUUID()
  try {
    const response = await fetch('/api/v1/staff/clinics/' + clinic.value + '/stock-inquiry', {
      method: 'POST', cache: 'no-store', redirect: 'error',
      headers: { 'X-CSRF-Token': csrf, 'Idempotency-Key': key, 'Content-Type': 'application/json' },
      body: JSON.stringify({ supplyId: supply.value || null }),
    })
    const result = await response.json()
    const snapshot = result.stock
    const reference = result.requestReference
    if (response.status !== 200 || typeof reference !== 'string' || !reference.startsWith('UC/v1/UC13/')
        || !reference.endsWith('/STOCK_INQUIRY/' + key) || snapshot?.clinicId !== clinic.value
        || snapshot.supplyId !== (supply.value || null) || !Array.isArray(snapshot.items)
        || !Number.isFinite(Date.parse(snapshot.queriedAt))) {
      throw new Error('QUERY_UNAVAILABLE')
    }
    stock.value = snapshot
  } catch {
    notice.value = '未取得库存，请核对查询范围和访问权限后重试。'
  } finally {
    busy.value = false
  }
}

function localTime(value) {
  return new Date(value).toLocaleString('zh-CN')
}
</script>

<template>
  <section class="stock-inquiry">
    <h1>医疗用品库存及预警</h1>
    <p v-if="!csrf">请从已授权的诊所负责人工作台打开。</p>
    <form @submit.prevent="query">
      <fieldset :disabled="!csrf || busy">
        <label>诊所编号<input v-model.trim="clinic" inputmode="numeric" required></label>
        <label>用品编号（选填，留空查询全部）<input v-model.trim="supply" inputmode="numeric"></label>
        <button :disabled="!ready">查询库存</button>
      </fieldset>
    </form>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="stock">
      <h2>查询结果</h2>
      <p>诊所 {{ stock.clinicId }} · {{ stock.supplyId ? '用品 ' + stock.supplyId : '全部用品' }} ·
        查询时间 <time :datetime="stock.queriedAt">{{ localTime(stock.queriedAt) }}</time></p>
      <p v-if="!stock.items.length">此查询范围没有用品记录。</p>
      <div v-else class="table-scroll">
        <table>
          <thead><tr><th>医疗用品</th><th>库存</th><th>预警下限</th><th>已有预警（含临近有效期）</th></tr></thead>
          <tbody>
            <tr v-for="item in stock.items" :key="item.supplyId">
              <td>{{ item.name }}</td>
              <td>{{ item.stockPresent ? item.onHand + ' ' + item.unit : '无库存记录' }}</td>
              <td>{{ item.threshold === null ? '未维护' : item.threshold + ' ' + item.unit }}</td>
              <td>{{ item.existingWarning || '暂无已维护的预警信息' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.stock-inquiry, fieldset { display: grid; gap: 12px; }
fieldset { max-width: 38rem; }
label { display: grid; gap: 4px; }
button { justify-self: start; }
.table-scroll { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: 8px; text-align: left; border-bottom: 1px solid #dce8ed; }
</style>
