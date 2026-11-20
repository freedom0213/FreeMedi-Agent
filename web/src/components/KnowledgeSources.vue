<script setup lang="ts">
import type { KnowledgeSource } from '../types/agent'

defineProps<{ sources: KnowledgeSource[] }>()
</script>

<template>
  <section v-if="sources.length" class="knowledge-sources" aria-label="资料来源">
    <div class="sources-label">资料来源 <span>{{ sources.length }} 段</span></div>
    <details v-for="source in sources" :key="source.chunkId" class="source">
      <summary>{{ source.title }}<span v-if="source.heading"> · {{ source.heading }}</span></summary>
      <div class="source-body">
        <p class="source-meta">{{ source.source }} · {{ source.docNo }} v{{ source.version }}</p>
        <blockquote>{{ source.content }}</blockquote>
      </div>
    </details>
  </section>
</template>

<style scoped>
.knowledge-sources { display: grid; gap: 7px; margin-top: 12px; }
.sources-label { color: var(--ink-soft); font-size: 12px; font-weight: 600; }
.sources-label span { margin-left: 4px; color: var(--ink-muted); font-weight: 400; }
.source { border: 1px solid var(--line-strong); border-radius: 6px; background: var(--paper); font-size: 12px; }
summary { padding: 9px 11px; color: var(--teal-dark); cursor: pointer; overflow-wrap: anywhere; }
.source-body { padding: 0 11px 11px; }
.source-body p.source-meta { margin: 0 0 7px; color: var(--ink-muted); font-size: 11px; }
blockquote { margin: 0; padding-left: 9px; border-left: 2px solid var(--line-strong); color: var(--ink-soft); line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
