<script setup lang="ts">
import { ref } from 'vue'
import { adminLogout, adminSession } from '../api/admin'
import PasswordEditor from '../components/PasswordEditor.vue'
import './admin.css'

const signingOut = ref(false)
const failed = ref(false)
async function signOut() {
  signingOut.value = true
  failed.value = false
  try {
    await adminLogout()
  } catch {
    failed.value = true
    signingOut.value = false
  }
}
</script>

<template>
  <main class="admin-surface account-password" aria-labelledby="admin-password-title">
    <section class="admin-panel account-card">
      <h1 id="admin-password-title">{{ adminSession?.mustChangePassword ? '设置新密码' : '修改密码' }}</h1>
      <p v-if="adminSession?.mustChangePassword" class="admin-form-hint">你正在使用临时密码。设置新密码后即可进入工作台。</p>
      <PasswordEditor admin :temporary="adminSession?.mustChangePassword" />
      <div v-if="adminSession?.mustChangePassword">
        <button class="admin-button" type="button" :disabled="signingOut" @click="signOut">退出登录</button>
        <p v-if="failed" class="admin-form-error" role="alert">退出失败，请重试。</p>
      </div>
      <RouterLink v-else class="admin-text-link" :to="{ name: 'admin-home' }">返回工作台</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.account-password { display: grid; place-items: start center; min-height: 100vh; padding: clamp(20px, 8vw, 80px) 16px; }
.account-card { display: grid; gap: 16px; width: min(100%, 440px); padding: 24px; }
.account-card h1, .account-card p { margin: 0; }
</style>
