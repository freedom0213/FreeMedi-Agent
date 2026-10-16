<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ArrowUpRight, CalendarDays, Clock3, LoaderCircle, Search, ShieldCheck, Sparkles } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import { listDepartments } from '../api/portal'
import type { Department } from '../types/portal'

const router = useRouter()
const query = ref('')
const departments = ref<Department[]>([])
const loading = ref(false)
const catalogueUnavailable = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    departments.value = (await listDepartments()).slice(0, 5)
  } catch {
    catalogueUnavailable.value = true
  } finally {
    loading.value = false
  }
})

function beginGuide(prompt = query.value.trim()) {
  router.push({ name: 'guide', query: prompt ? { intent: prompt } : { new: '1' } })
}

function beginBooking(department?: Department) {
  router.push({ name: 'booking', query: department ? { departmentId: String(department.id) } : {} })
}
</script>

<template>
  <AppShell>
    <div class="home-page page-wrap">
      <section class="service-intro">
        <div class="intro-copy">
          <div class="eyebrow"><span class="eyebrow-line" />模拟医院就诊服务</div>
          <h1>从就诊需求开始，<br class="desktop-break" />找到合适的预约方案。</h1>
          <p>查询科室和医生，或由智能导诊助手协助收集需求、匹配可用号源。</p>
        </div>
        <div class="intro-aside">
          <div class="intro-aside-icon"><CalendarDays :size="20" /></div>
          <div>
            <strong>预约由你确认</strong>
            <span>号源查询后展示完整方案，确认后才创建预约。</span>
          </div>
        </div>
      </section>

      <section class="service-search" aria-label="查找就诊服务">
        <div class="search-entry">
          <Search :size="19" class="search-icon" />
          <input
            v-model="query"
            type="text"
            placeholder="描述哪里不舒服，或直接说想看哪个科室"
            aria-label="描述就诊需求或指定科室"
            @keydown.enter="beginGuide()"
          />
          <button class="button-primary search-submit" type="button" @click="beginGuide()">
            开始智能导诊 <ArrowRight :size="16" />
          </button>
        </div>
        <div class="search-support">
          <span><Clock3 :size="14" />就诊时段以查询到的号源为准</span>
          <span><ShieldCheck :size="14" />仅使用模拟医院数据</span>
        </div>
      </section>

      <section class="service-grid">
        <div class="department-section">
          <div class="section-heading">
            <div>
              <h2>按科室查找</h2>
              <p>选择科室可直接筛选号源，也可以交给导诊助手协助</p>
            </div>
            <button class="text-action" type="button" @click="beginGuide('请帮我选择合适的科室并查询可预约号源')">
              咨询助手 <ArrowUpRight :size="15" />
            </button>
          </div>

          <div v-if="loading" class="block-empty"><LoaderCircle :size="15" class="spin" />正在读取科室目录…</div>
          <div v-else-if="departments.length" class="department-list">
            <button
              v-for="department in departments"
              :key="department.id"
              class="department-row"
              type="button"
              @click="beginBooking(department)"
            >
              <span class="department-symbol"><span /></span>
              <span class="department-name">{{ department.name }}</span>
              <span class="department-action">预约挂号 <ArrowRight :size="14" /></span>
            </button>
            <button class="department-row" type="button" @click="beginBooking()">
              <span class="department-symbol"><span /></span>
              <span class="department-name">全部科室</span>
              <span class="department-action">查看目录 <ArrowRight :size="14" /></span>
            </button>
          </div>
          <div class="not-ready-note">
            <span class="note-mark">i</span>
            <span v-if="catalogueUnavailable">
              科室目录暂时无法读取，请确认后端服务已启动；也可以直接使用智能导诊。
            </span>
            <span v-else>
              科室、医生和号源均来自模拟医院数据。预约提交前会展示完整方案并请你核对。
            </span>
          </div>
        </div>

        <aside class="guide-entry">
          <div class="guide-entry-top">
            <div class="guide-entry-icon"><Sparkles :size="19" /></div>
            <span class="service-label">预约服务</span>
          </div>
          <h2>不确定从哪里开始？</h2>
          <p>描述哪里不舒服，或直接说想看哪个科室。助手会协助判断就诊方向、查找可用号源，并请你核对后再预约。</p>
          <button class="button-primary guide-button" type="button" @click="beginGuide()">
            开始智能导诊 <ArrowRight :size="16" />
          </button>
          <div class="guide-footnote"><ShieldCheck :size="14" />预约写入前需要你的明确确认</div>
        </aside>
      </section>

      <section id="service-note" class="service-notice">
        <div class="notice-title"><span class="notice-line" />就诊服务说明</div>
        <div class="notice-content">
          <div class="notice-item"><strong>预约规则</strong><span>遵循页面展示的确认与核验流程</span></div>
          <div class="notice-item"><strong>数据范围</strong><span>科室、医生、排班和号源均为合成数据</span></div>
          <div class="notice-item"><strong>服务边界</strong><span>提供预约协助，不提供诊断或用药建议</span></div>
        </div>
      </section>
    </div>
  </AppShell>
</template>
