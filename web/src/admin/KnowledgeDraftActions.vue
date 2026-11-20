<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { indexKnowledgeDraft, publishKnowledgeDraft, type DocumentVersion } from '../api/admin'

const props = defineProps<{ document: DocumentVersion; publishedVersion: number }>()
const emit = defineEmits<{ updated: [message: string, issue?: boolean] }>()
const dialog = ref<HTMLDialogElement>()
const indexing = ref(false)
const publishing = ref(false)
const progress = ref(0)
const issue = ref('')
const indexReady = computed(() => props.document.indexed > 0 && !props.document.pending && !props.document.failed)
let indexRequest: AbortController | undefined
let mounted = true

onBeforeUnmount(() => {
  mounted = false
  indexRequest?.abort()
  dialog.value?.close()
})

async function indexDraft() {
  if (indexing.value || publishing.value) return
  const request = new AbortController()
  indexRequest = request
  indexing.value = true
  progress.value = 0
  const total = props.document.pending + props.document.failed
  for (let step = 0; step < total; step++) {
    const outcome = await indexKnowledgeDraft(props.document, request.signal)
    if (request.signal.aborted) return
    if (outcome.kind === 'issue') {
      emit('updated', outcome.message, true)
      return
    }
    if (outcome.data.failed) {
      emit('updated', '索引未完成。请检查失败片段后重试。', true)
      return
    }
    progress.value++
    if (outcome.data.finished) {
      emit('updated', '草稿索引已完成，可以核对并发布。')
      return
    }
  }
  emit('updated', '索引进度已更新。')
}

async function openPublish() {
  issue.value = ''
  await nextTick()
  dialog.value?.showModal()
}

function closePublish() {
  if (!publishing.value) dialog.value?.close()
}

async function publish() {
  if (publishing.value || issue.value || !indexReady.value) return
  publishing.value = true
  const outcome = await publishKnowledgeDraft(props.document, props.publishedVersion)
  if (!mounted) return
  publishing.value = false
  if (outcome.kind === 'issue') issue.value = outcome.message
  else {
    closePublish()
    emit('updated', `v${props.document.version} 已发布。患者的新问答可引用新版。`)
  }
}
</script>

<template>
  <div class="admin-draft-actions">
    <button v-if="!indexReady" type="button" class="admin-button admin-button--primary" :disabled="indexing" @click="indexDraft">
      {{ indexing ? '正在建立索引…' : document.failed ? '重试索引' : '建立索引' }}
    </button>
    <button v-else type="button" class="admin-button admin-button--primary" @click="openPublish">发布新版</button>
    <span v-if="indexing" role="status" class="admin-muted">本次已处理 {{ progress }} 段</span>
  </div>
  <dialog ref="dialog" class="admin-archive-dialog" aria-labelledby="admin-publish-title" @cancel.prevent="closePublish">
    <h2 id="admin-publish-title">确认发布新版</h2>
    <dl class="admin-archive-facts">
      <dt>资料</dt><dd>{{ document.title }} <span>{{ document.docNo }}</span></dd>
      <dt>即将发布</dt><dd>v{{ document.version }} · {{ document.indexed }} 段已索引</dd>
      <dt>即将归档</dt><dd>{{ publishedVersion ? 'v' + publishedVersion : '无 · 首次发布' }}</dd>
    </dl>
    <p>发布后，患者的新问答可以引用新版。历史回答仍可展开作答时的来源。</p>
    <p v-if="issue" class="admin-form-error" role="alert">{{ issue }}</p>
    <footer>
      <button type="button" class="admin-button" :disabled="publishing" autofocus @click="closePublish">{{ issue ? '关闭' : '取消' }}</button>
      <button v-if="issue" type="button" class="admin-button admin-button--primary" @click="emit('updated', '')">重新读取版本</button>
      <button v-else type="button" class="admin-button admin-button--primary" :disabled="publishing" @click="publish">
        {{ publishing ? '正在发布…' : '确认发布' }}
      </button>
    </footer>
  </dialog>
</template>
