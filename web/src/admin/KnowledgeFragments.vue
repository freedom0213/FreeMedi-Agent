<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  agentSettingsError, readKnowledgeFragments, retryKnowledgeIndex,
  type DocumentType, type FragmentFilters, type IndexStatus, type KnowledgeFragment, type VersionStatus,
} from '../api/admin'
import { documentTypeLabels, indexLabels, knowledgeFragmentLink, publishedTime, versionLabels } from './knowledgeLabels'
import KnowledgeIndexStatus from './KnowledgeIndexStatus.vue'
import KnowledgeSearchProbe from './KnowledgeSearchProbe.vue'
import { useKnowledgeRead } from './useKnowledgeRead'
import AdminPagination from './AdminPagination.vue'

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
        管理员会话已失效。<RouterLink :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
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
            <td v-else><details class="knowledge-inline-excerpt"><summary>查看正文</summary><p class="knowledge-excerpt">{{ row.content }}</p></details></td>
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
      <AdminPagination v-if="data && (filters.page > 1 || data.hasNext || (data.totalPages ?? 0) > 1)"
        :page="filters.page" :total-pages="data.totalPages" :has-next="data.hasNext" :busy="state !== 'ready'" @change="go" />
    </section>
  </div>
</template>
