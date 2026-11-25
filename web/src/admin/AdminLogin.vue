<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowRight, Cross, ShieldCheck } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import { adminHttpStatus, adminLogin } from '../api/admin'
import './admin.css'

const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const working = ref(false)
const hint = computed(() => {
  if (route.query.reason === 'forbidden') return '当前无法使用管理端，请使用有权限的管理员账号登录。'
  if (route.query.reason === 'unavailable') return '暂时无法连接管理服务。请确认已启动本地后端后再登录。'
  return ''
})

async function submit() {
  if (working.value) return
  working.value = true
  error.value = ''
  try {
    await adminLogin(username.value.trim(), password.value)
  } catch (failure) {
    error.value = adminHttpStatus(failure) === 401
      ? '登录失败，请核对管理员账号和密码。'
      : '登录未完成，请确认管理服务可用后重试。'
    working.value = false
  }
}
</script>

<template>
  <main class="admin-surface admin-login">
    <div class="admin-login-intro">
      <div class="admin-brand"><Cross :size="22" aria-hidden="true" /> FreeMedi-Agent</div>
      <div class="admin-login-description">
        <ShieldCheck :size="36" aria-hidden="true" />
        <h1>智能导诊管理端</h1>
        <p>在工作台查看院内资料的发布版本、片段索引和患者引用状态。</p>
      </div>
      <RouterLink to="/">返回患者端 <ArrowRight :size="16" aria-hidden="true" /></RouterLink>
    </div>
    <div class="admin-login-form-area">
      <form class="admin-login-form" @submit.prevent="submit">
        <h2>管理员登录</h2>
        <p v-if="hint" class="admin-form-hint" role="status">{{ hint }}</p>
        <label for="admin-username">管理员账号</label>
        <input id="admin-username" v-model="username" class="admin-input" autocomplete="username" required maxlength="64" />
        <label for="admin-password">密码</label>
        <input id="admin-password" v-model="password" type="password" class="admin-input" autocomplete="current-password" required />
        <p v-if="error" class="admin-form-error" role="alert">{{ error }}</p>
        <button class="admin-button admin-button--primary" :disabled="working" type="submit">
          {{ working ? '正在登录…' : '登录管理端' }}
        </button>
      </form>
    </div>
  </main>
</template>
