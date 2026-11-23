<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { ArrowLeft, ChevronDown, FileText, FileUp } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { maxMarkdownBytes, previewMarkdown, saveKnowledgeDraft, type KnowledgeImportPreview } from '../api/admin'
import { documentTypeLabels, versionLabels } from './knowledgeLabels'
import AdminPagination from './AdminPagination.vue'

const selectedFile = shallowRef<File>()
const result = shallowRef<KnowledgeImportPreview>()
const issue = ref('')
const signIn = ref(false)
const loading = ref(false)
const saving = ref(false)
const router = useRouter()
const page = ref(0)
const pageSize = 20
let pending: AbortController | undefined

const chunks = computed(() => result.value?.chunks ?? [])
const firstChunk = computed(() => page.value * pageSize)
const visibleChunks = computed(() => chunks.value.slice(firstChunk.value, firstChunk.value + pageSize))

onBeforeUnmount(() => pending?.abort())

function selectFile(event: Event) {
  pending?.abort()
  pending = undefined
  loading.value = false
  selectedFile.value = undefined
  result.value = undefined
  issue.value = ''
  signIn.value = false
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.md')) issue.value = '请选择 .md 格式的 Markdown 文件。'
  else if (!file.size) issue.value = '文件为空，请选一份含正文的 Markdown 文件。'
  else if (file.size > maxMarkdownBytes) issue.value = '文件超过 256 KiB，请缩小文件后再预览。'
  else selectedFile.value = file
  if (issue.value) input.value = ''
}

async function inspectFile() {
  if (!selectedFile.value || loading.value || saving.value) return
  const controller = new AbortController()
  pending = controller
  loading.value = true
  issue.value = ''
  result.value = undefined
  signIn.value = false
  const response = await previewMarkdown(selectedFile.value, controller.signal)
  if (controller.signal.aborted) return
  loading.value = false
  pending = undefined
  if (response.kind === 'issue') {
    issue.value = response.message
    signIn.value = Boolean(response.signIn)
  } else {
    page.value = 0
    result.value = response.preview
  }
}

async function saveDraft() {
  if (!selectedFile.value || !result.value || saving.value || result.value.duplicate) return
  const controller = new AbortController()
  pending = controller
  saving.value = true
  issue.value = ''
  const response = await saveKnowledgeDraft(selectedFile.value, result.value, controller.signal)
  if (controller.signal.aborted) return
  pending = undefined
  saving.value = false
  if (response.kind === 'issue') {
    issue.value = response.message
    signIn.value = Boolean(response.signIn)
    result.value = undefined
    return
  }
  await router.push({ name: 'admin-document', params: { docNo: response.data.docNo },
    query: { version: response.data.version } })
}
</script>

<template>
  <RouterLink class="admin-text-link admin-detail-back" :to="{ name: 'admin-knowledge' }">
    <ArrowLeft :size="16" aria-hidden="true" /> 返回院内资料
  </RouterLink>
  <h1 class="admin-page-title">Markdown 导入预览</h1>
  <form class="admin-panel admin-preview-form" @submit.prevent="inspectFile">
    <div class="admin-preview-picker">
      <span class="admin-module-icon"><FileText :size="22" aria-hidden="true" /></span>
      <div>
        <label for="admin-markdown-file">选择 Markdown 文件</label>
        <p id="admin-markdown-help">UTF-8 · .md · 最大 256 KiB</p>
        <input id="admin-markdown-file" type="file" accept=".md" aria-describedby="admin-markdown-help" :disabled="saving" @change="selectFile" />
      </div>
      <button class="admin-button admin-button--primary" type="submit" :disabled="!selectedFile || loading || saving || signIn">
        <FileUp :size="16" aria-hidden="true" /> {{ loading ? '正在解析…' : '解析并预览' }}
      </button>
    </div>
    <p class="admin-muted admin-preview-scope">预览仅显示资料与切分结果，不会创建版本或改变当前发布状态。</p>
    <p v-if="loading" role="status" class="admin-muted">正在解析文件并核对资料版本…</p>
    <div v-if="issue" class="admin-form-error" role="alert">
      {{ issue }}
      <RouterLink v-if="signIn" class="admin-text-link"
                  :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
    </div>
  </form>
  <template v-if="result">
    <section class="admin-panel admin-preview-summary" aria-labelledby="admin-preview-metadata">
      <header class="admin-panel-toolbar">
        <h2 id="admin-preview-metadata">资料信息</h2>
        <span v-if="result.duplicate" class="admin-tag" data-state="PENDING">重复内容</span>
        <span v-else class="admin-tag" data-state="PUBLISHED">{{ result.disposition === 'NEW_DOCUMENT' ? '新资料' : '新版本' }}</span>
      </header>
      <dl class="admin-version-facts admin-preview-facts">
        <div><dt>资料名称</dt><dd>{{ result.title }}</dd></div>
        <div><dt>资料编号</dt><dd>{{ result.docNo }}</dd></div>
        <div><dt>资料类型</dt><dd>{{ documentTypeLabels[result.docType] }}</dd></div>
        <div><dt>来源</dt><dd>{{ result.source }}</dd></div>
        <div v-if="result.departmentId"><dt>科室编号</dt><dd>{{ result.departmentId }}</dd></div>
        <div v-if="result.doctorId"><dt>医生编号</dt><dd>{{ result.doctorId }}</dd></div>
        <div>
          <dt>当前发布版本</dt>
          <dd>
            <RouterLink v-if="result.published" class="admin-text-link"
                        :to="{ name: 'admin-document', params: { docNo: result.docNo }, query: { version: result.published.version } }">
              v{{ result.published.version }}
            </RouterLink>
            <span v-else class="admin-muted">暂无</span>
          </dd>
        </div>
        <div v-if="result.duplicate">
          <dt>相同内容所在版本</dt>
          <dd><RouterLink class="admin-text-link"
                          :to="{ name: 'admin-document', params: { docNo: result.docNo }, query: { version: result.duplicate.version } }">
            v{{ result.duplicate.version }} · {{ versionLabels[result.duplicate.status] }}
          </RouterLink></dd>
        </div>
        <div v-else><dt>预计版本号</dt><dd>v{{ result.proposedVersion }}</dd></div>
      </dl>
      <p v-if="result.duplicate" class="admin-preview-duplicate">这份内容已导入过。现有导入流程会跳过重复内容，保留原版本状态。</p>
      <footer v-else class="admin-panel-toolbar admin-draft-footer">
        <span class="admin-muted">保存后可建立索引，再确认发布。</span>
        <button class="admin-button admin-button--primary" type="button" :disabled="saving" @click="saveDraft">
          {{ saving ? '正在保存…' : '保存为草稿' }}
        </button>
      </footer>
    </section>
    <section class="admin-panel admin-preview-chunks" aria-labelledby="admin-preview-chunks">
      <header class="admin-panel-toolbar">
        <h2 id="admin-preview-chunks">切分预览</h2>
        <span class="admin-muted">{{ chunks.length }} 段</span>
      </header>
      <details v-for="chunk in visibleChunks" :key="chunk.chunkNo" :open="chunk.chunkNo === firstChunk + 1">
        <summary>
          <span class="admin-chunk-index">片段 {{ String(chunk.chunkNo).padStart(2, '0') }}</span>
          <strong>{{ chunk.heading || '无章节标题' }}</strong>
          <small>{{ chunk.charCount }} 字符</small>
          <ChevronDown :size="15" aria-hidden="true" />
        </summary>
        <p class="admin-chunk-text">{{ chunk.content }}</p>
      </details>
      <AdminPagination v-if="chunks.length > pageSize" :page="page + 1" :total-pages="Math.ceil(chunks.length / pageSize)"
        @change="page = $event - 1" />
    </section>
  </template>
</template>
