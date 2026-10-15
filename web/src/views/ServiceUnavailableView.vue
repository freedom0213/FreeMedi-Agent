<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { ArrowLeft, CalendarDays } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'

defineProps<{ title: string }>()
</script>

<template>
  <AppShell>
    <section class="not-ready page-wrap">
      <div class="not-ready-mark"><CalendarDays :size="22" /></div>
      <p class="eyebrow"><span class="eyebrow-line" />功能建设中</p>
      <h1>{{ title }}</h1>
      <p class="not-ready-copy">当前版本尚未开放此服务。你可以先体验智能导诊与预约编排。</p>
      <RouterLink class="button-primary not-ready-link" to="/guide">前往智能导诊 <ArrowLeft :size="15" /></RouterLink>
    </section>
  </AppShell>
</template>
