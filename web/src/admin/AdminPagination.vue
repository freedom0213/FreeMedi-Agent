<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'

const props = defineProps<{ page: number; totalPages?: number; hasNext?: boolean; busy?: boolean }>()
const emit = defineEmits<{ change: [page: number] }>()
const bar = ref<HTMLElement>()
const last = computed(() => props.totalPages ?? props.page + Number(!!props.hasNext))
const end = computed(() => Math.min(last.value, Math.max(4, props.page + 1)))
const start = computed(() => Math.max(1, Math.min(end.value - 3, props.page - 1)))
const pages = computed(() => {
  const adjacent = Array.from({ length: Math.max(0, end.value - start.value + 1) }, (_, index) => start.value + index)
  return [...new Set([1, ...adjacent, last.value].filter(page => page > 0))]
})

function go(page: number) {
  if (page === props.page || props.busy || page < 1 || page > last.value) return
  bar.value?.parentElement?.scrollIntoView({ block: 'start' })
  emit('change', page)
}
</script>

<template>
  <nav ref="bar" class="table-pagination" aria-label="表格分页">
    <button type="button" aria-label="上一页" :disabled="busy || page <= 1" @click="go(page - 1)"><ChevronLeft :size="17" aria-hidden="true" /></button>
    <template v-for="(number, index) in pages" :key="number">
      <span v-if="index > 0 && number - (pages[index - 1] ?? 0) > 1" aria-hidden="true">…</span>
      <button type="button" :aria-label="`第 ${number} 页`" :aria-current="page === number ? 'page' : undefined"
        :disabled="busy" @click="go(number)">{{ number }}</button>
    </template>
    <button type="button" aria-label="下一页" :disabled="busy || page >= last" @click="go(page + 1)"><ChevronRight :size="17" aria-hidden="true" /></button>
  </nav>
</template>

<style scoped>
.table-pagination {
  position: sticky; bottom: 6px; z-index: 2; display: flex; flex-wrap: wrap; align-items: center; justify-content: center;
  width: fit-content; max-width: calc(100% - 12px); gap: 4px; margin: 0 auto 8px; padding: 4px 6px;
  border: 1px solid var(--admin-border); border-radius: 8px; background: #fff; box-shadow: 0 4px 15px #203b6026;
}
.table-pagination button { display: grid; place-items: center; min-width: 34px; min-height: 36px; padding: 2px 5px;
  border: 1px solid transparent; border-radius: 5px; background: transparent; color: var(--admin-ink); font: inherit; cursor: pointer; }
.table-pagination button[aria-current] { background: var(--admin-blue); color: #fff; }
.table-pagination button:enabled:not([aria-current]):hover { background: #ebf3ff; }
.table-pagination button:disabled { opacity: .48; cursor: default; }
.table-pagination span { color: var(--admin-muted); }
</style>
