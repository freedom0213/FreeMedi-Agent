<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'
import { ChevronDown, CircleHelp, HeartPulse, Menu } from 'lucide-vue-next'
import { ref } from 'vue'

const route = useRoute()
const menuOpen = ref(false)
const navItems = [
  { label: '首页', to: '/', ready: true },
  { label: '智能导诊', to: '/guide', ready: true },
  { label: '预约挂号', to: '/booking', ready: true },
  { label: '我的预约', to: '/appointments', ready: true },
]
</script>

<template>
  <div class="app-frame">
    <header class="site-header">
      <div class="header-inner">
        <RouterLink class="brand" to="/" aria-label="FreeMedi-Agent 首页">
          <span class="brand-mark"><HeartPulse :size="19" :stroke-width="2.2" /></span>
          <span>FreeMedi-Agent</span>
        </RouterLink>

        <button class="mobile-menu" type="button" aria-label="打开导航" @click="menuOpen = !menuOpen">
          <Menu :size="20" />
        </button>

        <nav class="main-nav" :class="{ 'is-open': menuOpen }" aria-label="主导航">
          <RouterLink
            v-for="item in navItems"
            :key="item.to"
            :to="item.to"
            :class="[
              'nav-link',
              { active: route.path === item.to || (item.to === '/guide' && route.path === '/guide') },
              { 'nav-coming': !item.ready },
            ]"
            @click="menuOpen = false"
          >
            {{ item.label }}
            <span v-if="!item.ready" class="soon-mark">后续</span>
          </RouterLink>
        </nav>

        <div class="header-tools">
          <span class="environment-label"><span class="environment-dot" />演示环境</span>
          <div class="profile-button" aria-label="当前演示患者">
            <span class="profile-avatar">演</span>
            <span class="profile-name">演示患者</span>
            <ChevronDown :size="14" />
          </div>
        </div>
      </div>
    </header>

    <main><slot /></main>

    <footer class="site-footer">
      <div class="footer-inner">
        <span>FreeMedi-Agent</span>
        <span class="synthetic-note">演示系统 · 医院、医生、号源及预约数据均为合成数据</span>
        <a href="#service-note" class="help-link"><CircleHelp :size="15" />服务说明</a>
      </div>
    </footer>
  </div>
</template>
