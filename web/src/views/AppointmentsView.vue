<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, CalendarDays, CircleHelp, LoaderCircle,
  RefreshCw, ShieldCheck, Stethoscope,
} from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import { errorMessage } from '../api/http'
import { channelLabel, formatAppointmentDate, listMyAppointments, periodLabel } from '../api/portal'
import type { AppointmentRecord } from '../types/portal'

const router = useRouter()
const records = ref<AppointmentRecord[]>([])
const selected = ref<AppointmentRecord | null>(null)
const loading = ref(false)
const error = ref('')

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    records.value = await listMyAppointments()
    const keep = records.value.find((record) => record.appointmentNo === selected.value?.appointmentNo)
    selected.value = keep ?? records.value[0] ?? null
  } catch (failure) {
    error.value = errorMessage(failure)
    records.value = []
    selected.value = null
  } finally {
    loading.value = false
  }
}

function statusLabel(status: string): string {
  return ({ BOOKED: '已预约', CANCELLED: '已取消' } as Record<string, string>)[status] ?? status
}
</script>

<template>
  <AppShell>
    <div class="portal-page page-wrap">
      <div class="portal-heading">
        <button class="back-link" type="button" @click="router.push('/')">
          <ArrowLeft :size="16" />返回就诊服务
        </button>
        <div class="portal-heading-row">
          <div>
            <div class="eyebrow"><span class="eyebrow-line" />我的预约</div>
            <h1>预约记录</h1>
          </div>
          <div class="heading-actions">
            <span class="record-count">共 {{ records.length }} 条</span>
            <button class="button-secondary" type="button" :disabled="loading" @click="load()">
              <RefreshCw :size="14" />刷新
            </button>
          </div>
        </div>
      </div>

      <div class="portal-workspace">
        <section class="portal-main" aria-label="预约记录列表">
          <div v-if="loading" class="block-empty"><LoaderCircle :size="16" class="spin" />正在读取预约记录…</div>
          <div v-else-if="records.length" class="record-list">
            <button
              v-for="record in records"
              :key="record.appointmentNo"
              class="record-row"
              :class="{ selected: record.appointmentNo === selected?.appointmentNo }"
              type="button"
              @click="selected = record"
            >
              <span class="record-body">
                <span class="record-date"><CalendarDays :size="14" />{{ formatAppointmentDate(record.appointmentDate) }}</span>
                <span class="record-line">{{ periodLabel(record.clinicPeriod) }} {{ record.timeRange }}</span>
                <span class="record-line"><Stethoscope :size="13" />{{ record.departmentName }} · {{ record.doctorName }}</span>
              </span>
              <span class="record-status">{{ statusLabel(record.status) }}</span>
            </button>
          </div>
          <div v-else class="block-empty tall">
            <p>当前演示账号还没有预约记录。</p>
            <RouterLink class="button-primary" to="/booking">去预约挂号 <ArrowRight :size="15" /></RouterLink>
          </div>
          <p v-if="error" class="inline-error" role="alert"><CircleHelp :size="15" />{{ error }}</p>
        </section>

        <aside class="portal-aside" aria-label="预约详情">
          <div class="aside-head"><h2>预约详情</h2></div>
          <template v-if="selected">
            <dl class="summary-list">
              <div><dt>预约编号</dt><dd class="mono">{{ selected.appointmentNo }}</dd></div>
              <div><dt>状态</dt><dd>{{ statusLabel(selected.status) }}</dd></div>
              <div>
                <dt>就诊时间</dt>
                <dd>{{ formatAppointmentDate(selected.appointmentDate) }} {{ periodLabel(selected.clinicPeriod) }} {{ selected.timeRange }}</dd>
              </div>
              <div><dt>科室</dt><dd>{{ selected.departmentName }}</dd></div>
              <div>
                <dt>医生</dt>
                <dd>{{ selected.doctorName }}<template v-if="selected.doctorTitle"> · {{ selected.doctorTitle }}</template></dd>
              </div>
              <div><dt>预约方式</dt><dd>{{ channelLabel(selected.bookedVia) }}</dd></div>
            </dl>
            <div class="privacy-note">
              <ShieldCheck :size="15" />
              <span>只能看到当前演示账号自己的预约；他人的预约不会出现在这里。</span>
            </div>
          </template>
          <p v-else class="aside-note">选择左侧一条记录查看详情。</p>
        </aside>
      </div>
    </div>
  </AppShell>
</template>
