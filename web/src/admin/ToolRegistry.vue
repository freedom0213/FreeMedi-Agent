<script setup lang="ts">
import { ref, shallowRef, watchEffect } from 'vue'
import { ChevronDown, ShieldCheck } from 'lucide-vue-next'
import { adminHttpStatus, agentSettingsError, readRegisteredTools, updateRegisteredTool, type RegisteredTool } from '../api/admin'
import { toolNames } from './toolLabels'

const tools = shallowRef<RegisteredTool[]>([])
const state = ref<'loading' | 'ready' | 'error' | 'session'>('loading')
const attempt = ref(0)
const busy = ref(false)
const problem = ref('')
const feedback = ref('')
const pending = ref<string>()
const edit = ref<{ name: string; inherit: boolean; timeoutMs: number }>()
const impacts: Record<string, string> = {
  findAvailableSlots: '智能导诊将暂时无法查询新号源。患者仍可使用普通挂号，也可确认现有的有效预约单。',
  getAppointmentRules: '智能导诊将暂时无法调用这项系统规则查询。',
  searchHospitalKnowledge: '智能导诊和预约后的就诊准备暂时无法检索院内资料。',
}

function editTimeout(tool: RegisteredTool) {
  edit.value = { name: tool.name, inherit: tool.timeoutMs == null, timeoutMs: tool.timeoutMs ?? tool.defaultTimeoutMs ?? 0 }
}

async function save(tool: RegisteredTool, enabled: boolean, timeoutMs: number | null, message: string) {
  if (busy.value || tool.revision === undefined) return
  busy.value = true
  problem.value = ''
  feedback.value = ''
  try {
    tools.value = await updateRegisteredTool(tool.name, enabled, timeoutMs, tool.revision)
    pending.value = undefined
    edit.value = undefined
    feedback.value = `${toolNames[tool.name] ?? tool.name}${message}，下一轮请求生效。`
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    busy.value = false
  }
}

function saveTimeout(tool: RegisteredTool) {
  if (!edit.value) return
  void save(tool, tool.enabled !== false, edit.value.inherit ? null : edit.value.timeoutMs, '超时已保存')
}

watchEffect((invalidate) => {
  void attempt.value
  const request = new AbortController()
  invalidate(() => request.abort())
  state.value = 'loading'
  readRegisteredTools(request.signal).then((result) => {
    if (request.signal.aborted) return
    tools.value = result
    state.value = 'ready'
  }).catch((error) => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    state.value = status === 401 || status === 403 ? 'session' : 'error'
  })
})
</script>

<template>
  <h1 class="admin-page-title">工具注册表</h1>
  <div class="admin-tool-boundary">
    <ShieldCheck :size="21" aria-hidden="true" />
    <div>
      <strong>创建预约需要患者确认</strong>
      <p>患者在预约确认单上点击「确认预约」后，后端才会创建并核验预约。</p>
    </div>
  </div>
  <p v-if="busy" class="admin-form-hint tool-feedback" role="status">正在保存…</p>
  <div v-else-if="problem" class="admin-form-error tool-feedback" role="alert">
    {{ problem }}
    <button class="admin-button" type="button" @click="attempt++; problem = ''; edit = undefined; pending = undefined">重新加载</button>
  </div>
  <p v-else-if="feedback" class="admin-form-hint tool-feedback" role="status">{{ feedback }}</p>
  <section class="admin-panel admin-tool-registry" aria-labelledby="registered-tools">
    <header class="admin-panel-toolbar"><h2 id="registered-tools">模型可调用的工具</h2></header>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取工具定义…</p>
    <div v-else-if="state === 'error'" class="admin-empty" role="alert">
      <p>工具定义加载失败。</p>
      <button class="admin-button" type="button" @click="attempt++">重新加载</button>
    </div>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      <p>管理员会话已失效。</p>
      <RouterLink class="admin-button" :to="{ name: 'admin-login' }">重新登录</RouterLink>
    </div>
    <p v-else-if="!tools.length" class="admin-empty">当前暂无已注册工具。</p>
    <details v-for="(tool, index) in tools" v-else :key="tool.name" :open="index === 0">
      <summary>
        <span><strong>{{ toolNames[tool.name] ?? tool.name }}</strong><code>{{ tool.name }}</code>
          <span v-if="tool.enabled !== undefined" class="tool-state" :class="{ 'tool-state--paused': !tool.enabled }">{{ tool.enabled ? '已启用' : '已停用' }}</span>
        </span>
        <ChevronDown :size="17" aria-hidden="true" />
      </summary>
      <div class="admin-tool-detail">
        <p class="admin-tool-description">{{ tool.description }}</p>
        <div v-if="tool.revision !== undefined" class="tool-settings">
          <p>单次超时：{{ tool.timeoutMs == null ? '继承默认 · ' + tool.defaultTimeoutMs : tool.timeoutMs }} 毫秒</p>
          <p v-if="tool.updatedAt" class="admin-muted">由 {{ tool.updatedBy }} 更新于 {{ new Date(tool.updatedAt).toLocaleString('zh-CN') }}</p>
          <form v-if="edit?.name === tool.name" class="tool-timeout" @submit.prevent="saveTimeout(tool)">
            <label><input v-model="edit.inherit" type="checkbox" :disabled="busy"> 继承默认超时</label>
            <label v-if="!edit.inherit">单次超时（毫秒）
              <input v-model.number="edit.timeoutMs" class="admin-input" type="number" required min="100" max="120000" :disabled="busy">
            </label>
            <div class="tool-actions">
              <button class="admin-button admin-button--primary" :disabled="busy">保存超时</button>
              <button class="admin-button" type="button" :disabled="busy" @click="edit = undefined">取消</button>
            </div>
          </form>
          <div v-else-if="pending === tool.name">
            <p>{{ impacts[tool.name] }}</p>
            <div class="tool-actions">
              <button class="admin-button admin-button--primary" type="button" :disabled="busy"
                @click="save(tool, false, tool.timeoutMs ?? null, '已停用')">确认停用</button>
              <button class="admin-button" type="button" :disabled="busy" @click="pending = undefined">取消</button>
            </div>
          </div>
          <div v-else class="tool-actions">
            <button class="admin-button" type="button" :disabled="busy" @click="pending = undefined; editTimeout(tool)">编辑超时</button>
            <button v-if="tool.enabled" class="admin-button" type="button" :disabled="busy"
              @click="edit = undefined; pending = tool.name">停用</button>
            <button v-else class="admin-button" type="button" :disabled="busy"
              @click="save(tool, true, tool.timeoutMs ?? null, '已启用')">启用</button>
          </div>
        </div>
        <table v-if="tool.parameters.length" class="admin-tool-parameters" :aria-label="tool.name + ' 参数'">
          <thead><tr><th scope="col">参数</th><th scope="col">类型</th><th scope="col">必填</th><th scope="col">说明</th></tr></thead>
          <tbody>
            <tr v-for="parameter in tool.parameters" :key="parameter.name">
              <th scope="row"><code>{{ parameter.name }}</code></th>
              <td><code>{{ parameter.type.toLowerCase() }}</code></td>
              <td><span v-if="parameter.required" class="admin-tag" data-state="PENDING">必填</span><span v-else>可选</span></td>
              <td>{{ parameter.description }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="admin-muted admin-tool-no-params">此工具无需参数。</p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.tool-feedback { margin-bottom: 16px; }
.tool-state { color: #215d40; font-size: 13px; }
.tool-state--paused { color: #8c4526; }
.tool-settings { margin-bottom: 14px; padding-bottom: 14px; border-bottom: 1px solid var(--admin-border); }
.tool-settings p { margin: 0 0 8px; }
.tool-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.tool-timeout { display: grid; gap: 8px; max-width: 280px; }
</style>
