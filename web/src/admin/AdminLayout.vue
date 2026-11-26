<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Activity, ChartNoAxesColumnIncreasing, ChevronDown, ChevronRight, Cross, Database, Files, LayoutDashboard, LogOut, Menu, Settings, SlidersHorizontal } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { adminLogout, adminRoleLabels, adminSession, canOpenAdmin } from '../api/admin'
import './admin.css'

const groups = [
  { id: 'catalog', label: '基础数据管理', icon: Database, pages: [
    { name: 'admin-departments', label: '科室管理' },
    { name: 'admin-doctors', label: '医生管理' },
    { name: 'admin-schedules', label: '排班管理' },
    { name: 'admin-appointments', label: '预约明细' },
    { name: 'admin-today-triage', label: '今日分诊' },
    { name: 'admin-visit-review', label: '就诊结果复核' },
  ] },
  { id: 'ai', label: 'AI 能力配置', icon: SlidersHorizontal, pages: [
    { name: 'admin-model-config', label: 'AI 模型配置' },
    { name: 'admin-prompt-templates', label: 'Prompt 模板' },
  ] },
  { id: 'knowledge', label: '医学知识库（RAG）', icon: Files, pages: [
    { name: 'admin-knowledge-library', label: '医学知识库' },
    { name: 'admin-knowledge', label: '知识文档' },
    { name: 'admin-knowledge-chunks', label: '知识片段' },
    { name: 'admin-knowledge-index', label: '向量化与检索' },
  ] },
  { id: 'tools', label: 'Agent 工具中心', icon: Activity, pages: [
    { name: 'admin-tool-registry', label: '工具注册表' },
    { name: 'admin-tool-logs', label: '工具调用日志' },
  ] },
  { id: 'observability', label: '统计与观测', icon: ChartNoAxesColumnIncreasing, pages: [
    { name: 'admin-visit-operations', label: '就诊运营看板' },
    { name: 'admin-agent-timeline', label: 'Agent 执行时间线' },
    { name: 'admin-model-calls', label: 'AI 调用观测' },
  ] },
  { id: 'settings', label: '系统设置', icon: Settings, pages: [
    { name: 'admin-staff', label: '岗位账号' },
    { name: 'admin-setup', label: '首次启用' },
  ] },
]

const route = useRoute()
const visibleGroups = computed(() => groups.map(group => ({ ...group, pages: group.pages.filter(page => canOpenAdmin(page.name)) }))
  .filter(group => group.pages.length))
const roleNames = computed(() => [...new Set(adminSession.value?.grants?.map(grant => adminRoleLabels[grant.role]))].join('、'))
const isKnowledgeDetail = computed(() => route.name === 'admin-document' || route.name === 'admin-knowledge-import')
const selectedName = computed(() => isKnowledgeDetail.value ? 'admin-knowledge' : route.name)
const selectedGroup = computed(() => groups.find(group => group.pages.some(page => page.name === selectedName.value)))
const selectedPage = computed(() => selectedGroup.value?.pages.find(page => page.name === selectedName.value))
const expanded = ref<string[]>([])
const mobileOpen = ref(false)
const logoutPending = ref(false)
const logoutError = ref(false)
const content = ref<HTMLElement>()

watch(() => route.fullPath, () => {
  const id = selectedGroup.value?.id
  if (id && !expanded.value.includes(id)) expanded.value.push(id)
  mobileOpen.value = false
}, { immediate: true })

function scrollToRoute() {
  if (!content.value) return
  const target = route.hash && document.getElementById(route.hash.slice(1))
  if (target && content.value.contains(target)) target.scrollIntoView({ block: 'start' })
  else content.value.scrollTop = 0
}

onMounted(scrollToRoute)
watch([() => route.path, () => route.hash], scrollToRoute, { flush: 'post' })

function toggle(id: string) {
  expanded.value = expanded.value.includes(id) ? expanded.value.filter(value => value !== id) : [...expanded.value, id]
}

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
      <div class="admin-sidebar-head">
        <RouterLink class="admin-brand" :to="{ name: 'admin-home' }">
          <Cross :size="20" aria-hidden="true" /><span>FreeMedi-Agent<small>智能导诊管理端</small></span>
        </RouterLink>
        <button class="admin-mobile-menu" type="button" :aria-expanded="mobileOpen" aria-controls="admin-sidebar-menu"
          @click="mobileOpen = !mobileOpen">
          <Menu :size="18" aria-hidden="true" /> {{ mobileOpen ? '收起菜单' : '展开菜单' }}
        </button>
      </div>
      <div id="admin-sidebar-menu" class="admin-sidebar-body" :class="{ 'admin-sidebar-body--open': mobileOpen }">
        <nav class="admin-menu" aria-label="管理菜单">
          <RouterLink :to="{ name: 'admin-home' }" :class="{ 'admin-menu-current': route.name === 'admin-home' }">
            <LayoutDashboard :size="18" aria-hidden="true" /> 工作台首页
          </RouterLink>
          <div v-for="group in visibleGroups" :key="group.id">
            <button class="admin-menu-toggle" type="button" :aria-expanded="expanded.includes(group.id)"
              :aria-controls="'admin-menu-' + group.id" :class="{ 'admin-menu-group-current': selectedGroup?.id === group.id }"
              @click="toggle(group.id)">
              <component :is="group.icon" :size="18" aria-hidden="true" />
              <span>{{ group.label }}</span>
              <ChevronDown :size="15" aria-hidden="true" />
            </button>
            <div v-show="expanded.includes(group.id)" :id="'admin-menu-' + group.id" class="admin-submenu">
              <RouterLink v-for="page in group.pages" :key="page.name" :to="{ name: page.name }"
                :class="{ 'admin-menu-current': selectedName === page.name }"
                :aria-current="selectedName === page.name ? 'page' : undefined">{{ page.label }}</RouterLink>
            </div>
          </div>
        </nav>
        <button class="admin-sidebar-logout admin-menu-toggle" type="button" :disabled="logoutPending" @click="logout">
          <LogOut :size="17" aria-hidden="true" /> {{ logoutPending ? '正在退出…' : '退出系统' }}
        </button>
      </div>
    </aside>
    <div class="admin-main-container">
      <header class="admin-navbar">
        <nav class="admin-breadcrumb" aria-label="当前位置">
          <RouterLink :to="{ name: 'admin-home' }">工作台首页</RouterLink>
          <template v-if="selectedGroup && selectedPage">
            <ChevronRight :size="14" aria-hidden="true" /><span>{{ selectedGroup.label }}</span>
            <ChevronRight :size="14" aria-hidden="true" />
            <RouterLink v-if="isKnowledgeDetail" :to="{ name: selectedPage.name }">{{ selectedPage.label }}</RouterLink>
            <span v-else aria-current="page">{{ selectedPage.label }}</span>
          </template>
          <template v-if="isKnowledgeDetail">
            <ChevronRight :size="14" aria-hidden="true" />
            <span aria-current="page">{{ route.name === 'admin-document' ? '版本详情' : '导入预览' }}</span>
          </template>
        </nav>
        <div class="admin-profile"><span>{{ adminSession?.displayName }}</span><small v-if="roleNames">{{ roleNames }}</small>
          <RouterLink class="admin-text-link" :to="{ name: 'admin-password' }">修改密码</RouterLink>
        </div>
      </header>
      <main ref="content" class="admin-content" aria-label="管理端内容" tabindex="0">
        <div class="admin-page">
          <p v-if="logoutError" class="admin-form-error" role="alert">退出失败，请重试。</p>
          <RouterView v-slot="{ Component }">
            <div :key="String(route.name)" class="admin-page-content"><component :is="Component" /></div>
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>
