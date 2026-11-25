<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, FileText, RotateCcw } from 'lucide-vue-next'
import {
  activatePromptTemplate, agentSettingsError, readPromptTemplates, savePromptTemplate, trialPromptTemplate,
  type PromptDraft, type PromptTemplateView, type PromptVersion,
} from '../api/admin'
import { reasons } from './agentTraceLabels'
import { useKnowledgeRead } from './useKnowledgeRead'

const { data, state, reload } = useKnowledgeRead(readPromptTemplates)
const selected = ref<number | null>(null)
const draft = ref<PromptDraft>()
const editor = ref<HTMLElement>()
const versionPanel = ref<HTMLElement>()
const confirmationPanel = ref<HTMLElement>()
const busy = ref<'save' | 'trial' | 'activate'>()
const confirmation = ref<PromptVersion | 'builtin'>()
const message = ref('')
const error = ref('')
const active = computed(() => data.value?.versions.find(version => version.id === data.value?.activeId))
const current = computed(() => data.value?.versions.find(version => version.id === selected.value))
const locked = computed(() => !!busy.value || state.value !== 'ready')
const date = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short' })
const fields = [
  { key: 'triage', label: '导诊沟通' },
  { key: 'selection', label: '选号确认引导' },
] as const
const guidance = (value: string | undefined) => value || '沿用内置底稿'
const versionLabel = (value: string) => value.startsWith('builtin-') ? '内置 ' + value.slice(8) : value
const trialFailure = (code: string) => reasons[code] ?? ({
  EXPECTATION_NOT_MET: '结果未达到用例预期',
  MODEL_REQUEST_FAILED: '模型请求失败',
} as Record<string, string>)[code] ?? code

watch(draft, value => { if (value) editor.value?.scrollIntoView({ block: 'start' }) }, { flush: 'post' })
watch(selected, value => { if (value) versionPanel.value?.scrollIntoView({ block: 'start' }) }, { flush: 'post' })
watch(confirmation, value => { if (value) confirmationPanel.value?.focus() }, { flush: 'post' })

function status(version: PromptVersion) {
  if (busy.value === 'trial' && version.id === selected.value || data.value?.runningTrial === version.id) return '试跑中'
  if (version.canActivate) return '试跑通过'
  if (version.trialPassed) return '配置已变，需重跑'
  return version.trialPassed === false ? '试跑未通过' : '待试跑'
}

function edit(version = active.value) {
  draft.value = { triage: version?.prompt.triage ?? '', selection: version?.prompt.selection ?? '', note: '' }
  message.value = ''
  error.value = ''
}

function select(id: number) {
  selected.value = id
  confirmation.value = undefined
  error.value = ''
}

async function update(kind: NonNullable<typeof busy.value>, run: () => Promise<PromptTemplateView>,
  success: (result: PromptTemplateView) => string) {
  if (locked.value) return
  busy.value = kind
  error.value = ''
  message.value = ''
  try {
    const result = await run()
    data.value = result
    message.value = success(result)
  } catch (failure) {
    error.value = agentSettingsError(failure)
  } finally {
    busy.value = undefined
  }
}

function save() {
  if (!draft.value) return
  const values = { ...draft.value }
  void update('save', () => savePromptTemplate(values), result => {
    draft.value = undefined
    selected.value = result.versions[0]?.id ?? null
    return '已保存新版本。请先试跑，再手动启用。'
  })
}

function trial(version: PromptVersion) {
  void update('trial', () => trialPromptTemplate(version.id), result =>
    result.versions.find(row => row.id === version.id)?.canActivate
      ? '必测用例已通过，可以手动启用。' : '试跑已完成，请查看逐项结果和当前配置。')
}

function activate() {
  if (!confirmation.value || !data.value) return
  const target = confirmation.value
  const revision = data.value.revision
  void update('activate', () => activatePromptTemplate(target === 'builtin' ? null : target.id, revision), () => {
    confirmation.value = undefined
    return target === 'builtin' ? '已恢复内置模板，下一轮请求生效。'
      : '已启用 ' + target.prompt.version + '，下一轮请求生效。'
  })
}
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">Prompt 模板</h1>
    <button v-if="data" class="admin-button" type="button" :disabled="locked" @click="reload()">刷新</button>
  </header>
  <p v-if="state === 'loading'" class="admin-panel admin-empty" role="status">正在读取模板版本…</p>
  <div v-else-if="state === 'error'" class="admin-panel admin-empty" role="alert">
    模板加载失败。<button class="admin-button" type="button" @click="reload()">重新加载</button>
  </div>
  <p v-else-if="state === 'session'" class="admin-form-error" role="alert">
    管理员会话已失效。<RouterLink class="admin-text-link" :to="{ name: 'admin-login' }">重新登录</RouterLink>
  </p>
  <div v-else-if="data" class="prompt-workspace">
    <section class="admin-panel" aria-labelledby="prompt-active">
      <header class="admin-panel-toolbar prompt-active-header">
        <div>
          <h2 id="prompt-active">智能导诊对话表达</h2>
          <p class="admin-muted">运行版本 <strong>{{ versionLabel(data.effectiveVersion) }}</strong>
            <template v-if="data.activatedAt"> · {{ date.format(new Date(data.activatedAt)) }}切换</template></p>
        </div>
        <div class="catalog-actions">
          <button class="admin-button admin-button--primary" type="button" :disabled="locked || !!draft" @click="edit()">新建版本</button>
          <button v-if="data.activeId !== null" class="admin-button" type="button" :disabled="locked" @click="confirmation = 'builtin'">
            <RotateCcw :size="15" aria-hidden="true" /> 恢复内置模板
          </button>
        </div>
      </header>
      <p v-if="!data.trialAvailable" class="admin-form-hint prompt-notice">
        当前使用本地演示规则。启用外部模型后可试跑。
        <RouterLink class="admin-text-link" :to="{ name: 'admin-model-config' }">查看模型配置</RouterLink>
      </p>
      <p v-else-if="active && !active.canActivate" class="admin-form-hint prompt-notice">当前模型或工具配置已变化。请重新试跑当前模板以复核结果。</p>
      <details class="prompt-base">
        <summary><FileText :size="16" aria-hidden="true" /> 查看只读底稿</summary>
        <pre>{{ data.basePrompt }}</pre>
      </details>
    </section>

    <p v-if="message" class="admin-form-hint" role="status">{{ message }}</p>
    <p v-if="error" class="admin-form-error" role="alert">{{ error }}</p>
    <div v-if="confirmation" ref="confirmationPanel" class="admin-panel prompt-confirm" role="group" aria-label="确认切换模板" tabindex="-1">
      <p>确认{{ confirmation === 'builtin' ? '恢复内置模板' : '启用 ' + confirmation.prompt.version }}？下一轮导诊请求开始生效。</p>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" type="button" :disabled="locked" @click="activate">
          {{ confirmation === 'builtin' ? '确认恢复' : '确认启用' }}
        </button>
        <button class="admin-button" type="button" :disabled="locked" @click="confirmation = undefined">取消</button>
      </div>
    </div>

    <form v-if="draft" ref="editor" class="admin-panel" aria-labelledby="prompt-editor" @submit.prevent="save">
      <header class="admin-panel-toolbar"><h2 id="prompt-editor">编辑新版本</h2></header>
      <div class="prompt-form">
        <div v-for="field in fields" :key="field.key" class="prompt-edit-row">
          <div class="prompt-compare"><strong>当前生效 · {{ field.label }}</strong><p>{{ guidance(active?.prompt[field.key]) }}</p></div>
          <label>{{ field.label }}<textarea v-model="draft[field.key]" class="admin-input" rows="3" maxlength="400"
            placeholder="留空则沿用内置表达" :disabled="locked"></textarea></label>
        </div>
        <label>修改说明<input v-model="draft.note" class="admin-input" maxlength="160" :disabled="locked"></label>
        <div class="catalog-actions">
          <button class="admin-button admin-button--primary" :disabled="locked">保存新版本</button>
          <button class="admin-button" type="button" :disabled="locked" @click="draft = undefined">取消编辑</button>
        </div>
      </div>
    </form>

    <section class="admin-panel" aria-labelledby="prompt-history">
      <header class="admin-panel-toolbar"><h2 id="prompt-history">版本记录</h2></header>
      <p v-if="!data.versions.length" class="admin-empty">暂无自定义版本。</p>
      <div v-else class="admin-table-scroll" role="region" aria-label="模板版本列表" tabindex="0">
        <table class="admin-table prompt-table">
          <thead><tr><th scope="col">版本</th><th scope="col">修改说明</th><th scope="col">保存信息</th><th scope="col">试跑</th><th scope="col">操作</th></tr></thead>
          <tbody><tr v-for="version in data.versions" :key="version.id">
            <td><strong>{{ version.prompt.version }}</strong><small v-if="version.id === data.activeId">已选用</small></td>
            <td>{{ version.note || '—' }}</td>
            <td>{{ version.createdBy }}<small>{{ date.format(new Date(version.createdAt)) }}</small></td>
            <td>{{ status(version) }}<small v-if="version.testedAt">{{ date.format(new Date(version.testedAt)) }}</small></td>
            <td><button class="admin-button" type="button" :aria-pressed="current?.id === version.id" :disabled="locked" @click="select(version.id)">查看 {{ version.prompt.version }}</button></td>
          </tr></tbody>
        </table>
      </div>
    </section>

    <section v-if="current" ref="versionPanel" class="admin-panel" aria-labelledby="prompt-version">
      <header class="admin-panel-toolbar prompt-active-header">
        <div><h2 id="prompt-version">{{ current.prompt.version }} · 对比与试跑</h2>
          <p class="admin-muted">{{ status(current) }}</p></div>
        <div class="catalog-actions">
          <button class="admin-button" type="button" :disabled="locked || !!draft" @click="edit(current)">复制编辑</button>
          <button class="admin-button" type="button" :disabled="locked || !data.trialAvailable || data.runningTrial !== null" @click="trial(current)">
            {{ busy === 'trial' ? '正在试跑…' : '试跑合成用例' }}
          </button>
          <button v-if="current.id !== data.activeId" class="admin-button admin-button--primary" type="button" :disabled="locked || !current.canActivate || data.runningTrial === current.id"
            @click="confirmation = current" >启用 {{ current.prompt.version }}</button>
        </div>
      </header>
      <p v-if="busy === 'trial'" class="admin-form-hint prompt-notice" role="status">正在对比生效版和候选版，请等待模型返回。</p>
      <div v-for="field in fields" :key="field.key" class="prompt-edit-row prompt-diff">
        <div class="prompt-compare"><strong>当前生效 · {{ field.label }}</strong><p>{{ guidance(active?.prompt[field.key]) }}</p></div>
        <div class="prompt-compare"><strong>{{ current.prompt.version }} · {{ field.label }}</strong>
          <p>{{ guidance(current.prompt[field.key]) }}</p></div>
      </div>
      <div v-if="current.report" class="prompt-report">
        <p class="admin-muted">{{ current.report.modelLabel }} · 对比版本 {{ versionLabel(current.report.baselineVersion) }}<span v-if="!current.canActivate && current.trialPassed"> · 历史试跑结果</span></p>
        <details v-for="sample in current.report.cases" :key="sample.key" class="prompt-case">
          <summary>{{ sample.label }} <span class="admin-tag" :data-state="sample.candidate.passed ? 'INDEXED' : 'FAILED'">{{ sample.candidate.passed ? '通过' : '未通过' }}</span></summary>
          <p>{{ sample.input }}<small>{{ sample.expectation }}</small></p>
          <div class="prompt-edit-row">
            <div v-for="variant in (['baseline', 'candidate'] as const)" :key="variant" class="prompt-compare">
              <strong>{{ variant === 'baseline' ? '对比版本' : '候选版本' }} · {{ sample[variant].passed ? '通过' : '未通过' }}</strong>
              <p v-if="sample[variant].reply">{{ sample[variant].reply }}</p>
              <p v-if="sample[variant].tool">拟调用 <code>{{ sample[variant].tool }}</code><small>{{ sample[variant].arguments }}</small></p>
              <p v-if="sample[variant].failure" class="admin-negative">{{ trialFailure(sample[variant].failure!) }}</p>
            </div>
          </div>
        </details>
      </div>
      <p v-else class="admin-empty">试跑使用合成用例，展示拟调用的工具和回复。</p>
    </section>
    <RouterLink v-if="data.activatedAt" class="admin-text-link prompt-timeline-link" :to="{ name: 'admin-agent-timeline' }">
      查看 Agent 执行时间线 <ArrowRight :size="15" aria-hidden="true" />
    </RouterLink>
  </div>
</template>

<style scoped>
.prompt-workspace { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
.prompt-active-header { flex-wrap: wrap; }
.prompt-active-header p { margin: 4px 0 0; }
.prompt-notice { margin: 0 18px 12px; }
.prompt-base { border-top: 1px solid var(--admin-border); }
.prompt-base summary, .prompt-case summary { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.prompt-base summary { padding: 12px 18px; color: var(--admin-blue-deep); }
.prompt-base summary::after, .prompt-case summary::after { content: '+'; margin-inline-start: auto; color: var(--admin-muted); }
details[open] > summary::after { content: '−'; }
.prompt-base pre { margin: 0 18px 18px; padding: 12px; max-height: 300px; overflow: auto; white-space: pre-wrap;
  overflow-wrap: anywhere; background: var(--admin-canvas); border-radius: 6px; color: var(--admin-ink); font: inherit; }
.prompt-confirm, .prompt-form, .prompt-report { padding: 14px 18px; }
.prompt-confirm p, .prompt-compare p { margin: 0; }
.prompt-confirm .catalog-actions { margin-top: 8px; }
.prompt-form { display: grid; gap: 14px; border-top: 1px solid var(--admin-border); }
.prompt-form label { display: grid; gap: 5px; font-weight: 600; }
.prompt-form textarea { resize: vertical; }
.prompt-edit-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.prompt-compare { padding: 10px 12px; border-radius: 6px; background: var(--admin-canvas); overflow-wrap: anywhere; }
.prompt-compare p { margin-top: 4px; white-space: pre-wrap; }
.prompt-diff { padding: 8px 18px; }
.prompt-table { min-width: 590px; }
.prompt-table td:nth-child(2) { max-width: 30ch; white-space: normal; overflow-wrap: anywhere; }
.prompt-report { border-top: 1px solid var(--admin-border); }
.prompt-report > p { margin-top: 0; }
.prompt-case { border-top: 1px solid var(--admin-border); padding-block: 10px; }
.prompt-case > p { margin: 8px 0; }
.prompt-case small { display: block; color: var(--admin-muted); }
.prompt-timeline-link { justify-self: start; }
@media (max-width: 650px) { .prompt-edit-row { grid-template-columns: minmax(0, 1fr); } }
</style>
