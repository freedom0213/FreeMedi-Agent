<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import axios from 'axios'
import AppShell from '../components/AppShell.vue'
import { login, loginTarget } from '../api/auth'
import { errorMessage } from '../api/http'

const route = useRoute()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await login(username.value.trim(), password.value, loginTarget(route.query.next))
  } catch (failure) {
    error.value = axios.isAxiosError(failure) && failure.response?.status === 401
      ? '账号或密码不正确。'
      : errorMessage(failure)
    busy.value = false
  } finally {
    password.value = ''
  }
}
</script>

<template>
  <AppShell v-slot="{ hospital }">
    <div class="page-wrap login-page">
      <form class="login-card" @submit.prevent="submit">
        <h1>患者登录</h1>
        <p>登录后可预约挂号并查看本人的就诊记录。</p>
        <p v-if="route.query.reason === 'password-changed'" role="status">密码已修改，请使用新密码登录。</p>
        <label>账号
          <input v-model="username" name="username" autocomplete="username" required maxlength="64" :disabled="busy" />
        </label>
        <label>密码
          <input v-model="password" name="password" type="password" autocomplete="current-password" required maxlength="72" :disabled="busy" />
        </label>
        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <button class="button-primary" type="submit" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button>
        <details class="forgot-password">
          <summary>忘记密码</summary>
          <p>请联系院方账号服务人员，完成身份核验后办理密码重置。</p>
          <p v-if="hospital?.contact">院方联系方式 {{ hospital.contact }}</p>
        </details>
      </form>
    </div>
  </AppShell>
</template>

<style scoped>
.login-page { padding-top: clamp(32px, 7vw, 88px); }
.login-card { display: grid; gap: 16px; max-width: 416px; margin-inline: auto; padding: 28px; background: var(--paper); border: 1px solid var(--line); border-radius: 10px; }
h1 { margin: 0; font-size: 26px; }
p { margin: 0; font-size: 14px; color: var(--ink-soft); }
label { display: grid; gap: 7px; font-size: 13px; }
input { min-height: 42px; width: 100%; padding: 8px 10px; border: 1px solid var(--line-strong); border-radius: 6px; color: var(--ink); }
.login-error { color: var(--red); }
.forgot-password summary { cursor: pointer; color: var(--ink); font-size: 14px; }
.forgot-password p { margin-top: 7px; }
</style>
