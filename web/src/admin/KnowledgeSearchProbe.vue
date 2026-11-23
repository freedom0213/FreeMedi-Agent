<script setup lang="ts">
import { ref, shallowRef } from 'vue'
import { agentSettingsError, probeKnowledge, type DocumentType, type KnowledgeProbe } from '../api/admin'
import { documentTypeLabels, knowledgeFragmentLink } from './knowledgeLabels'

const query = ref('')
const category = ref<DocumentType | ''>('')
const topK = ref(5)
const result = shallowRef<KnowledgeProbe>()
const testedQuery = ref('')
const busy = ref(false)
const error = ref('')
const score = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 })

async function search() {
  const input = query.value.trim()
  if (!input || busy.value) return
  busy.value = true
  error.value = ''
  result.value = undefined
  try {
    result.value = await probeKnowledge(input, category.value || undefined, topK.value)
    testedQuery.value = input
  } catch (failure) {
    error.value = agentSettingsError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="admin-panel" aria-labelledby="knowledge-probe-heading">
    <header class="admin-panel-toolbar">
      <div>
        <h2 id="knowledge-probe-heading">语义检索测试</h2>
        <p class="knowledge-caption admin-muted">从已发布的片段中找相近内容，分数表示相似度。</p>
      </div>
    </header>
    <form class="catalog-filter knowledge-probe-form" @submit.prevent="search">
      <label>检索问题<input v-model="query" class="admin-input" type="search" required maxlength="500"
        placeholder="例如：就诊时需要携带哪些证件？"></label>
      <label>检索类型<select v-model="category" class="admin-input">
        <option value="">全部类型</option>
        <option v-for="(label, type) in documentTypeLabels" :key="type" :value="type">{{ label }}</option>
      </select></label>
      <label>返回条数<input v-model.number="topK" class="admin-input" type="number" required min="1" max="10"></label>
      <button class="admin-button admin-button--primary" :disabled="busy || !query.trim()">{{ busy ? '正在检索…' : '试搜' }}</button>
    </form>
    <p v-if="busy" class="admin-form-hint catalog-message" role="status">正在检索已发布片段…</p>
    <p v-else-if="error" class="admin-form-error catalog-message" role="alert">{{ error }}</p>
    <p v-else-if="result && !result.indexAvailable" class="admin-form-error catalog-message" role="alert">
      {{ result.unavailableReason ?? '向量索引当前不可用' }}。请恢复后重试。
    </p>
    <template v-else-if="result">
      <p v-if="!result.hits.length" class="admin-empty" role="status">「{{ testedQuery }}」没有命中已发布片段。</p>
      <div v-else class="knowledge-hits">
        <p role="status">「{{ testedQuery }}」命中 {{ result.hits.length }} 段</p>
        <ol>
          <li v-for="hit in result.hits" :key="hit.id">
            <details><summary><strong>{{ hit.heading || hit.title }}</strong><span>相似度 {{ score.format(hit.score) }}</span></summary>
              <p class="admin-muted">{{ hit.title }} · v{{ hit.version }} · {{ hit.source }}</p>
              <p class="knowledge-excerpt">{{ hit.content }}</p>
              <RouterLink class="admin-text-link" :to="knowledgeFragmentLink(hit)">查看来源版本</RouterLink>
            </details>
          </li>
        </ol>
      </div>
    </template>
  </section>
</template>
