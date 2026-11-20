<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import { ArrowRight, FileUp, Search } from 'lucide-vue-next'
import { adminHttpStatus, readDocuments, type DocumentRow } from '../api/admin'
import { documentTypeLabels, publishedTime, versionLabels } from './knowledgeLabels'

const rows = ref<DocumentRow[]>([])
const search = ref('')
const attempt = ref(0)
const state = ref<'loading' | 'ready' | 'error' | 'session'>('loading')
const matches = computed(() => {
  const query = search.value.trim().toLowerCase()
  return rows.value.filter(({ document }) => document.title.toLowerCase().includes(query)
    || document.docNo.toLowerCase().includes(query))
})

watchEffect((invalidate) => {
  void attempt.value
  const request = new AbortController()
  invalidate(() => request.abort())
  state.value = 'loading'
  readDocuments(request.signal).then((data) => {
    if (request.signal.aborted) return
    rows.value = data
    state.value = 'ready'
  }).catch((error) => {
    if (request.signal.aborted) return
    const status = adminHttpStatus(error)
    state.value = status === 401 || status === 403 ? 'session' : 'error'
  })
})
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">院内资料</h1>
    <RouterLink class="admin-button admin-button--primary" :to="{ name: 'admin-knowledge-import' }">
      <FileUp :size="17" aria-hidden="true" /> 导入预览
    </RouterLink>
  </header>
  <section class="admin-panel" aria-label="院内资料列表">
    <div class="admin-panel-toolbar">
      <label class="admin-search">
        <Search :size="17" aria-hidden="true" />
        <input v-model="search" type="search" aria-label="搜索资料标题或编号" placeholder="搜索资料标题或编号" />
      </label>
      <span v-if="state === 'ready'" class="admin-muted">{{ matches.length }} 份资料</span>
    </div>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在加载院内资料…</p>
    <div v-else-if="state === 'error'" class="admin-empty" role="alert">
      <p>资料列表加载失败。</p>
      <button class="admin-button" type="button" @click="attempt++">重新加载资料</button>
    </div>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      <p>管理员会话已失效，请重新登录。</p>
      <RouterLink class="admin-button" :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
    </div>
    <div v-else-if="!matches.length" class="admin-empty" role="status">
      {{ rows.length ? '没有找到符合搜索条件的资料。' : '暂无院内资料。' }}
    </div>
    <div v-else class="admin-table-scroll" role="region" aria-label="资料表格" tabindex="0">
      <table class="admin-table">
        <thead><tr><th scope="col">资料</th><th scope="col">版本</th><th scope="col">发布状态</th>
          <th scope="col">索引结果</th><th scope="col">患者引用</th><th scope="col">发布时间</th><th scope="col">操作</th></tr></thead>
        <tbody>
          <tr v-for="{ document, versionCount, draftVersion } in matches" :key="document.docNo">
            <td><strong>{{ document.title }}</strong><small>{{ documentTypeLabels[document.docType] }} · {{ document.docNo }}</small></td>
            <td>v{{ document.version }}<small>共 {{ versionCount }} 版</small></td>
            <td><span class="admin-tag" :data-state="document.status">{{ versionLabels[document.status] }}</span></td>
            <td>{{ document.status === 'ARCHIVED' ? '曾索引' : '已索引' }} {{ document.indexed }}
              <small>待索引 {{ document.pending }} · <span :class="{ 'admin-negative': document.failed }">失败 {{ document.failed }}</span></small>
            </td>
            <td v-if="document.status === 'PUBLISHED' && document.indexed">
              <span class="admin-positive">{{ document.indexed }} 段可参与检索</span>
            </td>
            <td v-else><span class="admin-muted">不可引用</span></td>
            <td>{{ publishedTime(document.publishedAt) }}</td>
            <td>
              <RouterLink class="admin-text-link" :to="{ name: 'admin-document', params: { docNo: document.docNo } }"
                          :aria-label="'查看版本 ' + document.title">查看版本 <ArrowRight :size="14" aria-hidden="true" /></RouterLink>
              <small v-if="draftVersion && draftVersion !== document.version">
                <RouterLink class="admin-text-link" :to="{ name: 'admin-document', params: { docNo: document.docNo }, query: { version: draftVersion } }">
                  继续草稿 v{{ draftVersion }}
                </RouterLink>
              </small>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
