<script setup lang="ts">
import { ArrowRight, BookOpen } from 'lucide-vue-next'
import { adminSession } from '../api/admin'
</script>

<template>
  <section class="admin-welcome">
    <div>
      <h1>欢迎，{{ adminSession?.displayName }}</h1>
      <p>FreeMedi-Agent 帮助患者明确就诊方向、完成预约，并查询院内就诊资料。你可以在这里检查患者问答使用的资料版本。</p>
    </div>
  </section>
  <section class="admin-home-module" aria-labelledby="admin-home-knowledge">
    <span class="admin-module-icon"><BookOpen :size="24" aria-hidden="true" /></span>
    <div>
      <h2 id="admin-home-knowledge">院内资料</h2>
      <p>查看预约规则、就诊须知和医生资料，核对发布版本与片段状态。</p>
    </div>
    <RouterLink class="admin-button admin-button--primary" :to="{ name: 'admin-knowledge' }">
      查看院内资料 <ArrowRight :size="17" aria-hidden="true" />
    </RouterLink>
  </section>
</template>
