<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { CircleHelp, Menu, Plus, X } from 'lucide-vue-next'
import { ref, useTemplateRef } from 'vue'
import { logout, patientSession } from '../api/auth'

const route = useRoute()
const menuOpen = ref(false)
const menuButton = useTemplateRef<HTMLButtonElement>('menuButton')
const loggingOut = ref(false)
const logoutError = ref('')

async function signOut() {
  loggingOut.value = true
  logoutError.value = ''
  try {
    await logout()
  } catch {
    logoutError.value = '退出失败，请重试。'
    loggingOut.value = false
  }
}

function closeMenuOnEscape() {
  if (!menuOpen.value) return
  menuOpen.value = false
  menuButton.value?.focus()
}

function isCurrentSection(path: string) {
  return route.path === path || (path !== '/' && route.path.startsWith(path + '/'))
}

const navItems = [
  { label: '首页', to: '/' },
  { label: '智能导诊', to: '/guide' },
  { label: '预约挂号', to: '/booking' },
  { label: '我的预约', to: '/appointments' },
]
</script>

<template>
  <div class="app-frame">
    <header class="site-header" @keydown.esc="closeMenuOnEscape">
      <div class="header-inner">
        <RouterLink class="brand" to="/" aria-label="FreeMedi-Agent 首页">
          <span class="brand-mark"><Plus :size="19" :stroke-width="3.4" aria-hidden="true" /></span>
          <span>FreeMedi<span class="brand-tail">-Agent</span></span>
        </RouterLink>

        <button
          ref="menuButton"
          class="mobile-menu"
          type="button"
          :aria-label="menuOpen ? '关闭导航' : '打开导航'"
          :aria-expanded="menuOpen"
          aria-controls="site-main-nav"
          @click="menuOpen = !menuOpen"
        >
          <X v-if="menuOpen" :size="20" aria-hidden="true" />
          <Menu v-else :size="20" aria-hidden="true" />
        </button>

        <nav id="site-main-nav" class="main-nav" :class="{ 'is-open': menuOpen }" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            class="nav-link"
            :class="{ active: isCurrentSection(item.to) }"
            :aria-current="route.path === item.to ? 'page' : isCurrentSection(item.to) ? 'location' : undefined"
            @click="menuOpen = false"
          >
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="header-tools">
          <span class="environment-label"><span class="environment-dot" />演示环境</span>
          <template v-if="patientSession">
            <span class="profile-name">{{ patientSession.displayName }}</span>
            <button class="profile-button" type="button" :disabled="loggingOut" @click="signOut">退出登录</button>
          </template>
          <RouterLink v-else-if="route.name !== 'login'" class="profile-button" to="/login">患者登录</RouterLink>
        </div>
      </div>
    </header>

    <main class="patient-page-content">
      <p v-if="logoutError" class="page-wrap" role="alert">{{ logoutError }}</p>
      <slot />
    </main>

    <footer class="site-footer">
      <div class="footer-inner">
        <span>FreeMedi-Agent</span>
        <span class="synthetic-note">演示系统 · 医院、医生、号源及预约数据均为合成数据</span>
        <a href="#service-note" class="help-link"><CircleHelp :size="15" />服务说明</a>
      </div>
    </footer>
  </div>
</template>
