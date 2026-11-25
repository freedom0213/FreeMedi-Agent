<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Archive, ArrowLeft, ChevronRight } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { adminHttpStatus, readDocument, type DocumentDetail, type DocumentVersion } from '../api/admin'
import { documentTypeLabels, indexLabels, publishedTime, versionLabels } from './knowledgeLabels'
import KnowledgeArchiveDialog from './KnowledgeArchiveDialog.vue'
import KnowledgeDraftActions from './KnowledgeDraftActions.vue'

const route = useRoute()
const router = useRouter()
const detail = ref<DocumentDetail>()
const selectedChunkNo = ref<number>()
const attempt = ref(0)
const archiveTarget = ref<DocumentVersion>()
const archivedVersion = ref<{ docNo: string; version: number }>()
const draftNotice = ref<{ message: string; issue: boolean }>()
const state = ref<'loading' | 'ready' | 'error' | 'missing' | 'session'>('loading')
const document = computed(() => detail.value?.document)
const chunk = computed(() => detail.value?.chunks.find(item => item.chunkNo === selectedChunkNo.value))
const canArchive = computed(() => document.value?.status === 'PUBLISHED'
  && detail.value?.versions.filter(item => item.status === 'PUBLISHED').length === 1)
const isLatestDraft = computed(() => document.value?.status === 'DRAFT'
  && detail.value?.versions[0]?.version === document.value.version)
const publishedVersion = computed(() => detail.value?.versions.find(item => item.status === 'PUBLISHED')?.version ?? 0)

watch(() => route.fullPath, () => {
  archiveTarget.value = undefined
  draftNotice.value = undefined
})

watch([() => route.params.docNo, () => route.query.version, () => route.query.chunk, attempt], ([docNo, version, fragment], _previous, invalidate) => {
  if (typeof docNo !== 'string') return
  const request = new AbortController()
  invalidate(() => request.abort())
  state.value = 'loading'
  readDocument(docNo, typeof version === 'string' ? version : undefined, request.signal).then((data) => {
    if (request.signal.aborted) return
    detail.value = data
    selectedChunkNo.value = data.chunks.find(item => String(item.chunkNo) === fragment)?.chunkNo ?? data.chunks[0]?.chunkNo
    state.value = 'ready'
  }).catch((error) => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    state.value = status === 404 ? 'missing' : status === 401 || status === 403 ? 'session' : 'error'
  })
}, { immediate: true })

function changeVersion(event: Event) {
  const version = (event.target as HTMLSelectElement).value
  void router.push({ name: 'admin-document', params: route.params, query: { version } })
}

function refreshArchive(scope: 'selected' | 'current', confirmed = false) {
  const target = archiveTarget.value
  archiveTarget.value = undefined
  if (!target) return
  if (confirmed) archivedVersion.value = { docNo: target.docNo, version: target.version }
  const version = scope === 'selected' ? String(target.version) : undefined
  if (route.query.version === version) attempt.value++
  else void router.replace({ name: 'admin-document', params: route.params, query: { version } })
}

function refreshDraft(message: string, issue = false) {
  draftNotice.value = message ? { message, issue } : undefined
  attempt.value++
}
</script>

<template>
  <RouterLink class="admin-text-link admin-detail-back" :to="{ name: 'admin-knowledge' }">
    <ArrowLeft :size="16" aria-hidden="true" /> 返回院内资料
  </RouterLink>
  <div v-if="state !== 'ready'" class="admin-panel admin-empty">
    <p v-if="state === 'loading'" role="status">正在加载版本详情…</p>
    <template v-else-if="state === 'missing'">
      <p role="alert">找不到这份资料或所选版本。</p>
      <RouterLink v-if="route.query.version" class="admin-button" :to="{ name: 'admin-document', params: route.params }">查看资料默认版本</RouterLink>
    </template>
    <template v-else-if="state === 'session'">
      <p role="alert">管理员会话已失效，请重新登录。</p>
      <RouterLink class="admin-button" :to="{ name: 'admin-login' }">重新登录</RouterLink>
    </template>
    <template v-else>
      <p role="alert">版本详情加载失败。</p>
      <button class="admin-button" type="button" @click="attempt++">重新加载版本</button>
    </template>
  </div>
  <template v-else-if="detail && document">
    <p v-if="draftNotice" :class="draftNotice.issue ? 'admin-form-error admin-draft-notice' : 'admin-archive-notice'"
       :role="draftNotice.issue ? 'alert' : 'status'">{{ draftNotice.message }}</p>
    <p v-if="archivedVersion?.docNo === document.docNo && archivedVersion.version === document.version
             && document.status === 'ARCHIVED'" class="admin-archive-notice" role="status">
      v{{ document.version }} 已下架。历史回答仍保留原有来源。
    </p>
    <div class="admin-detail-heading">
      <div><h1 class="admin-page-title">{{ document.title }}</h1><p class="admin-muted">{{ document.docNo }} · {{ documentTypeLabels[document.docType] }}</p></div>
      <label class="admin-version-switch">查看版本
        <select class="admin-input" :value="document.version" @change="changeVersion">
          <option v-for="option in detail.versions" :key="option.version" :value="option.version">
            v{{ option.version }} · {{ versionLabels[option.status] }}
          </option>
        </select>
      </label>
    </div>
    <section class="admin-panel admin-version-summary" aria-label="版本摘要">
      <div class="admin-version-status" :data-state="document.status">
        <span class="admin-tag" :data-state="document.status">v{{ document.version }} · {{ versionLabels[document.status] }}</span>
        <strong v-if="document.status === 'ARCHIVED'">此版本已归档，患者新问答不可引用</strong>
        <strong v-else-if="document.status === 'DRAFT'">草稿尚未发布，患者问答不可引用</strong>
        <strong v-else-if="document.indexed">{{ document.indexed }} 段可参与患者新问答检索</strong>
        <strong v-else>版本已发布，暂无可供患者检索的片段</strong>
        <button v-if="canArchive" class="admin-button admin-archive-trigger" type="button" @click="archiveTarget = document">
          <Archive :size="16" aria-hidden="true" /> 下架资料
        </button>
        <KnowledgeDraftActions v-if="isLatestDraft" :document="document" :published-version="publishedVersion"
                               @updated="refreshDraft" />
      </div>
      <dl class="admin-version-facts">
        <div><dt>来源</dt><dd>{{ document.source }}</dd></div>
        <div><dt>发布时间</dt><dd>{{ publishedTime(document.publishedAt) }}</dd></div>
        <div><dt>索引结果</dt><dd>{{ document.status === 'ARCHIVED' ? '曾索引' : '已索引' }} {{ document.indexed }} · 待索引 {{ document.pending }} · 失败 {{ document.failed }}</dd></div>
      </dl>
    </section>
    <section class="admin-panel admin-chunk-panel" aria-labelledby="knowledge-chunk-heading">
      <header class="admin-panel-toolbar">
        <h2 id="knowledge-chunk-heading">知识片段 <small class="admin-muted">{{ detail.chunks.length }} 段</small></h2>
      </header>
      <div v-if="!chunk" class="admin-empty" role="status">这个版本暂无片段。</div>
      <div v-else class="admin-chunks">
        <ul class="admin-chunk-list" aria-label="按切分顺序查看片段">
          <li v-for="item in detail.chunks" :key="item.chunkNo">
            <button type="button" :aria-pressed="selectedChunkNo === item.chunkNo" @click="selectedChunkNo = item.chunkNo">
              <span class="admin-chunk-index">{{ String(item.chunkNo).padStart(2, '0') }}</span>
              <span><strong>{{ item.heading || '无章节标题' }}</strong>
                <small :data-state="item.embeddingStatus">{{ document.status === 'ARCHIVED' && item.embeddingStatus === 'INDEXED' ? '曾索引' : indexLabels[item.embeddingStatus] }}</small>
              </span>
              <ChevronRight :size="15" aria-hidden="true" />
            </button>
          </li>
        </ul>
        <article class="admin-chunk-content" aria-label="所选片段">
          <header>
            <span class="admin-muted">v{{ document.version }} · 片段 {{ String(chunk.chunkNo).padStart(2, '0') }}</span>
            <span v-if="chunk.retrievable" class="admin-tag" data-state="INDEXED">可参与检索</span>
            <span v-else-if="document.status === 'ARCHIVED'" class="admin-tag">不可引用 · 版本已归档</span>
            <span v-else-if="document.status === 'DRAFT'" class="admin-tag">不可引用 · 草稿未发布</span>
            <span v-else class="admin-tag" :data-state="chunk.embeddingStatus">暂不可引用 · {{ indexLabels[chunk.embeddingStatus] }}</span>
          </header>
          <h3>{{ chunk.heading || '无章节标题' }}</h3>
          <p class="admin-muted">{{ chunk.charCount }} 字符</p>
          <p class="admin-chunk-text">{{ chunk.content }}</p>
          <div v-if="chunk.failureReason" class="admin-form-error">
            <strong>索引失败原因</strong><p>{{ chunk.failureReason }}</p>
          </div>
        </article>
      </div>
    </section>
  </template>
  <KnowledgeArchiveDialog v-if="archiveTarget" :version="archiveTarget" @close="archiveTarget = undefined"
                          @archived="refreshArchive('selected', true)" @refresh="refreshArchive" />
</template>
