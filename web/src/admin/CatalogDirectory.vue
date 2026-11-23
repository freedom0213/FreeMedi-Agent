<script setup lang="ts">
import { onMounted, onUnmounted, ref, shallowRef } from 'vue'
import {
  agentSettingsError, readCatalogOptions, saveCatalogEntry, setCatalogActive,
  type CatalogKind, type CatalogOption, type DepartmentRow, type DoctorRow,
} from '../api/admin'
import { useCatalogPage } from './useCatalogPage'
import AdminPagination from './AdminPagination.vue'

const props = defineProps<{ kind: CatalogKind }>()
const doctorPage = props.kind === 'doctors'
const label = doctorPage ? '医生' : '科室'
const { items, filters, hasNext, totalPages, state, reload, go, search } = useCatalogPage<DepartmentRow | DoctorRow>(props.kind)
const q = ref('')
const status = ref('')
const departmentId = ref<number | ''>('')
const departments = shallowRef<CatalogOption[]>([])
const optionError = ref('')
const optionsRequest = new AbortController()
onUnmounted(() => optionsRequest.abort())
onMounted(() => {
  if (!doctorPage) return
  void readCatalogOptions('departments', optionsRequest.signal).then(result => {
    departments.value = result
  }).catch(error => {
    if (!optionsRequest.signal.aborted) optionError.value = agentSettingsError(error)
  })
})

const busy = ref(false)
const feedback = ref('')
const problem = ref('')
const target = ref<DepartmentRow | DoctorRow>()
const draft = ref<{ id?: number; code: string; name: string; description: string;
  departmentId: number | ''; title: string; specialty: string; introduction: string }>()

function edit(row?: DepartmentRow | DoctorRow) {
  problem.value = ''
  feedback.value = ''
  target.value = undefined
  const department = row && 'code' in row ? row : undefined
  const doctor = row && 'departmentId' in row ? row : undefined
  draft.value = {
    id: row?.id, code: department?.code ?? '', name: row?.name ?? '', description: department?.description ?? '',
    departmentId: doctor?.departmentId ?? '', title: doctor?.title ?? '', specialty: doctor?.specialty ?? '',
    introduction: doctor?.introduction ?? '',
  }
}

async function save() {
  if (!draft.value || busy.value) return
  const item = draft.value
  const body = doctorPage
    ? { name: item.name, title: item.title, specialty: item.specialty, introduction: item.introduction,
      ...(item.id === undefined ? { departmentId: item.departmentId } : {}) }
    : { name: item.name, description: item.description, ...(item.id === undefined ? { code: item.code } : {}) }
  busy.value = true
  problem.value = ''
  try {
    await saveCatalogEntry(props.kind, item.id, body)
    feedback.value = label + '已保存。'
    draft.value = undefined
    if (item.id === undefined) {
      q.value = item.name
      status.value = ''
      departmentId.value = ''
      search({ q: item.name })
    } else reload()
  } catch (error) {
    problem.value = agentSettingsError(error)
  } finally {
    busy.value = false
  }
}

function confirmStatus(row: DepartmentRow | DoctorRow) {
  problem.value = ''
  feedback.value = ''
  draft.value = undefined
  target.value = row
}

async function changeStatus() {
  if (!target.value || busy.value) return
  const row = target.value
  const active = row.status !== 'ACTIVE'
  busy.value = true
  problem.value = ''
  try {
    await setCatalogActive(props.kind, row.id, active)
    feedback.value = row.name + (active ? '已启用。' : '已停用。')
    target.value = undefined
    reload()
  } catch (error) {
    problem.value = agentSettingsError(error)
    reload()
  } finally {
    busy.value = false
  }
}

function clearFilters() {
  q.value = ''
  status.value = ''
  departmentId.value = ''
  search({})
}
</script>

<template>
  <h1 class="admin-page-title">{{ label }}管理</h1>
  <section class="admin-panel" :aria-label="label + '列表'">
    <form class="catalog-filter" @submit.prevent="search({ q: q.trim() || undefined, status: status || undefined, departmentId: departmentId || undefined })">
      <label>{{ doctorPage ? '医生姓名' : '科室名称或编码' }}<input v-model="q" class="admin-input" maxlength="100" type="search"></label>
      <label v-if="doctorPage">所属科室
        <select v-model="departmentId" class="admin-input">
          <option value="">全部科室</option>
          <option v-for="department in departments" :key="department.id" :value="department.id">{{ department.name }}</option>
        </select>
      </label>
      <label>状态
        <select v-model="status" class="admin-input"><option value="">全部状态</option><option value="ACTIVE">已启用</option><option value="DISABLED">已停用</option></select>
      </label>
      <div class="catalog-actions">
        <button class="admin-button">查询</button>
        <button class="admin-button" type="button" @click="clearFilters">重置</button>
      </div>
      <button class="admin-button admin-button--primary catalog-create" type="button" :disabled="busy" @click="edit()">新增{{ label }}</button>
    </form>
    <p v-if="optionError" class="admin-form-error catalog-message" role="alert">科室选项加载失败。{{ optionError }}</p>
    <p v-if="feedback" class="admin-form-hint catalog-message" role="status">{{ feedback }}</p>
    <p v-if="problem" class="admin-form-error catalog-message" role="alert">{{ problem }}</p>
    <form v-if="draft" class="catalog-editor" @submit.prevent="save">
      <h2>{{ draft.id === undefined ? '新增' : '编辑' }}{{ label }}</h2>
      <fieldset :disabled="busy" class="catalog-fields">
        <label v-if="!doctorPage">科室编码
          <input v-model="draft.code" class="admin-input" :readonly="draft.id !== undefined"
            required maxlength="48" pattern="[A-Za-z0-9_\-]+" aria-describedby="catalog-code-hint">
          <small id="catalog-code-hint" class="admin-muted">可用字母、数字、下划线和短横线，保存后固定。</small>
        </label>
        <label v-else>所属科室
          <select v-model="draft.departmentId" class="admin-input" :disabled="draft.id !== undefined" required>
            <option value="" disabled>选择科室</option>
            <option v-for="department in departments" :key="department.id" :value="department.id"
              :disabled="department.status !== 'ACTIVE'">{{ department.name }}{{ department.status === 'DISABLED' ? '（已停用）' : '' }}</option>
          </select>
        </label>
        <label>{{ label }}名称<input v-model.trim="draft.name" class="admin-input" required maxlength="100"></label>
        <template v-if="doctorPage">
          <label>职称<input v-model.trim="draft.title" class="admin-input" maxlength="80"></label>
          <label>专长<input v-model.trim="draft.specialty" class="admin-input" maxlength="500"></label>
          <label class="catalog-wide">简介<textarea v-model.trim="draft.introduction" class="admin-input" rows="3" maxlength="3000"></textarea></label>
        </template>
        <label v-else class="catalog-wide">简介<textarea v-model.trim="draft.description" class="admin-input" rows="3" maxlength="3000"></textarea></label>
      </fieldset>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" :disabled="busy">{{ busy ? '正在保存…' : '保存' }}</button>
        <button class="admin-button" type="button" :disabled="busy" @click="draft = undefined; problem = ''">取消</button>
      </div>
    </form>
    <section v-if="target" class="catalog-editor" aria-label="确认变更状态">
      <h2>{{ target.status === 'ACTIVE' ? '停用' : '启用' }}{{ target.name }}</h2>
      <p v-if="target.status === 'ACTIVE'">停用后，患者将无法再查询该{{ label }}的号源。如有未结束的有效预约，系统会阻止停用。</p>
      <p v-else>启用后，满足条件的开放号源可重新被患者查到。</p>
      <div class="catalog-actions">
        <button class="admin-button admin-button--primary" type="button" :disabled="busy" @click="changeStatus">
          {{ target.status === 'ACTIVE' ? '确认停用' : '确认启用' }}
        </button>
        <button class="admin-button" type="button" :disabled="busy" @click="target = undefined; problem = ''">取消</button>
      </div>
    </section>
    <p v-if="state === 'loading'" class="admin-empty" role="status">正在读取{{ label }}…</p>
    <div v-else-if="state === 'session'" class="admin-empty" role="alert">
      管理员会话已失效。<RouterLink :to="{ name: 'admin-login', query: { next: $route.fullPath } }">重新登录</RouterLink>
    </div>
    <div v-else-if="state === 'failed'" class="admin-empty" role="alert">
      {{ label }}加载失败。<button class="admin-button" type="button" @click="reload">重新加载</button>
    </div>
    <p v-else-if="!items.length" class="admin-empty">没有符合条件的{{ label }}。</p>
    <div v-else class="admin-table-scroll">
      <table class="admin-table catalog-table" :aria-label="label + '记录'">
        <thead><tr>
          <th scope="col">{{ label }}名称</th><th scope="col">{{ doctorPage ? '所属科室' : '科室编码' }}</th>
          <th v-if="doctorPage" scope="col">职称</th><th scope="col">{{ doctorPage ? '专长' : '简介' }}</th>
          <th scope="col">状态</th><th scope="col">操作</th>
        </tr></thead>
        <tbody><tr v-for="row in items" :key="row.id">
          <th scope="row">{{ row.name }}</th>
          <template v-if="'departmentId' in row"><td>{{ row.departmentName }}</td><td>{{ row.title || '—' }}</td><td>{{ row.specialty || '—' }}</td></template>
          <template v-else><td>{{ row.code }}</td><td>{{ row.description || '—' }}</td></template>
          <td><span class="catalog-status" :data-active="row.status === 'ACTIVE'">{{ row.status === 'ACTIVE' ? '已启用' : '已停用' }}</span></td>
          <td><div class="catalog-actions">
            <button class="admin-button" type="button" :aria-label="'编辑' + row.name" :disabled="busy" @click="edit(row)">编辑</button>
            <button class="admin-button" type="button" :aria-label="(row.status === 'ACTIVE' ? '停用' : '启用') + row.name"
              :disabled="busy" @click="confirmStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
    <AdminPagination v-if="state === 'ready' && (filters.page > 1 || hasNext || (totalPages ?? 0) > 1)"
      :page="filters.page" :total-pages="totalPages" :has-next="hasNext" @change="go" />
  </section>
</template>
