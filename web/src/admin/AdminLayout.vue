<script setup lang="ts">
import { computed, ref } from 'vue'
import { Activity, ChevronRight, Cross, Files, LayoutDashboard, ListTree, LogOut, SlidersHorizontal } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { adminLogout, adminSession } from '../api/admin'
import './admin.css'

const route = useRoute()
const inKnowledge = computed(() => route.name === 'admin-knowledge' || route.name === 'admin-document'
  || route.name === 'admin-knowledge-import')
const inToolLogs = computed(() => route.name === 'admin-tool-logs')
const inToolRegistry = computed(() => route.name === 'admin-tool-registry')
const inModelConfig = computed(() => route.name === 'admin-model-config')
const logoutPending = ref(false)
const logoutError = ref(false)

async function logout() {
  logoutPending.value = true
  logoutError.value = false
  try {
    await adminLogout()
  } catch {
    logoutError.value = true
    logoutPending.value = false
  }
}
</script>

<template>
  <!-- Sidebar, Navbar and AppMain shell adapted from RuoYi-Vue3 TypeScript (MIT). -->
  <div class="admin-surface admin-shell">
    <aside class="admin-sidebar">
      <RouterLink class="admin-brand" :to="{ name: 'admin-home' }">
        <Cross :size="20" aria-hidden="true" /><span>FreeMedi-Agent<small>智能导诊管理端</small></span>
      </RouterLink>
      <nav class="admin-menu" aria-label="管理菜单">
        <RouterLink :to="{ name: 'admin-home' }" :class="{ 'admin-menu-current': route.name === 'admin-home' }">
          <LayoutDashboard :size="18" aria-hidden="true" /> 工作台首页
        </RouterLink>
        <RouterLink :to="{ name: 'admin-knowledge' }" :class="{ 'admin-menu-current': inKnowledge }">
          <Files :size="18" aria-hidden="true" /> 院内资料
        </RouterLink>
        <RouterLink :to="{ name: 'admin-tool-logs' }" :class="{ 'admin-menu-current': inToolLogs }">
          <Activity :size="18" aria-hidden="true" /> 工具调用日志
        </RouterLink>
        <RouterLink :to="{ name: 'admin-tool-registry' }" :class="{ 'admin-menu-current': inToolRegistry }">
          <ListTree :size="18" aria-hidden="true" /> 工具注册表
        </RouterLink>
        <RouterLink :to="{ name: 'admin-model-config' }" :class="{ 'admin-menu-current': inModelConfig }">
          <SlidersHorizontal :size="18" aria-hidden="true" /> AI 模型配置
        </RouterLink>
      </nav>
    </aside>
    <div class="admin-main-container">
      <header class="admin-navbar">
        <nav class="admin-breadcrumb" aria-label="当前位置">
          <RouterLink :to="{ name: 'admin-home' }">工作台首页</RouterLink>
          <template v-if="inKnowledge">
            <ChevronRight :size="14" aria-hidden="true" />
            <RouterLink v-if="route.name !== 'admin-knowledge'" :to="{ name: 'admin-knowledge' }">院内资料</RouterLink>
            <span v-else aria-current="page">院内资料</span>
          </template>
          <template v-if="route.name === 'admin-document'">
            <ChevronRight :size="14" aria-hidden="true" /><span aria-current="page">版本详情</span>
          </template>
          <template v-if="route.name === 'admin-knowledge-import'">
            <ChevronRight :size="14" aria-hidden="true" /><span aria-current="page">导入预览</span>
          </template>
          <template v-if="inToolLogs">
            <ChevronRight :size="14" aria-hidden="true" /><span aria-current="page">工具调用日志</span>
          </template>
          <template v-if="inToolRegistry">
            <ChevronRight :size="14" aria-hidden="true" /><span aria-current="page">工具注册表</span>
          </template>
          <template v-if="inModelConfig">
            <ChevronRight :size="14" aria-hidden="true" /><span aria-current="page">AI 模型配置</span>
          </template>
        </nav>
        <div class="admin-profile">
          <span>{{ adminSession?.displayName }}</span>
          <button class="admin-button" type="button" :disabled="logoutPending" @click="logout">
            <LogOut :size="15" aria-hidden="true" /> {{ logoutPending ? '正在退出…' : '退出登录' }}
          </button>
        </div>
      </header>
      <main class="admin-page">
        <p v-if="logoutError" class="admin-form-error" role="alert">退出失败，请重试。</p>
        <RouterView />
      </main>
    </div>
  </div>
</template>
