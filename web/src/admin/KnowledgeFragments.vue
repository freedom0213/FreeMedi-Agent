<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { BookOpen, X } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import {
  agentSettingsError, readKnowledgeFragments, retryKnowledgeIndex,
  type DocumentType, type FragmentFilters, type IndexStatus, type KnowledgeFragment, type VersionStatus,
} from '../api/admin'
import { documentTypeLabels, indexLabels, knowledgeFragmentLink, publishedTime, versionLabels } from './knowledgeLabels'
import KnowledgeIndexStatus from './KnowledgeIndexStatus.vue'
import KnowledgeSearchProbe from './KnowledgeSearchProbe.vue'
import { useKnowledgeRead } from './useKnowledgeRead'
import PaginationBar from '../components/PaginationBar.vue'

const props = defineProps<{ vectors: boolean }>()
const route = useRoute()
function queryType(value: unknown): DocumentType | '' {
  return typeof value === 'string' && Object.prototype.hasOwnProperty.call(documentTypeLabels, value) ? value as DocumentType : ''
}
const query = ref('')
const category = ref(queryType(route.query.type))
const status = ref(readOption(route.query.status, versionLabels))
const embedding = ref(readOption(route.query.embedding, indexLabels))
const filters = shallowRef<FragmentFilters>({ page: 1, docType: category.value || undefined,
  status: status.value || undefined, embedding: embedding.value || undefined })
const indexSummary = ref<InstanceType<typeof KnowledgeIndexStatus>>()
const { data, state, reload } = useKnowledgeRead(signal => readKnowledgeFragments(filters.value, props.vectors, signal))
const working = ref<number>()
const feedback = ref('')
const problem = ref('')
const reader = ref<HTMLDialogElement>()
const reading = shallowRef<KnowledgeFragment | null>(null)
const preview = shallowRef<{ row: KnowledgeFragment; left: number; top: number; above: boolean } | null>(null)
let previewTimer: number | undefined
let readerTrigger: HTMLButtonElement | null = null

function clearPreview() {
  window.clearTimeout(previewTimer)
  preview.value = null
}

function schedulePreview(event: Event, row: KnowledgeFragment) {
  if (reading.value || (event instanceof PointerEvent && event.pointerType !== 'mouse')) return
  const button = event.currentTarget as HTMLButtonElement
  const rect = button.getBoundingClientRect()
  const width = Math.min(326, window.innerWidth - 24)
  const above = rect.bottom + 150 > window.innerHeight && rect.top > 150
  clearPreview()
  previewTimer = window.setTimeout(() => {
    preview.value = { row, above, left: Math.min(Math.max(rect.left, 12), window.innerWidth - width - 12),
      top: above ? rect.top - 7 : rect.bottom + 7 }
  }, 300)
}

function schedulePreviewClose() {
  window.clearTimeout(previewTimer)
  previewTimer = window.setTimeout(clearPreview, 180)
}

function keepPreview() {
  window.clearTimeout(previewTimer)
}

function hidePreviewOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') clearPreview()
}

onMounted(() => {
  window.addEventListener('scroll', clearPreview, true)
  window.addEventListener('resize', clearPreview)
  window.addEventListener('keydown', hidePreviewOnEscape)
})
onUnmounted(() => {
  window.removeEventListener('scroll', clearPreview, true)
  window.removeEventListener('resize', clearPreview)
  window.removeEventListener('keydown', hidePreviewOnEscape)
  clearPreview()
})

async function readBody(event: MouseEvent, row: KnowledgeFragment) {
  clearPreview()
  readerTrigger = event.currentTarget as HTMLButtonElement
  reading.value = row
  await nextTick()
  reader.value?.showModal()
}

function closeBody() {
  clearPreview()
  reading.value = null
  readerTrigger?.focus({ preventScroll: true })
  readerTrigger = null
}

const vectorLabels = { READY: '已核验', MISSING: '向量缺失', UNVERIFIED: '暂无法核验', NOT_INDEXED: '向量未就绪' }

watch([() => route.query.type, () => route.query.status, () => route.query.embedding], ([type, nextStatus, nextEmbedding]) => {
  category.value = queryType(type)
  status.value = readOption(nextStatus, versionLabels)
  embedding.value = readOption(nextEmbedding, indexLabels)
  apply()
})

watch(data, result => {
  if (result?.totalPages !== undefined && filters.value.page > Math.max(1, result.totalPages)) {
    go(Math.max(1, result.totalPages))
  }
})

function readOption<T extends VersionStatus | IndexStatus>(candidate: unknown, options: Record<T, string>): T | '' {
  return typeof candidate === 'string' && Object.hasOwn(options, candidate) ? candidate as T : ''
}

function go(page: number) {
  filters.value = { ...filters.value, page }
}

function apply() {
  filters.value = { page: 1, docType: category.value || undefined, status: status.value || undefined,
    embedding: embedding.value || undefined, q: query.value.trim() || undefined }
}

function clear() {
  query.value = ''
  category.value = ''
  status.value = ''
  embedding.value = ''
  apply()
}

function canRetry(row: KnowledgeFragment) {
  return row.documentStatus === 'PUBLISHED' && (row.embeddingStatus !== 'INDEXED' || row.vectorState === 'MISSING')
}

async function retry(row: KnowledgeFragment) {
  if (working.value !== undefined || !canRetry(row)) return
  working.value = row.id
  feedback.value = ''
  problem.value = ''
  try {
    const result = await retryKnowledgeIndex(row.id)
    feedback.value = result.status === 'ALREADY_READY' ? '该片段的向量已存在，无需重试。' : '该片段的向量索引已建立。'
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    working.value = undefined
    reload()
    indexSummary.value?.reload()
  }
}
</script>

<template>
  <h1 class="admin-page-title">{{ vectors ? '向量化与检索' : '知识片段' }}</h1>
  <div class="knowledge-workspace">
    <KnowledgeIndexStatus v-if="vectors" ref="indexSummary" />
    <KnowledgeSearchProbe v-if="vectors" />
    <section class="admin-panel" aria-labelledby="knowledge-fragment-list">
      <header class="admin-panel-toolbar">
        <div>
          <h2 id="knowledge-fragment-list">{{ vectors ? '片段索引' : '片段目录' }}</h2>
          <p class="knowledge-caption admin-muted">{{ vectors ? '向量核验针对当前集合。重试只处理已发布的片段。' : '按来源、版本和状态核对切分后的正文。' }}</p>
        </div>
        <button class="admin-button" type="button" :disabled="state === 'loading' || working !== undefined" @click="reload">刷新列表</button>
      </header>
      <form class="catalog-filter" @submit.prevent="apply">
        <label>搜索片段<input v-model="query" class="admin-input" type="search" maxlength="200" placeholder="文档编号、标题或正文"></label>
        <label>资料类型<select v-model="category" class="admin-input">
          <option value="">全部类型</option>
          <option v-for="(label, type) in documentTypeLabels" :key="type" :value="type">{{ label }}</option>
        </select></label>
        <label>发布状态<select v-model="status" class="admin-input">
          <option value="">全部状态</option>
          <option v-for="(label, value) in versionLabels" :key="value" :value="value">{{ label }}</option>
        </select></label>
        <label>索引状态<select v-model="embedding" class="admin-input">
          <option value="">全部状态</option>
          <option v-for="(label, value) in indexLabels" :key="value" :value="value">{{ label }}</option>
        </select></label>
        <div class="catalog-actions">
          <button class="admin-button">查询</button>
          <button class="admin-button" type="button" @click="clear">重置</button>
        </div>
      </form>
      <p v-if="feedback" class="admin-form-hint catalog-message" role="status">{{ feedback }}</p>
      <p v-if="problem" class="admin-form-error catalog-message" role="alert">{{ problem }}</p>
      <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取片段…</p>
      <div v-else-if="state === 'error'" class="admin-empty" role="alert">
        片段列表加载失败。<button class="admin-button" type="button" @click="reload">重新加载</button>
      </div>
      <div v-else-if="state === 'session'" class="admin-empty" role="alert">
        管理员会话已失效。<RouterLink :to="{ name: 'admin-login' }">重新登录</RouterLink>
      </div>
      <p v-else-if="!data?.items.length" class="admin-empty">没有符合条件的片段。</p>
      <div v-else class="admin-table-scroll" role="region" :aria-label="vectors ? '片段索引记录' : '知识片段记录'" tabindex="0">
        <table class="admin-table catalog-table knowledge-fragment-table">
          <thead><tr>
            <th scope="col">片段</th><th scope="col">来源文档</th><th scope="col">发布状态</th>
            <th v-if="vectors" scope="col">向量模型 / 维度</th><th v-else scope="col">正文</th>
            <th scope="col">索引状态</th><th v-if="vectors" scope="col">索引时间</th><th scope="col">操作</th>
          </tr></thead>
          <tbody><tr v-for="row in data.items" :key="row.id">
            <th scope="row">{{ row.heading || '无标题片段' }}<small>片段 {{ row.chunkNo }} · {{ row.charCount }} 字符</small></th>
            <td>{{ row.title }}<small>{{ row.docNo }} · v{{ row.version }}</small>
              <small>{{ documentTypeLabels[row.docType] }} · {{ row.source }}</small>
            </td>
            <td><span class="admin-tag" :data-state="row.documentStatus">{{ versionLabels[row.documentStatus] }}</span></td>
            <td v-if="vectors"><code v-if="row.vectorModel">{{ row.vectorModel }}</code><span v-else>—</span>
              <small v-if="row.vectorDimensions != null">{{ row.vectorDimensions }} 维</small>
            </td>
            <td v-else>
              <button type="button" class="admin-text-link knowledge-preview-button" aria-haspopup="dialog"
                :aria-describedby="preview?.row.id === row.id ? 'knowledge-preview' : undefined"
                @pointerenter="schedulePreview($event, row)" @pointerleave="schedulePreviewClose"
                @focus="schedulePreview($event, row)" @blur="clearPreview" @click="readBody($event, row)">
                <BookOpen :size="15" aria-hidden="true" /> 查看正文
              </button>
            </td>
            <td><span :class="{ 'admin-negative': row.embeddingStatus === 'FAILED' }">{{ indexLabels[row.embeddingStatus] }}</span>
              <small v-if="row.vectorState">{{ vectorLabels[row.vectorState] }}</small>
              <small v-if="row.failureReason">{{ row.failureReason }}</small>
              <small v-if="vectors && row.documentStatus !== 'PUBLISHED'">不参与已发布资料检索</small>
            </td>
            <td v-if="vectors">{{ row.indexedAt ? publishedTime(row.indexedAt) : '—' }}</td>
            <td><div class="catalog-actions">
              <RouterLink class="admin-text-link" :to="knowledgeFragmentLink(row)">来源版本</RouterLink>
              <button v-if="vectors && canRetry(row)" class="admin-button" type="button" :disabled="working !== undefined"
                @click="retry(row)">{{ working === row.id ? '正在索引…' : '重试索引' }}</button>
            </div></td>
          </tr></tbody>
        </table>
      </div>
      <PaginationBar v-if="data && (filters.page > 1 || data.hasNext || (data.totalPages ?? 0) > 1)"
        :page="filters.page" :total-pages="data.totalPages" :has-next="data.hasNext" :busy="state !== 'ready'" @change="go" />
    </section>
  </div>
  <template v-if="!vectors">
    <Teleport to="body">
      <Transition name="knowledge-preview">
        <div v-if="preview" id="knowledge-preview" class="knowledge-preview" role="tooltip"
          :class="{ 'above-trigger': preview.above }" :style="{ left: preview.left + 'px', top: preview.top + 'px' }"
          @pointerenter="keepPreview" @pointerleave="schedulePreviewClose">
          <p>{{ preview.row.content }}</p>
        </div>
      </Transition>
    </Teleport>
    <dialog ref="reader" class="knowledge-reader" aria-labelledby="knowledge-reader-title"
      @close="closeBody" @click.self="reader?.close()">
      <div v-if="reading" class="knowledge-reader-inner">
        <header>
          <div>
            <h2 id="knowledge-reader-title">{{ reading.heading || '无标题片段' }}</h2>
            <p>{{ reading.title }} · {{ reading.docNo }} · v{{ reading.version }}</p>
          </div>
          <button class="admin-button" type="button" autofocus aria-label="关闭正文" @click="reader?.close()"><X :size="20" /></button>
        </header>
        <p class="knowledge-reader-text">{{ reading.content }}</p>
        <footer><RouterLink class="admin-text-link" :to="knowledgeFragmentLink(reading)" @click="reader?.close()">查看来源版本</RouterLink></footer>
      </div>
    </dialog>
  </template>
</template>

<style scoped>
.knowledge-preview-button { display: inline-flex; align-items: center; gap: 5px; min-height: 36px; padding: 0; border: 0; background: none; white-space: nowrap; cursor: pointer; }
.knowledge-preview { position: fixed; z-index: 20; width: min(326px, calc(100vw - 24px)); padding: 11px 13px; border: 1px solid #cedaea; border-radius: 9px; background: #fff; color: #263b56; box-shadow: 0 6px 20px #203b602c; }
.knowledge-preview p { display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; margin: 0; font-size: 13px; line-height: 1.55; white-space: pre-wrap; overflow-wrap: anywhere; }
.knowledge-preview.above-trigger { transform: translateY(-100%); }
.knowledge-preview-enter-active, .knowledge-preview-leave-active { transition: opacity 120ms ease; }
.knowledge-preview-enter-from, .knowledge-preview-leave-to { opacity: 0; }
.knowledge-reader { position: fixed; inset: 0 0 0 auto; width: min(100%, 490px); max-width: none; max-height: none; height: 100%; margin: 0; padding: 0; border: 0; border-inline-start: 1px solid var(--admin-border); background: #fff; color: var(--admin-ink); box-shadow: -8px 0 34px #1a304738; }
.knowledge-reader[open] { animation: knowledge-reader-in 180ms cubic-bezier(.2,.75,.3,1); }
.knowledge-reader::backdrop { background: #13274434; }
.knowledge-reader-inner { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; height: 100%; }
.knowledge-reader header { display: flex; align-items: start; justify-content: space-between; gap: 12px; padding: 22px; border-bottom: 1px solid var(--admin-border); }
.knowledge-reader h2 { margin: 0 0 5px; font-size: 21px; overflow-wrap: anywhere; }
.knowledge-reader header p { margin: 0; color: var(--admin-muted); font-size: 13px; overflow-wrap: anywhere; }
.knowledge-reader header button { flex: none; padding: 0; width: 40px; }
.knowledge-reader-text { margin: 0; overflow: auto; padding: 22px; font-size: 15px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.knowledge-reader footer { padding: 15px 22px; border-top: 1px solid var(--admin-border); }
@keyframes knowledge-reader-in { from { opacity: .45; transform: translateX(24px); } }
@media (prefers-reduced-motion: reduce) {
  .knowledge-preview-enter-active, .knowledge-preview-leave-active { transition: none; }
  .knowledge-reader[open] { animation: none; }
}
</style>
