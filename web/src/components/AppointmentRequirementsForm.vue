<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listDepartments, listDoctors } from '../api/portal'
import { errorMessage } from '../api/http'
import type { Department, Doctor } from '../types/portal'
import type { RequirementChanges, Requirements } from '../types/agent'

const props = defineProps<{ requirements?: Requirements; disabled: boolean }>()
const emit = defineEmits<{ search: [changes: RequirementChanges]; dirty: [dirty: boolean] }>()
const departments = ref<Department[]>([])
const doctors = ref<Doctor[]>([])
const departmentError = ref('')
const doctorError = ref('')
const loadingDepartments = ref(false)
const loadingDoctors = ref(false)
const doctorReload = ref(0)

function editable(value?: Requirements): Required<RequirementChanges> {
  return {
    departmentId: value?.departmentId ?? null,
    doctorId: value?.doctorId ?? null,
    appointmentDate: value?.appointmentDate ?? '',
    clinicPeriod: value?.clinicPeriod ?? '',
    dateFlexible: value?.dateFlexible ?? false,
    periodFlexible: value?.periodFlexible ?? false,
  }
}

const form = ref(editable(props.requirements))
const changes = computed(() => {
  const before = editable(props.requirements)
  const after = {
    ...form.value,
    dateFlexible: !!form.value.appointmentDate && form.value.dateFlexible,
    periodFlexible: !!form.value.clinicPeriod && form.value.periodFlexible,
  }
  return Object.fromEntries(
    (Object.keys(after) as (keyof typeof after)[]).filter(key => after[key] !== before[key])
      .map(key => [key, after[key]]),
  ) as RequirementChanges
})
const dirty = computed(() => Object.keys(changes.value).length > 0)

watch(dirty, value => emit('dirty', value), { immediate: true })
watch(() => props.requirements, value => { form.value = editable(value) })
watch([() => form.value.departmentId, doctorReload], async ([id], _, onCleanup) => {
  doctors.value = []
  doctorError.value = ''
  loadingDoctors.value = !!id
  if (!id) return
  let current = true
  onCleanup(() => { current = false })
  try {
    const incoming = await listDoctors(id)
    if (current) doctors.value = incoming
  } catch (error) {
    if (current) doctorError.value = errorMessage(error)
  } finally {
    if (current) loadingDoctors.value = false
  }
}, { immediate: true })

onMounted(loadDepartments)

async function loadDepartments() {
  if (loadingDepartments.value) return
  departmentError.value = ''
  loadingDepartments.value = true
  try {
    departments.value = await listDepartments()
  } catch (error) {
    departmentError.value = errorMessage(error)
  } finally {
    loadingDepartments.value = false
  }
}

function reset() {
  form.value = editable(props.requirements)
}

function search() {
  if (!props.disabled) emit('search', changes.value)
}
</script>

<template>
  <form class="requirements" @submit.prevent="search">
    <h2>本次预约需求</h2>
    <fieldset :disabled="disabled">
      <div class="requirement-field">
        <label for="guide-department">科室</label>
        <select id="guide-department" v-model="form.departmentId" :disabled="loadingDepartments" @change="form.doctorId = null">
          <option :value="null">{{ loadingDepartments ? '正在读取科室…' : '请选择科室' }}</option>
          <option v-for="item in departments" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </div>
      <div class="requirement-field">
        <label for="guide-doctor">医生</label>
        <select id="guide-doctor" v-model="form.doctorId" :disabled="!form.departmentId || loadingDoctors">
          <option :value="null">{{ loadingDoctors ? '正在读取医生…' : '不限医生' }}</option>
          <option v-for="item in doctors" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </div>
      <div v-if="departmentError" class="directory-recovery">
        <p role="alert">科室列表加载失败。{{ departmentError }}</p>
        <button type="button" class="button-secondary" @click="loadDepartments">重新加载科室</button>
      </div>
      <div v-if="doctorError" class="directory-recovery">
        <p role="alert">医生列表加载失败。{{ doctorError }}</p>
        <button type="button" class="button-secondary" @click="doctorReload++">重新加载医生</button>
      </div>
      <div class="requirement-field">
        <label for="guide-date">就诊日期</label>
        <input id="guide-date" v-model="form.appointmentDate" type="date" @change="form.dateFlexible = false" />
      </div>
      <div class="requirement-field">
        <label for="guide-period">就诊时段</label>
        <select id="guide-period" v-model="form.clinicPeriod" @change="form.periodFlexible = false">
          <option value="">不限时段</option>
          <option value="MORNING">上午</option>
          <option value="AFTERNOON">下午</option>
          <option value="EVENING">晚上</option>
        </select>
      </div>
      <label class="flexibility"><input v-model="form.dateFlexible" type="checkbox" :disabled="!form.appointmentDate" />无号时可换日期</label>
      <label class="flexibility"><input v-model="form.periodFlexible" type="checkbox" :disabled="!form.clinicPeriod" />无号时可换时段</label>
    </fieldset>
    <div class="requirement-actions">
      <button type="submit" class="button-primary" :disabled="disabled">保存并查询</button>
      <button v-if="dirty" type="button" class="text-action" :disabled="disabled" @click="reset">撤销修改</button>
      <p v-if="dirty" role="status">请先保存或撤销页面修改，再继续选择或补充需求。</p>
    </div>
  </form>
</template>

<style scoped>
.requirements { padding: 13px; background: var(--brand-soft); border: 1px solid var(--brand-line); border-radius: 9px; }
h2 { margin: 0 0 12px; font-size: 15px; }
fieldset { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; min-width: 0; padding: 0; margin: 0; border: 0; }
.requirement-field { display: grid; gap: 5px; }
label { font-size: 13px; color: var(--brand-muted); }
select, input[type="date"] { width: 100%; min-width: 0; min-height: 38px; padding: 6px 8px; border: 1px solid var(--brand-line); border-radius: 7px; background: var(--brand-paper); color: var(--brand-ink); font: inherit; }
.flexibility { display: flex; align-items: center; min-height: 30px; }
.flexibility input { accent-color: var(--brand-plum); }
.requirement-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 12px; }
.requirement-actions p { margin: 0; font-size: 13px; color: var(--brand-muted); }
.requirement-actions .text-action { color: var(--brand-plum); font-size: 13px; }
.directory-recovery {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff2ef;
  color: #863c39;
}
.directory-recovery p { flex: 1 1 200px; margin: 0; font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 640px) { select, input[type="date"] { font-size: 16px; } }
</style>
