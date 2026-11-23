import { computed, ref, shallowRef, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminHttpStatus, readAdminAgentTrace, readRecentAgentTasks, type AgentTaskSummary, type AgentTracePage } from '../api/admin'

export type TaskTraceRoute = 'admin-tool-logs' | 'admin-agent-timeline'

export function useAdminTaskTrace(routeName: TaskTraceRoute) {
  const route = useRoute()
  const router = useRouter()
  const recent = shallowRef<AgentTaskSummary[]>([])
  const defaultTaskId = ref('')
  const trace = shallowRef<AgentTracePage>()
  const search = ref('')
  const recentLoading = ref(true)
  const recentIssue = ref('')
  const detailLoading = ref(false)
  const detailIssue = ref('')
  const moreLoading = ref(false)
  const moreIssue = ref(false)
  const refresh = ref(0)
  const explicitId = computed(() => typeof route.query.taskId === 'string' ? route.query.taskId : '')
  const taskId = computed(() => explicitId.value || defaultTaskId.value)
  let detailRequest: AbortController | undefined

  watch(explicitId, value => { search.value = value }, { immediate: true })

  watchEffect((invalidate) => {
    void refresh.value
    const request = new AbortController()
    invalidate(() => request.abort())
    recentLoading.value = true
    recentIssue.value = ''
    readRecentAgentTasks(request.signal).then((tasks) => {
      if (!request.signal.aborted) {
        recent.value = tasks
        if (!defaultTaskId.value) defaultTaskId.value = tasks[0]?.taskId ?? ''
      }
    }).catch((error) => {
      if (!request.signal.aborted) {
        const status = adminHttpStatus(error)
        recentIssue.value = status === 401 || status === 403 ? '管理员会话已失效。' : '最近任务加载失败。'
      }
    }).finally(() => { if (!request.signal.aborted) recentLoading.value = false })
  })

  watch([taskId, refresh], ([id], _, invalidate) => {
    const request = new AbortController()
    detailRequest = request
    invalidate(() => request.abort())
    trace.value = undefined
    detailIssue.value = ''
    moreLoading.value = false
    moreIssue.value = false
    detailLoading.value = !!id
    if (!id) return
    if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(id)) {
      detailIssue.value = '请输入有效的任务编号。'
      detailLoading.value = false
      return
    }
    readAdminAgentTrace(id, 0, request.signal).then((value) => {
      if (!request.signal.aborted) trace.value = value
    }).catch((error) => {
      if (request.signal.aborted) return
      const status = adminHttpStatus(error)
      detailIssue.value = status === 404 ? '找不到这条任务，请核对任务编号。'
        : status === 401 || status === 403 ? '管理员会话已失效。' : '追踪详情加载失败。'
    }).finally(() => { if (!request.signal.aborted) detailLoading.value = false })
  }, { immediate: true })

  function locate() {
    const id = search.value.trim()
    if (id === explicitId.value) refresh.value++
    else void router.push({ name: routeName, query: id ? { taskId: id } : {} })
  }

  async function loadMore() {
    const current = trace.value
    const request = detailRequest
    if (!current?.nextAfter || !request || moreLoading.value) return
    moreIssue.value = false
    moreLoading.value = true
    try {
      const next = await readAdminAgentTrace(current.task.taskId, current.nextAfter, request.signal)
      if (!request.signal.aborted) trace.value = { ...next, entries: [...current.entries, ...next.entries] }
    } catch {
      if (!request.signal.aborted) moreIssue.value = true
    } finally {
      if (!request.signal.aborted) moreLoading.value = false
    }
  }

  return { recent, trace, search, taskId, recentLoading, recentIssue, detailLoading, detailIssue,
    moreLoading, moreIssue, refresh, locate, loadMore }
}
