<script setup>
import {computed,onMounted} from 'vue'
import {useRoute} from 'vue-router'
import CourseInventoryGuide from './components/CourseInventoryGuide.vue'
import {workspaceContext} from './services/staff-workspace.js'
import './styles/workspace.css'
const context=workspaceContext(document),route=useRoute()
const title=computed(()=>context.links.find(item=>item.href===route.path)?.title||'系统工作台')
onMounted(()=>{if(context.source&&context.links.length)context.source.hidden=true})
</script>
<template>
 <a class="skip-content" href="#workspace-main">跳转到工作区</a>
 <div class="application-layout" :class="{'without-sidebar':!context.links.length}">
  <aside v-if="context.links.length" class="workspace-sidebar">
   <a class="brand" href="/" aria-label="ADC 系统首页"><span class="brand-mark">ADC</span><span><strong>牙科连锁诊所</strong><span class="brand-caption">运营管理系统</span></span></a>
   <div class="sidebar-heading">岗位工作台</div>
   <nav class="module-navigation" aria-label="功能导航"><a v-for="(item,index) in context.links" :key="item.href" :href="item.href" :class="{active:route.path===item.href}" :aria-current="route.path===item.href?'page':undefined"><span class="navigation-number">{{String(index+1).padStart(2,'0')}}</span>{{item.title}}<span v-if="route.path===item.href" class="navigation-dot"></span></a></nav>
   <div class="sidebar-footer"><span class="sidebar-indicator"></span>课程运行环境<span>按当前岗位显示可用功能</span></div>
  </aside>
  <div class="workspace-body">
   <header class="app-header"><div class="workspace-breadcrumb"><span>ADC 工作台</span><span aria-hidden="true">/</span><strong>{{title}}</strong></div><div class="header-tools"><span class="environment-label">课程演示</span><details class="account-menu"><summary><span class="role-avatar">{{context.role.slice(0,1)}}</span><span><span class="role-caption">当前岗位</span><strong>{{context.role}}</strong></span><span class="account-chevron">⌄</span></summary><div class="account-popover"><span class="role-caption">当前登录岗位</span><strong>{{context.role}}</strong><p>可用功能以服务端授权为准。</p><a href="/">切换岗位 / 重新登录 →</a></div></details></div></header>
   <div id="workspace-main" class="workspace" tabindex="-1"><div class="workspace-heading"><div><p>WORKSPACE</p><h2>{{title}}</h2></div><span class="workspace-role">{{context.role}}</span></div><CourseInventoryGuide /><RouterView /></div>
  </div>
 </div>
</template>
