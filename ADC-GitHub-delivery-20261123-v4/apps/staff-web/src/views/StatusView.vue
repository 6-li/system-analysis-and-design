<script setup>
import { ref } from 'vue';
import { technicalErrorMessage } from '../services/http.js';
import { checkBackend } from '../services/health.js';
const message=ref('尚未检查'), busy=ref(false);
async function check(){busy.value=true;try{await checkBackend();message.value='技术服务连接正常'}catch(error){message.value=technicalErrorMessage(error)}finally{busy.value=false}}
</script>
<template><section><h1>开发工作台</h1><p>当前仅提供环境检查。预约、患者资料及收费功能尚未开放。</p><button :disabled="busy" @click="check">{{busy?'检查中…':'检查后端连接'}}</button><p role="status" aria-live="polite">{{message}}</p></section></template>
