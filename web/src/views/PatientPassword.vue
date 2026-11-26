<script setup lang="ts">
import AppShell from '../components/AppShell.vue'
import PasswordEditor from '../components/PasswordEditor.vue'
</script>

<template>
  <AppShell>
    <div class="page-wrap account-password">
      <section class="account-card" aria-labelledby="patient-password-title">
        <h1 id="patient-password-title">修改密码</h1>
        <PasswordEditor />
        <RouterLink class="button-secondary" :to="{ name: 'home' }">返回首页</RouterLink>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.account-password { padding-top: clamp(24px, 7vw, 80px); }
.account-card { display: grid; gap: 16px; margin-inline: auto; max-width: 440px; background: var(--paper); border: 1px solid var(--line); border-radius: 10px; padding: 24px; }
.account-card h1 { margin: 0; font-size: 26px; }
</style>
