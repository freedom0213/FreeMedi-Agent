<script setup lang="ts">
import { computed, ref } from 'vue'
import { adminHas, adminRoleLabels, adminSession, agentSettingsError, readCatalogOptions, readStaff, saveStaff,
  readStaffSecurityHistory, resetStaffPassword, setStaffActive, type AdminGrant, type AdminRole,
  type StaffMember, type StaffSecurityEvent, type TemporaryPassword } from '../api/admin'
import { formatHospitalDateTime } from '../api/portal'
import { useKnowledgeRead } from './useKnowledgeRead'

const staff = useKnowledgeRead(readStaff)
const departments = useKnowledgeRead(signal => readCatalogOptions('departments', signal))
const roles: AdminRole[] = ['SCHEDULER', 'TRIAGE', 'KNOWLEDGE']
if (adminHas('SUPER_ADMIN')) roles.push('SYSTEM_ADMIN', 'VISIT_SUPERVISOR')
const editor = ref<{ id?: number; username: string; displayName: string; password: string; grants: AdminGrant[] }>()
const role = ref<AdminRole>('SCHEDULER')
const department = ref<number | ''>('')
const busy = ref(false)
const notice = ref('')
const error = ref('')
const scoped = computed(() => role.value === 'SCHEDULER' || role.value === 'TRIAGE' || role.value === 'VISIT_SUPERVISOR')
const change = ref<{ account: StaffMember; action: 'disable' | 'enable' | 'reset' }>()
const temporary = ref<{ account: StaffMember; secret: TemporaryPassword }>()
const copyMessage = ref('')
const viewing = ref<StaffMember>()
const before = ref<number>()
const history = useKnowledgeRead(signal => viewing.value
  ? readStaffSecurityHistory(viewing.value.id, before.value, signal) : Promise.resolve(undefined))
const historyLabels: Record<StaffSecurityEvent['action'], string> = {
  DISABLED: '停用账号', ENABLED: '恢复账号', PASSWORD_RESET: '重置密码', PASSWORD_CHANGED: '修改密码',
}
const actionLabels = { disable: '停用账号', enable: '恢复账号', reset: '重置密码' }

function canEdit(row: StaffMember) {
  return String(row.id) !== adminSession.value?.adminId && !row.grants.some(grant =>
    grant.role === 'SUPER_ADMIN' || (['SYSTEM_ADMIN', 'VISIT_SUPERVISOR'].includes(grant.role) && !adminHas('SUPER_ADMIN')))
}

function edit(row?: StaffMember) {
  clearActions()
  editor.value = { id: row?.id, username: row?.username ?? '', displayName: row?.displayName ?? '',
    password: '', grants: row?.grants.map(grant => ({ ...grant })) ?? [] }
  error.value = ''
  notice.value = ''
}

function clearActions() {
  change.value = undefined
  temporary.value = undefined
  copyMessage.value = ''
  viewing.value = undefined
  error.value = ''
  notice.value = ''
}

function beginChange(account: StaffMember, action: 'disable' | 'enable' | 'reset') {
  clearActions()
  editor.value = undefined
  change.value = { account, action }
}

function openHistory(account: StaffMember) {
  clearActions()
  editor.value = undefined
  before.value = undefined
  viewing.value = account
}

async function applyChange() {
  const operation = change.value
  if (!operation || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const { account, action } = operation
    if (action === 'reset') {
      const secret = await resetStaffPassword(account.id)
      temporary.value = { account, secret }
    } else {
      await setStaffActive(account.id, action === 'enable')
      notice.value = action === 'enable' ? '已恢复账号，员工可以重新登录。' : '账号已停用，已有会话已失效。'
    }
    change.value = undefined
    staff.reload()
  } catch (failure) {
    error.value = agentSettingsError(failure)
  } finally {
    busy.value = false
  }
}

async function copyTemporaryPassword() {
  if (!temporary.value) return
  try {
    await navigator.clipboard.writeText(temporary.value.secret.password)
    copyMessage.value = '临时密码已复制。'
  } catch {
    copyMessage.value = '复制失败，可直接选中临时密码复制。'
  }
}

function name(grant: AdminGrant) {
  const departmentName = departments.data.value?.find(item => item.id === grant.departmentId)?.name
  return adminRoleLabels[grant.role] + (grant.departmentId == null ? '' : ' · ' + (departmentName ?? grant.departmentId))
}

function addGrant() {
  if (!editor.value || (scoped.value && !department.value)) return
  const id = scoped.value ? Number(department.value) : null
  if (!editor.value.grants.some(grant => grant.role === role.value && grant.departmentId === id)) {
    editor.value.grants.push({ role: role.value, departmentId: id })
  }
}

async function save() {
  if (!editor.value || (!editor.value.id && !editor.value.grants.length) || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await saveStaff(editor.value.id, editor.value)
    editor.value = undefined
    notice.value = '岗位已保存，新权限从下一次请求生效。'
    staff.reload()
  } catch (failure) {
    error.value = agentSettingsError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <h1 class="admin-page-title">岗位账号</h1>
  <section class="admin-panel" aria-label="岗位账号">
    <header class="admin-panel-toolbar">
      <div><h2>管理端人员</h2><p class="admin-muted">为排班、分诊岗位指定科室。超级管理员由部署时的账号配置建立。</p></div>
      <button type="button" class="admin-button admin-button--primary" @click="edit()">新增账号</button>
    </header>
    <p v-if="notice" class="admin-form-hint catalog-message" role="status">{{ notice }}</p>
    <p v-if="error" class="admin-form-error catalog-message" role="alert">{{ error }}</p>
    <section v-if="change" class="catalog-editor" aria-label="确认账号操作">
      <h2>{{ actionLabels[change.action] }} · {{ change.account.displayName }}</h2>
      <p v-if="change.action === 'disable'">停用后，账号无法登录，已有登录会话将失效。</p>
      <p v-else-if="change.action === 'enable'">恢复后，员工可重新登录并使用原岗位与科室权限。</p>
      <p v-else>系统将生成有效期为 24 小时的临时密码，并使原密码和已有会话失效。</p>
      <div class="catalog-actions">
        <button type="button" class="admin-button admin-button--primary" :disabled="busy" @click="applyChange">
          确认{{ actionLabels[change.action] }}
        </button>
        <button type="button" class="admin-button" :disabled="busy" @click="change = undefined">取消</button>
      </div>
    </section>
    <section v-if="temporary" class="catalog-editor" aria-label="临时密码结果">
      <h2>临时密码 · {{ temporary.account.displayName }}</h2>
      <p>此处只显示一次。请通过院内可信渠道交给员工，有效至 {{ formatHospitalDateTime(temporary.secret.expiresAt) }}。</p>
      <p><code class="temporary-secret">{{ temporary.secret.password }}</code></p>
      <div class="catalog-actions">
        <button type="button" class="admin-button" @click="copyTemporaryPassword">复制临时密码</button>
        <button type="button" class="admin-button" @click="temporary = undefined; copyMessage = ''">关闭</button>
        <small v-if="copyMessage" role="status">{{ copyMessage }}</small>
      </div>
    </section>
    <section v-if="viewing" class="catalog-editor" aria-label="账号操作记录">
      <h2>账号操作记录 · {{ viewing.displayName }}</h2>
      <p v-if="history.state.value === 'loading'" role="status">正在读取操作记录…</p>
      <div v-else-if="history.state.value !== 'ready'" role="alert">
        操作记录加载失败。<button type="button" class="admin-button" @click="history.reload">重试</button>
      </div>
      <ul v-else-if="history.data.value?.events.length" class="staff-history">
        <li v-for="event in history.data.value.events" :key="event.id">
          <time :datetime="event.occurredAt">{{ formatHospitalDateTime(event.occurredAt) }}</time>
          · {{ event.operator }} · {{ historyLabels[event.action] }}
        </li>
      </ul>
      <p v-else class="admin-muted">暂无账号操作记录。</p>
      <div class="catalog-actions">
        <button v-if="history.state.value === 'ready' && history.data.value?.nextBefore" type="button" class="admin-button"
          @click="before = history.data.value?.nextBefore ?? undefined">查看更早记录</button>
        <button v-if="before" type="button" class="admin-button" @click="before = undefined">返回最新</button>
        <button type="button" class="admin-button" @click="viewing = undefined">关闭记录</button>
      </div>
    </section>
    <form v-if="editor" class="catalog-editor" @submit.prevent="save">
      <h2>{{ editor.id ? '调整岗位 · ' + editor.displayName : '新增账号' }}</h2>
      <fieldset v-if="!editor.id" class="catalog-fields" :disabled="busy">
        <label>登录账号<input v-model.trim="editor.username" class="admin-input" autocomplete="off" required maxlength="64" pattern="[A-Za-z0-9._\-]+"></label>
        <label>姓名<input v-model.trim="editor.displayName" class="admin-input" required maxlength="100"></label>
        <label>初始密码<input v-model="editor.password" class="admin-input" type="password" autocomplete="new-password" required minlength="10" maxlength="72"></label>
      </fieldset>
      <fieldset class="catalog-fields" :disabled="busy">
        <label>岗位<select v-model="role" class="admin-input">
          <option v-for="value in roles" :key="value" :value="value">{{ adminRoleLabels[value] }}</option>
        </select></label>
        <label v-if="scoped">授权科室<select v-model="department" class="admin-input">
          <option value="">选择科室</option>
          <option v-for="item in departments.data.value" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select></label>
        <button class="admin-button" type="button" :disabled="scoped && !department" @click="addGrant">加入授权</button>
      </fieldset>
      <p v-if="!editor.grants.length" class="admin-muted">{{ editor.id ? '保存后将撤销该账号的全部岗位权限。' : '请加入至少一项授权。' }}</p>
      <ul v-else>
        <li v-for="(grant, index) in editor.grants" :key="grant.role + ':' + grant.departmentId">
          {{ name(grant) }} <button class="admin-button" type="button" :disabled="busy" @click="editor.grants.splice(index, 1)">移除</button>
        </li>
      </ul>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" :disabled="busy || (!editor.id && !editor.grants.length)">保存账号权限</button>
        <button class="admin-button" type="button" :disabled="busy" @click="editor = undefined">取消</button>
      </div>
    </form>
    <p v-if="staff.state.value === 'loading'" class="admin-empty" role="status">正在读取岗位账号…</p>
    <div v-else-if="staff.state.value !== 'ready'" class="admin-empty" role="alert">
      岗位账号加载失败。<button type="button" class="admin-button" @click="staff.reload">重试</button>
    </div>
    <div v-else class="admin-table-scroll">
      <table class="admin-table" aria-label="管理端人员">
        <thead><tr><th scope="col">姓名</th><th scope="col">账号</th><th scope="col">岗位与科室</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead>
        <tbody><tr v-for="row in staff.data.value" :key="row.id">
          <th scope="row">{{ row.displayName }}</th><td>{{ row.username }}</td>
          <td>{{ row.grants.map(name).join('、') || '待授权' }}</td>
          <td>
            <span class="catalog-status" :data-active="row.status === 'ACTIVE'">{{ row.status === 'DISABLED' ? '已停用' : '启用' }}</span>
            <small v-if="row.temporaryPasswordExpiresAt && row.status !== 'DISABLED'">
              {{ Date.parse(row.temporaryPasswordExpiresAt) > Date.now() ? '待改密' : '临时密码已过期' }}
            </small>
          </td>
          <td><div class="catalog-actions">
            <template v-if="canEdit(row)">
              <button type="button" class="admin-button" :disabled="busy" @click="edit(row)">调整岗位</button>
              <button type="button" class="admin-button" :disabled="busy"
                @click="beginChange(row, row.status === 'DISABLED' ? 'enable' : 'disable')">{{ row.status === 'DISABLED' ? '恢复' : '停用' }}</button>
              <button v-if="row.status !== 'DISABLED'" type="button" class="admin-button" :disabled="busy" @click="beginChange(row, 'reset')">重置密码</button>
            </template>
            <button v-if="canEdit(row) || String(row.id) === adminSession?.adminId" type="button" class="admin-button" :disabled="busy"
              @click="openHistory(row)">操作记录</button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.temporary-secret { display: inline-block; max-width: 100%; overflow-wrap: anywhere; color: var(--admin-ink); }
.staff-history { padding-inline-start: 18px; }
.staff-history li + li { margin-top: 7px; }
</style>
