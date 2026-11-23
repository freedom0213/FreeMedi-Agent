import { ref, shallowRef, watchEffect } from 'vue'
import { adminHttpStatus } from '../api/admin'

export function useKnowledgeRead<T>(load: (signal: AbortSignal) => Promise<T>) {
  const data = shallowRef<T>()
  const state = ref<'loading' | 'ready' | 'error' | 'session'>('loading')
  const attempt = ref(0)
  watchEffect(onCleanup => {
    void attempt.value
    const request = new AbortController()
    onCleanup(() => request.abort())
    state.value = 'loading'
    load(request.signal).then(result => {
      if (request.signal.aborted) return
      data.value = result
      state.value = 'ready'
    }).catch(error => {
      if (request.signal.aborted) return
      const status = adminHttpStatus(error)
      state.value = status === 401 || status === 403 ? 'session' : 'error'
    })
  })
  return { data, state, reload: () => { attempt.value++ } }
}
