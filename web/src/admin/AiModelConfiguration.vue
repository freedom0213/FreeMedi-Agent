<script setup lang="ts">
import { computed, ref, shallowRef, watchEffect } from 'vue'
import {
  activateAgentModel, adminHttpStatus, agentSettingsError, readAgentModelConfiguration, saveAgentModel,
  testAgentModel, updateAgentTimeouts, type AgentModelConfiguration, type AgentTimeouts, type ModelDraft,
  type ModelRevision,
} from '../api/admin'

const configuration = shallowRef<AgentModelConfiguration>()
const state = ref<'loading' | 'ready' | 'error' | 'session'>('loading')
const attempt = ref(0)
const modeLabels: Record<AgentModelConfiguration['mode'], string> = {
  DEEPSEEK: 'DeepSeek',
  COMPATIBLE: '兼容聊天接口',
  DEMO: '本地演示规则',
  UNKNOWN: '未识别的决策实现',
}
const reasonLabels: Record<string, string> = {
  MODEL_TIMEOUT: '模型超时',
  MODEL_TRANSIENT: '模型服务暂时不可用',
  TOOL_TIMEOUT: '工具超时',
  TOOL_TRANSIENT: '工具服务暂时不可用',
  KNOWLEDGE_INDEX_UNAVAILABLE: '院内资料索引暂时不可用',
}
const seconds = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 })
const duration = (milliseconds: number) => seconds.format(milliseconds / 1000) + ' 秒'
const busy = ref(false)
const feedback = ref('')
const problem = ref('')
const draft = ref<ModelDraft>()
const policyDraft = ref<AgentTimeouts>()
const confirmation = ref<{ id: number | null; label: string }>()
const historyOpen = ref(false)
const latest = computed(() => {
  const seen = new Set<string>()
  return (configuration.value?.models ?? []).filter(model => {
    if (seen.has(model.profileId)) return false
    seen.add(model.profileId)
    return true
  })
})
const history = computed(() => (configuration.value?.models ?? [])
  .filter(model => !latest.value.some(current => current.id === model.id)))
const date = (value: string) => new Date(value).toLocaleString('zh-CN')
const timeoutFields = [
  { key: 'modelTimeoutMs', label: '单次模型调用' },
  { key: 'toolTimeoutMs', label: '默认工具调用' },
  { key: 'requestTimeoutMs', label: '整轮请求时限' },
] as const
const probeLabels: Record<string, string> = {
  PASSED: '测试通过', AUTH_REJECTED: '鉴权失败，请检查 API Key',
  QUOTA_OR_LIMIT: '服务额度不足或请求受限', PROVIDER_REJECTED: '供应商拒绝请求，请检查模型名和地址',
  TIMEOUT: '模型请求超时', INVALID_DECISION: '模型未返回规定的结构化回复',
  INVALID_TOOL_CALL: '模型未返回正确的工具调用', REQUEST_FAILED: '调用失败，请检查接口地址与服务状态',
}

function editModel(model?: ModelRevision) {
  draft.value = {
    profileId: model?.profileId ?? null, expectedId: model?.id ?? 0,
    label: model?.label ?? '', baseUrl: model?.baseUrl ?? configuration.value?.environmentBaseUrl ?? '',
    modelName: model?.modelName ?? '', apiKey: '',
  }
}

function editPolicy() {
  const policy = configuration.value?.callPolicy
  if (policy) policyDraft.value = {
    modelTimeoutMs: policy.modelTimeoutMs, toolTimeoutMs: policy.toolTimeoutMs, requestTimeoutMs: policy.requestTimeoutMs,
  }
}

async function perform(action: () => Promise<AgentModelConfiguration>, onSuccess: (value: AgentModelConfiguration) => string) {
  if (busy.value) return
  busy.value = true
  feedback.value = ''
  problem.value = ''
  try {
    const result = await action()
    configuration.value = result
    feedback.value = onSuccess(result)
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    busy.value = false
  }
}

function save() {
  if (!draft.value) return
  const input = { ...draft.value }
  void perform(() => saveAgentModel(input), () => {
    draft.value = undefined
    return '已保存新版本。测试通过后即可启用。'
  })
}

function verify(model: ModelRevision) {
  void perform(() => testAgentModel(model.id), result => {
    const code = result.models?.find(item => item.id === model.id)?.probeCode
    return code ? `${model.label} v${model.revision}：${probeLabels[code] ?? '测试未通过'}。` : '已完成测试。'
  })
}

function activate() {
  if (!configuration.value || !confirmation.value) return
  const { id } = confirmation.value
  const revision = configuration.value.revision ?? 0
  void perform(() => activateAgentModel(id, revision), () => {
    confirmation.value = undefined
    return '已切换模型，下一轮请求将使用此配置。'
  })
}

function savePolicy() {
  if (!configuration.value || !policyDraft.value) return
  const values = { ...policyDraft.value }
  const revision = configuration.value.revision ?? 0
  void perform(() => updateAgentTimeouts(values, revision), () => {
    policyDraft.value = undefined
    return '超时设置已保存，下一轮请求生效。'
  })
}

watchEffect((invalidate) => {
  void attempt.value
  const request = new AbortController()
  invalidate(() => request.abort())
  state.value = 'loading'
  readAgentModelConfiguration(request.signal).then((result) => {
    if (request.signal.aborted) return
    configuration.value = result
    state.value = 'ready'
  }).catch((error) => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    state.value = status === 401 || status === 403 ? 'session' : 'error'
  })
})
</script>

<template>
  <h1 class="admin-page-title">AI 模型配置</h1>
  <p v-if="state === 'loading'" class="admin-panel admin-empty" role="status">正在读取模型配置…</p>
  <div v-else-if="state === 'error'" class="admin-panel admin-empty" role="alert">
    <p>模型配置加载失败。</p>
    <button class="admin-button" type="button" @click="attempt++">重新加载</button>
  </div>
  <div v-else-if="state === 'session'" class="admin-panel admin-empty" role="alert">
    <p>管理员会话已失效。</p>
    <RouterLink class="admin-button" :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
  </div>
  <div v-else-if="configuration" class="model-settings">
    <section class="admin-panel" aria-labelledby="admin-active-model">
      <header class="admin-panel-toolbar"><h2 id="admin-active-model">当前导诊决策模型</h2></header>
      <dl class="model-fields">
        <div><dt>运行模式</dt><dd>{{ modeLabels[configuration.mode] }}</dd></div>
        <div v-if="configuration.provider">
          <dt>模型名称</dt><dd><code>{{ configuration.provider?.modelName ?? '未知' }}</code></dd>
        </div>
        <div v-if="configuration.active"><dt>当前版本</dt><dd>{{ configuration.active.label }} · v{{ configuration.active.revision }}</dd></div>
        <div v-if="configuration.activatedAt"><dt>生效时间</dt><dd>{{ date(configuration.activatedAt) }}</dd></div>
        <div v-if="configuration.active || configuration.environmentBaseUrl">
          <dt>接口地址</dt><dd>{{ configuration.active?.baseUrl ?? configuration.environmentBaseUrl }}</dd>
        </div>
      </dl>
    </section>
    <div v-if="feedback || problem || busy" aria-live="polite">
      <p v-if="busy" class="admin-form-hint" role="status">正在处理，请稍候…</p>
      <p v-else-if="problem" class="admin-form-error" role="alert">{{ problem }}
        <button class="admin-button" type="button" @click="attempt++; problem = ''; confirmation = undefined; policyDraft = undefined; draft = undefined">重新加载</button>
      </p>
      <p v-else class="admin-form-hint" role="status">{{ feedback }}</p>
    </div>
    <section v-if="configuration.models" class="admin-panel" aria-labelledby="admin-model-list">
      <header class="admin-panel-toolbar">
        <h2 id="admin-model-list">模型配置列表</h2>
        <button class="admin-button admin-button--primary" type="button" :disabled="busy || !configuration.secretStorageReady" @click="editModel()">新增配置</button>
      </header>
      <p v-if="!configuration.secretStorageReady" class="settings-line">服务器配置密钥加密后，即可添加模型。</p>
      <p v-else-if="!latest.length && !draft" class="settings-line">暂无已保存的模型。添加兼容聊天接口后，可以测试并切换。</p>
      <form v-if="draft" class="settings-form" @submit.prevent="save">
        <h3>{{ draft.profileId ? '编辑模型配置' : '新增模型配置' }}</h3>
        <fieldset :disabled="busy">
          <label>配置名称 <input v-model="draft.label" class="admin-input" required maxlength="100"></label>
          <label>模型名称 <input v-model="draft.modelName" class="admin-input" required maxlength="150"></label>
          <label>接口地址 <input v-model="draft.baseUrl" class="admin-input" type="url" required maxlength="500"
            aria-describedby="allowed-model-url"></label>
          <label>API Key <input v-model="draft.apiKey" class="admin-input" type="password" autocomplete="new-password"
            :required="!draft.profileId" maxlength="512" :placeholder="draft.profileId ? '留空保留现有密钥' : undefined"></label>
        </fieldset>
        <p id="allowed-model-url" class="admin-muted">使用部署人员授权的兼容聊天接口。更换地址时需重新填写密钥。</p>
        <div class="settings-actions">
          <button class="admin-button admin-button--primary" :disabled="busy">保存版本</button>
          <button class="admin-button" type="button" :disabled="busy" @click="draft = undefined">取消</button>
        </div>
      </form>
      <div v-if="configuration.active" class="settings-line settings-actions">
        <button class="admin-button" type="button" :disabled="busy"
          @click="confirmation = { id: null, label: '启动配置' }">恢复启动配置</button>
      </div>
      <div v-if="confirmation" class="settings-line" aria-label="确认切换">
        <p>将切换到「{{ confirmation.label }}」。下一轮导诊请求生效。</p>
        <div class="settings-actions">
          <button class="admin-button admin-button--primary" type="button" :disabled="busy" @click="activate">确认切换</button>
          <button class="admin-button" type="button" :disabled="busy" @click="confirmation = undefined">取消</button>
        </div>
      </div>
      <p v-if="latest.length" class="settings-line admin-muted">测试使用合成输入，会消耗少量模型调用额度。</p>
      <div v-for="model in (historyOpen ? [...latest, ...history] : latest)" :key="model.id" class="settings-line model-entry">
        <div>
          <strong>{{ model.label }} · v{{ model.revision }}</strong>
          <span v-if="configuration.active?.id === model.id" class="model-active">正在使用</span>
          <p><code>{{ model.modelName }}</code> · {{ model.baseUrl }}</p>
          <p>{{ model.probeCode ? (probeLabels[model.probeCode] ?? '测试未通过') : '未测试' }}
            <span v-if="model.testedAt"> · {{ date(model.testedAt) }} · {{ model.probeMs }} 毫秒</span>
          </p>
        </div>
        <div class="settings-actions">
          <button v-if="latest.some(item => item.id === model.id)" class="admin-button" type="button"
            :disabled="busy || !configuration.secretStorageReady" @click="editModel(model)">编辑</button>
          <button class="admin-button" type="button" :disabled="busy || !configuration.secretStorageReady" @click="verify(model)">测试</button>
          <button v-if="configuration.active?.id !== model.id" class="admin-button" type="button"
            :disabled="busy || model.probeCode !== 'PASSED' || !configuration.secretStorageReady"
            @click="confirmation = { id: model.id, label: model.label + ' · v' + model.revision }">启用</button>
        </div>
      </div>
      <div v-if="history.length" class="settings-line">
        <button class="admin-button" type="button" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">
          {{ historyOpen ? '收起历史版本' : '查看历史版本' }}
        </button>
      </div>
    </section>
    <section class="admin-panel" aria-labelledby="admin-model-timeouts">
      <header class="admin-panel-toolbar">
        <h2 id="admin-model-timeouts">超时限制</h2>
        <button v-if="!policyDraft && configuration.models" class="admin-button" type="button" :disabled="busy" @click="editPolicy">编辑超时</button>
      </header>
      <dl class="model-fields">
        <div><dt>单次模型调用</dt><dd>{{ duration(configuration.callPolicy.modelTimeoutMs) }}</dd></div>
        <div><dt>单次只读工具调用</dt><dd>{{ duration(configuration.callPolicy.toolTimeoutMs) }}</dd></div>
        <div><dt>本轮 Agent 请求总预算</dt><dd>{{ duration(configuration.callPolicy.requestTimeoutMs) }}</dd></div>
      </dl>
      <form v-if="policyDraft" class="settings-form" @submit.prevent="savePolicy">
        <fieldset :disabled="busy">
          <label v-for="field in timeoutFields" :key="field.key">{{ field.label }}（毫秒）
            <input v-model.number="policyDraft[field.key]" class="admin-input" type="number" required min="100" max="120000">
          </label>
        </fieldset>
        <p class="admin-muted">整轮时限须覆盖模型、默认工具及各工具单独设置的超时。</p>
        <div class="settings-actions">
          <button class="admin-button admin-button--primary" :disabled="busy">保存超时</button>
          <button class="admin-button" type="button" :disabled="busy" @click="policyDraft = undefined">取消</button>
        </div>
      </form>
    </section>
    <section class="admin-panel" aria-labelledby="admin-model-retries">
      <header class="admin-panel-toolbar"><h2 id="admin-model-retries">重试规则</h2></header>
      <dl class="model-fields">
        <div>
          <dt>Agent 调用</dt>
          <dd>最多 {{ configuration.callPolicy.maxAttempts - 1 }} 次重试（含首次共 {{ configuration.callPolicy.maxAttempts }} 次尝试）</dd>
        </div>
        <div v-if="configuration.provider"><dt>模型输出纠正</dt><dd>最多 {{ configuration.provider.maxOutputCorrections }} 次</dd></div>
        <div v-if="configuration.provider"><dt>供应商 SDK 自动重试</dt><dd>{{ configuration.provider.sdkMaxRetries }} 次</dd></div>
      </dl>
      <div class="model-retry-detail">
        <p>Agent 仅在还有请求预算时重试以下失败。</p>
        <ul aria-label="可重试的失败">
          <li v-for="reason in configuration.callPolicy.retryableReasons" :key="reason">{{ reasonLabels[reason] ?? reason }}</li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.model-settings { display: grid; gap: 16px; }
.model-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 200px), 1fr)); margin: 0; }
.model-fields > div { padding: 13px 18px 16px; border-top: 1px solid var(--admin-border); }
.model-fields dt { color: var(--admin-muted); font-size: 13px; }
.model-fields dd { margin: 5px 0 0; font-size: 16px; font-weight: 650; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.model-retry-detail { padding: 12px 18px 15px; border-top: 1px solid var(--admin-border); color: var(--admin-muted); }
.model-retry-detail p { margin: 0 0 6px; }
.model-retry-detail ul { display: flex; flex-wrap: wrap; gap: 5px 20px; margin: 0; padding-inline-start: 18px; }
.settings-line, .settings-form { margin: 0; padding: 14px 18px; border-top: 1px solid var(--admin-border); overflow-wrap: anywhere; }
.settings-form h3, .settings-line p { margin: 0 0 8px; }
.settings-form fieldset { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 235px), 1fr)); gap: 12px; padding: 0; margin: 0; border: 0; min-width: 0; }
.settings-form label { display: grid; gap: 4px; }
.settings-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.model-entry { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; }
.model-entry p { margin: 3px 0 0; }
.model-entry > div { min-width: 0; }
.model-active { display: inline-block; margin-inline-start: 8px; font-size: 13px; color: #215d40; }
</style>
