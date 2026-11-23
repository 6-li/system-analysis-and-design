<script setup>
import {ref,watch,computed} from 'vue'
const props=defineProps({quarter:{type:String,default:''},clinics:{type:String,default:''},disabled:Boolean})
const emit=defineEmits(['update:quarter','update:clinics'])
const year=ref(props.quarter.slice(0,4)||String(new Date(Date.now()+8*3600000).getUTCFullYear()))
const number=ref(props.quarter.slice(5)||'')
const selected=computed(()=>props.clinics.split(/[,，]/).map(x=>x.trim()).filter(Boolean))
function periodChanged(){emit('update:quarter',/^[0-9]{4}$/.test(year.value)&&Number(year.value)>0&&Number(year.value)<9999&&/^Q[1-4]$/.test(number.value)?year.value+'-'+number.value:'')}
function toggle(id,checked){const list=new Set(selected.value);checked?list.add(id):list.delete(id);emit('update:clinics',Array.from(list).sort().join(','))}
watch(()=>props.quarter,value=>{if(/^[0-9]{4}-Q[1-4]$/.test(value)){year.value=value.slice(0,4);number.value=value.slice(5)}})
</script>
<template><div class="quarter-scope-picker"><div class="quarter-period"><label>统计年度<input v-model="year" type="number" min="1" max="9998" :disabled="disabled" @input="periodChanged"></label><label>统计季度<select v-model="number" :disabled="disabled" @change="periodChanged"><option value="" disabled>请选择季度</option><option value="Q1">第一季度 · 1—3月</option><option value="Q2">第二季度 · 4—6月</option><option value="Q3">第三季度 · 7—9月</option><option value="Q4">第四季度 · 10—12月</option></select></label></div><fieldset class="clinic-options"><legend>统计诊所</legend><label v-for="id in ['81001','81002']" :key="id"><input type="checkbox" :checked="selected.includes(id)" :disabled="disabled" @change="toggle(id,$event.target.checked)">诊所 {{id}}</label></fieldset><p class="scope-hint">{{quarter||'请选择年份和季度'}} · {{selected.length?'已选 '+selected.join('、'):'请至少选择一家诊所'}} · 北京时间自然季度</p></div></template>
<style scoped>.quarter-period{display:grid;grid-template-columns:1fr 1.5fr;gap:16px}.quarter-period label{display:grid;gap:6px}.clinic-options{display:flex;gap:24px;flex-wrap:wrap;margin:16px 0 0;padding:12px 16px;background:#f6faf7}.clinic-options label{display:flex;gap:8px;align-items:center}.scope-hint{font-size:12px;color:#738c7c;margin:12px 0 0}@media(max-width:600px){.quarter-period{grid-template-columns:1fr}}</style>
