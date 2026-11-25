<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { archiveDocument, type DocumentVersion } from '../api/admin'

const props = defineProps<{ version: DocumentVersion }>()
const emit = defineEmits<{
  close: []
  archived: []
  refresh: [scope: 'selected' | 'current']
}>()
const dialog = ref<HTMLDialogElement>()
const submitting = ref(false)
const error = ref<'conflict' | 'session' | 'unknown'>()

onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())

function close() {
  if (!submitting.value) emit('close')
}

async function submit() {
  if (submitting.value || error.value) return
  submitting.value = true
  const result = await archiveDocument(props.version.docNo, props.version.version)
  submitting.value = false
  if (result === 'archived') emit('archived')
  else error.value = result
}
</script>

<template>
  <dialog ref="dialog" class="admin-archive-dialog" aria-labelledby="admin-archive-title"
          aria-describedby="admin-archive-history" @cancel.prevent="close">
    <h2 id="admin-archive-title">确认下架资料</h2>
    <dl class="admin-archive-facts">
      <dt>资料</dt><dd>{{ version.title }} <span>{{ version.docNo }}</span></dd>
      <dt>当前发布版本</dt><dd>v{{ version.version }}</dd>
      <dt>受影响片段</dt><dd>{{ version.indexed }} 段已索引片段将退出新问答检索</dd>
    </dl>
    <p id="admin-archive-history">下架后，患者的新问答不会引用此版本。历史回答仍可展开原有来源，管理端仍可查看此版本和片段正文。</p>
    <p v-if="error === 'conflict'" class="admin-form-error" role="alert">发布状态已变化。请查看当前版本并重新确认。</p>
    <p v-else-if="error === 'session'" class="admin-form-error" role="alert">管理员会话已失效，请重新登录。</p>
    <p v-else-if="error === 'unknown'" class="admin-form-error" role="alert">暂时无法确认下架结果，请先核验此版本的状态。</p>
    <footer>
      <button class="admin-button" type="button" autofocus :disabled="submitting" @click="close">
        {{ error ? '关闭' : '取消' }}
      </button>
      <button v-if="error === 'conflict'" class="admin-button admin-button--primary" type="button" @click="emit('refresh', 'current')">查看当前版本</button>
      <button v-else-if="error === 'unknown'" class="admin-button admin-button--primary" type="button" @click="emit('refresh', 'selected')">核验下架结果</button>
      <RouterLink v-else-if="error === 'session'" class="admin-button admin-button--primary"
                  :to="{ name: 'admin-login' }">重新登录</RouterLink>
      <button v-else class="admin-button admin-button--danger" type="button" :disabled="submitting" @click="submit">
        {{ submitting ? '正在下架…' : '确认下架' }}
      </button>
    </footer>
  </dialog>
</template>
