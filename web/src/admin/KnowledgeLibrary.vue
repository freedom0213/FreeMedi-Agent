<script setup lang="ts">
import { readKnowledgeLibrary } from '../api/admin'
import { documentTypeLabels } from './knowledgeLabels'
import { useKnowledgeRead } from './useKnowledgeRead'

const { data, state, reload } = useKnowledgeRead(readKnowledgeLibrary)
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">医学知识库</h1>
    <RouterLink class="admin-button" :to="{ name: 'admin-knowledge' }">查看全部文档</RouterLink>
  </header>
  <section class="admin-panel" aria-labelledby="knowledge-library-categories">
    <header class="admin-panel-toolbar">
      <div>
        <h2 id="knowledge-library-categories">按资料类型查看</h2>
        <p class="knowledge-caption admin-muted">发布状态按文档版本统计，索引状态只统计已发布版本的片段。</p>
      </div>
    </header>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取知识库…</p>
    <div v-else-if="state === 'error'" class="admin-empty" role="alert">
      知识库加载失败。<button class="admin-button" type="button" @click="reload">重新加载</button>
    </div>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      管理员会话已失效。<RouterLink :to="{ name: 'admin-login' }">重新登录</RouterLink>
    </div>
    <p v-else-if="!data?.length" class="admin-empty">暂无知识文档，可先从知识文档页导入。</p>
    <div v-else class="admin-table-scroll" role="region" aria-label="知识分类统计" tabindex="0">
      <table class="admin-table catalog-table">
        <thead><tr><th scope="col">类型</th><th scope="col">文档</th><th scope="col">版本</th>
          <th scope="col">已发布片段的索引状态</th><th scope="col">查看</th></tr></thead>
        <tbody><tr v-for="row in data" :key="row.docType">
          <th scope="row">{{ documentTypeLabels[row.docType] }}</th>
          <td>{{ row.documents }} 份</td>
          <td>{{ row.versions }} 版<small>发布 {{ row.published }} · 草稿 {{ row.drafts }} · 归档 {{ row.archived }}</small></td>
          <td>已索引 {{ row.indexed }} · 待索引 {{ row.pending }} ·
            <span :class="{ 'admin-negative': row.failed > 0 }">失败 {{ row.failed }}</span>
          </td>
          <td><div class="catalog-actions">
            <RouterLink class="admin-text-link" :to="{ name: 'admin-knowledge', query: { type: row.docType } }">文档</RouterLink>
            <RouterLink class="admin-text-link" :to="{ name: 'admin-knowledge-chunks', query: { type: row.docType } }">片段</RouterLink>
            <RouterLink class="admin-text-link" :to="{ name: 'admin-knowledge-index', query: { type: row.docType } }">索引</RouterLink>
          </div></td>
        </tr></tbody>
      </table>
    </div>
  </section>
</template>
