<script setup lang="ts">
import { readKnowledgeIndexSummary } from '../api/admin'
import { useKnowledgeRead } from './useKnowledgeRead'

const { data, state, reload } = useKnowledgeRead(readKnowledgeIndexSummary)
defineExpose({ reload })
const stores = { QDRANT: 'Qdrant', MEMORY: '本地内存', DISABLED: '未启用' }
const statuses = { REACHABLE: '可连接', UNAVAILABLE: '暂不可用', DISABLED: '未启用' }
</script>

<template>
  <section class="admin-panel knowledge-index-status" aria-labelledby="knowledge-current-embedder">
    <header class="admin-panel-toolbar">
      <h2 id="knowledge-current-embedder">当前向量模型</h2>
      <button class="admin-button" type="button" :disabled="state === 'loading'" @click="reload">刷新状态</button>
    </header>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取向量模型和索引状态…</p>
    <p v-else-if="state === 'error'" class="admin-empty" role="alert">向量模型状态读取失败，请刷新重试。</p>
    <p v-else-if="state === 'session'" class="admin-empty" role="alert">
      管理员会话已失效。<RouterLink :to="{ name: 'admin-login' }">重新登录</RouterLink>
    </p>
    <template v-else-if="data">
      <dl class="knowledge-index-facts">
        <div><dt>模型</dt><dd><code>{{ data.model }}</code></dd></div>
        <div><dt>运行方式</dt><dd>{{ data.provider === 'LOCAL' ? '本地模型' : '远程 API' }}</dd></div>
        <div><dt>维度</dt><dd>{{ data.dimensions }} 维</dd></div>
        <div><dt>向量库</dt><dd>{{ stores[data.store] }} · {{ statuses[data.status] }}</dd></div>
        <div v-if="data.collection"><dt>当前集合</dt><dd><code>{{ data.collection }}</code></dd></div>
        <div v-if="data.pointCount !== null"><dt>向量库记录数</dt><dd>{{ data.pointCount }}</dd></div>
      </dl>
      <p class="knowledge-index-note admin-muted">向量配置由服务端加载。向量库记录数可能包含草稿和归档版本。</p>
    </template>
  </section>
</template>
