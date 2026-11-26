<script setup lang="ts">
import { ref } from 'vue'
import { adminHttpStatus, agentSettingsError, changeAdminPassword } from '../api/admin'
import { changePatientPassword, requireLogin } from '../api/auth'
import { errorMessage } from '../api/http'

const props = defineProps<{ admin?: boolean; temporary?: boolean }>()
const current = ref('')
const next = ref('')
const confirmation = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  error.value = ''
  if (Array.from(next.value).length < 12 || new TextEncoder().encode(next.value).length > 72) {
    error.value = '新密码至少 12 个字符，最多 72 个 UTF-8 字节。'
    return
  }
  if (next.value !== confirmation.value) {
    error.value = '两次输入的新密码不一致。'
    return
  }
  busy.value = true
  try {
    if (props.admin) await changeAdminPassword(current.value, next.value)
    else await changePatientPassword(current.value, next.value)
  } catch (failure) {
    if (adminHttpStatus(failure) === 401) {
      if (props.admin) window.location.replace('/admin/login?reason=expired')
      else requireLogin()
    } else {
      error.value = props.admin ? agentSettingsError(failure) : errorMessage(failure)
    }
  } finally {
    current.value = ''
    busy.value = false
  }
}
</script>

<template>
  <form class="password-editor" @submit.prevent="submit">
    <p>保存后，该账号的现有登录会话将失效。请使用新密码重新登录。</p>
    <label>{{ temporary ? '临时密码' : '当前密码' }}
      <input v-model="current" :class="{ 'admin-input': admin }" type="password" autocomplete="current-password" required maxlength="72" :disabled="busy">
    </label>
    <label>新密码
      <input v-model="next" :class="{ 'admin-input': admin }" type="password" autocomplete="new-password" required maxlength="72" :disabled="busy"
        aria-describedby="new-password-requirements">
    </label>
    <small id="new-password-requirements">至少 12 个字符，最多 72 个 UTF-8 字节。</small>
    <label>确认新密码
      <input v-model="confirmation" :class="{ 'admin-input': admin }" type="password" autocomplete="new-password" required maxlength="72" :disabled="busy">
    </label>
    <p v-if="error" class="password-error" role="alert">{{ error }}</p>
    <button type="submit" :class="admin ? 'admin-button admin-button--primary' : 'button-primary'" :disabled="busy">
      {{ busy ? '正在保存…' : '保存新密码' }}
    </button>
  </form>
</template>

<style scoped>
.password-editor { display: grid; gap: 13px; }
.password-editor p { margin: 0; }
.password-editor label { display: grid; gap: 5px; font-size: 14px; }
.password-editor input:not(.admin-input) { min-height: 42px; border: 1px solid var(--line-strong); border-radius: 6px; padding: 8px 10px; }
.password-editor small { color: var(--admin-muted, var(--ink-soft)); }
.password-error { color: #a4292e; }
</style>
