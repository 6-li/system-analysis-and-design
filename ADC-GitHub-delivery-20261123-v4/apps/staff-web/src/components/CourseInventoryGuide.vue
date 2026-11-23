<script setup>
import {inventoryGuide} from './inventory-guide.js'
const updated=document.querySelector('meta[name="adc-quarterly-usability"]')?.content==='true'
const guide=inventoryGuide(document.querySelector('meta[name="adc-inventory-guide"]')?.content)
</script>
<template>
  <aside v-if="guide && !updated" class="course-guide">
    <h2>课程合成耗材资料</h2>
    <p>仅演示，非真实供应商或收款事实。诊所81001，用品9000001（件）；采购数量6，验收实收6、合成成本12.00元。</p>
    <p>采购确认引用：SYNTHETIC-INVENTORY-CONF-v1</p>
    <p>交付查询引用：SYNTHETIC-INVENTORY-DELIVERY-v1。请使用本次采购保存返回的需求编号。</p>
    <p>耗用依据：SYNTHETIC-INVENTORY-COST-v1；耗用数量2、成本4.00元，发生时间 {{guide.occurredAt}}。</p>
    <p>一条演示确认对应一条采购需求；重复新增会使交付关联不明确。来源不匹配时停止核验，不用任意文字替代。</p>
  </aside>
</template>
<style scoped>.course-guide{padding:16px;margin-bottom:20px;background:#edf4ef;border:1px solid #bacbc1;border-radius:10px;overflow-wrap:anywhere}.course-guide p{margin:8px 0}</style>
