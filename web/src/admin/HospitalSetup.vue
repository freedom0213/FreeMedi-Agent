<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { activateHospitalSetup, agentSettingsError, commitCatalogImport, previewCatalogImport, readHospitalSetup,
  type CatalogImportPreview, type HospitalRules, type ImportKind } from '../api/admin'
import { useKnowledgeRead } from './useKnowledgeRead'

const setup = useKnowledgeRead(readHospitalSetup)
const step = ref(1)
const profile = ref({ name: '', contact: '' })
const rules = ref<HospitalRules>()
const kind = ref<ImportKind>('DEPARTMENTS')
const csv = ref('')
const filename = ref('')
const preview = ref<CatalogImportPreview>()
const busy = ref(false)
const error = ref('')
const notice = ref('')
const templates = {
  DEPARTMENTS: { label: '科室', header: 'code,name', example: 'DEPT-X,示例科室' },
  DOCTORS: { label: '医生', header: 'code,name,departmentCode,title', example: 'DOC-X,示例医生,DEPT-X,主任医师' },
  SCHEDULES: { label: '排班和号源', header: 'doctorCode,date,period,start,minutes,count', example: 'DOC-X,YYYY-MM-DD,MORNING,09:00,15,4' },
}
const states = { CREATE: '可导入', SKIP: '跳过', ERROR: '需修正' }
const checks = computed(() => setup.data.value?.checks)
const prepared = computed(() => !!checks.value?.departments && !!checks.value?.doctors && !!checks.value?.schedules)
const active = computed(() => !!setup.data.value?.profile.activatedAt)

watch(setup.data, value => {
  if (!value || rules.value) return
  profile.value = { name: value.profile.name, contact: value.profile.contact }
  rules.value = { ...value.rules, cancellationCutoff: value.rules.cancellationCutoff.slice(0, 5) }
})

function move(next: number) {
  error.value = ''
  notice.value = ''
  step.value = next
}

function defaults() {
  const policy = setup.data.value?.defaults
  if (policy) rules.value = { ...policy, cancellationCutoff: policy.cancellationCutoff.slice(0, 5) }
}

function template() {
  const pick = templates[kind.value]
  const url = URL.createObjectURL(new Blob(['\uFEFF', pick.header, '\n', pick.example, '\n'], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'freemedi-' + kind.value.toLowerCase() + '.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function resetImport() {
  csv.value = ''
  filename.value = ''
  preview.value = undefined
  error.value = ''
  notice.value = ''
}

async function pick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  resetImport()
  if (!file) return
  if (file.size > 512_000) {
    error.value = '文件过大，请按模板拆成若干批次。'
    return
  }
  filename.value = file.name
  csv.value = await file.text()
}

async function action(run: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await run()
  } catch (failure) {
    error.value = agentSettingsError(failure)
  } finally {
    busy.value = false
  }
}

function review() {
  preview.value = undefined
  void action(async () => { preview.value = await previewCatalogImport(kind.value, csv.value) })
}

function commit() {
  const reviewed = preview.value
  if (!reviewed) return
  void action(async () => {
    preview.value = undefined
    const count = await commitCatalogImport(kind.value, csv.value, reviewed.token)
    notice.value = `已导入 ${count} 条。`
    setup.reload()
  })
}

function activate() {
  const policy = rules.value
  if (!policy) return
  void action(async () => {
    await activateHospitalSetup({ ...profile.value, ...policy })
    setup.reload()
    notice.value = '医院资料和业务规则已生效。'
  })
}
</script>

<template>
  <h1 class="admin-page-title">首次启用</h1>
  <section class="admin-panel" aria-label="医院首次启用">
    <p v-if="setup.state.value === 'loading' && !rules" class="admin-empty" role="status">正在读取医院配置…</p>
    <div v-else-if="!rules" class="admin-empty" role="alert">医院配置加载失败。
      <button class="admin-button" type="button" @click="setup.reload">重试</button>
    </div>
    <template v-else>
      <header class="admin-panel-toolbar"><h2>{{ active ? '医院已启用' : '准备医院资料与预约规则' }}</h2></header>
      <nav class="setup-steps" aria-label="启用步骤">
        <button v-for="(label, index) in ['医院资料', '业务规则', '导入基础数据', '检查与启用']" :key="label"
          type="button" class="admin-button" :class="{ 'admin-button--primary': step === index + 1 }"
          :aria-current="step === index + 1 ? 'step' : undefined" :disabled="busy" @click="move(index + 1)">{{ label }}</button>
      </nav>
      <p v-if="error" class="admin-form-error catalog-message" role="alert">{{ error }}</p>
      <p v-if="notice" class="admin-form-hint catalog-message" role="status">{{ notice }}</p>
      <form v-if="step === 1" class="catalog-editor" @submit.prevent="move(2)">
        <h2>医院资料</h2>
        <fieldset class="catalog-fields">
          <label>医院名称<input v-model.trim="profile.name" class="admin-input" required maxlength="100"></label>
          <label>门诊咨询电话<input v-model.trim="profile.contact" class="admin-input" type="tel" required maxlength="100"></label>
        </fieldset>
        <button class="admin-button admin-button--primary">下一步</button>
      </form>
      <form v-else-if="step === 2" class="catalog-editor" @submit.prevent="move(3)">
        <h2>预约与签到规则</h2>
        <p class="admin-muted">按北京时间执行。在线改约与取消使用同一个截止时间。</p>
        <fieldset class="catalog-fields">
          <label>可排班至今天之后（天）<input v-model.number="rules.scheduleDays" class="admin-input" type="number" required min="1" max="90"></label>
          <label>患者查号范围（含当天）<input v-model.number="rules.patientDays" class="admin-input" type="number" required min="1" :max="Math.min(90, rules.scheduleDays + 1)"></label>
          <label>就诊前一天取消与改约截止<input v-model="rules.cancellationCutoff" class="admin-input" type="time" required></label>
          <label>开诊前可提早签到（分钟）<input v-model.number="rules.checkInLead" class="admin-input" type="number" required min="0" max="120"></label>
        </fieldset>
        <div class="catalog-actions">
          <button type="button" class="admin-button" @click="defaults">使用默认规则</button>
          <button class="admin-button admin-button--primary">下一步</button>
        </div>
      </form>
      <section v-else-if="step === 3" class="catalog-editor" aria-labelledby="import-title">
        <h2 id="import-title">导入基础数据</h2>
        <p class="admin-muted">按科室、医生、排班顺序导入。使用 UTF-8 CSV，每批最多 200 行。</p>
        <fieldset class="catalog-fields" :disabled="busy">
          <label>导入类型<select v-model="kind" class="admin-input" @change="resetImport">
            <option v-for="(record, key) in templates" :key="key" :value="key">{{ record.label }}</option>
          </select></label>
          <label>选择 CSV<input :key="kind" class="admin-input" type="file" accept=".csv,text/csv" @change="pick"></label>
          <button type="button" class="admin-button" @click="template">下载模板</button>
        </fieldset>
        <div class="catalog-actions">
          <button type="button" class="admin-button" :disabled="busy || !csv" @click="review">预览导入</button>
          <button type="button" class="admin-button admin-button--primary" :disabled="busy" @click="move(4)">前往启用检查</button>
        </div>
        <div v-if="preview" class="admin-table-scroll">
          <table class="admin-table" :aria-label="'导入预览 ' + filename">
            <thead><tr><th scope="col">行号</th><th scope="col">编码</th><th scope="col">结果</th><th scope="col">说明</th></tr></thead>
            <tbody><tr v-for="row in preview.rows" :key="row.line">
              <td>{{ row.line }}</td><td>{{ row.key || '—' }}</td><td>{{ states[row.state] }}</td><td>{{ row.message }}</td>
            </tr></tbody>
          </table>
          <button class="admin-button admin-button--primary" type="button" :disabled="busy ||
            preview.rows.some(row => row.state === 'ERROR') || preview.rows.every(row => row.state === 'SKIP')" @click="commit">
            确认导入
          </button>
        </div>
      </section>
      <section v-else class="catalog-editor" aria-labelledby="activate-title">
        <h2 id="activate-title">检查与启用</h2>
        <p>{{ profile.name || '待填写医院名称' }} · {{ profile.contact || '待填写门诊咨询电话' }}</p>
        <ul>
          <li>启用科室 {{ checks?.departments ?? 0 }} 个</li>
          <li>启用医生 {{ checks?.doctors ?? 0 }} 位</li>
          <li>有可用号源的开放排班 {{ checks?.schedules ?? 0 }} 场</li>
        </ul>
        <p>可排到今天之后第 {{ rules.scheduleDays }} 天，患者可查询含当天的 {{ rules.patientDays }} 天。
          就诊前一天 {{ rules.cancellationCutoff }} 截止取消及改约，可提早 {{ rules.checkInLead }} 分钟签到。</p>
        <p v-if="!prepared" class="admin-muted">请先导入资料，或在基础数据管理中添加科室、医生与排班。</p>
        <button :disabled="busy || !prepared || !profile.name || !profile.contact" class="admin-button admin-button--primary"
          type="button" @click="activate">{{ active ? '保存并应用配置' : '确认启用' }}</button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.setup-steps { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 18px; }
.admin-table-scroll { margin-top: 12px; }
.admin-table-scroll > button { margin-top: 12px; }
</style>
