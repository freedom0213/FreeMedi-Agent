import { ref, shallowRef, watch } from 'vue'
import { adminHttpStatus, readCatalog, type CatalogKind } from '../api/admin'

type Filters = Parameters<typeof readCatalog>[1]

export function useCatalogPage<T>(kind: CatalogKind | 'schedules', initial: Omit<Filters, 'page'> = {}) {
  const items = shallowRef<T[]>([])
  const filters = shallowRef<Filters>({ ...initial, page: 1 })
  const hasNext = ref(false)
  const totalPages = ref<number>()
  const state = ref<'loading' | 'ready' | 'failed' | 'session'>('loading')
  const attempt = ref(0)

  watch([filters, attempt], ([query], _, onCleanup) => {
    const request = new AbortController()
    onCleanup(() => request.abort())
    state.value = 'loading'
    readCatalog<T>(kind, query, request.signal).then(result => {
      if (request.signal.aborted) return
      items.value = result.items
      hasNext.value = result.hasNext
      totalPages.value = result.totalPages
      if (query.page > Math.max(1, result.totalPages ?? query.page)) {
        go(Math.max(1, result.totalPages ?? 1))
        return
      }
      state.value = 'ready'
    }).catch(error => {
      if (request.signal.aborted) return
      const status = adminHttpStatus(error)
      state.value = status === 401 || status === 403 ? 'session' : 'failed'
    })
  }, { immediate: true })

  const reload = () => { attempt.value += 1 }
  const go = (page: number) => { filters.value = { ...filters.value, page } }
  const search = (query: Omit<Filters, 'page'>) => { filters.value = { ...query, page: 1 } }
  return { items, filters, hasNext, totalPages, state, reload, go, search }
}
