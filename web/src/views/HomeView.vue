<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, ArrowUpRight, CalendarDays, Clock3, Search, ShieldCheck, Sparkles } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'

const router = useRouter()
const query = ref('')
const quickPrompts = [
  { name: '心内科', prompt: '我想预约心内科，请帮我查询可用号源' },
  { name: '呼吸内科', prompt: '我想预约呼吸内科，请帮我查询可用号源' },
  { name: '皮肤科', prompt: '我想预约皮肤科，请帮我查询可用号源' },
  { name: '全科医学科', prompt: '我想预约全科医学科，请帮我查询可用号源' },
]

function beginGuide(prompt = query.value.trim()) {
  router.push({ name: 'guide', query: prompt ? { intent: prompt } : { new: '1' } })
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
            placeholder="搜索科室、医生，或说说哪里不舒服"
            aria-label="搜索科室、医生或描述预约需求"
            @keydown.enter="beginGuide()"
          />
          <button class="button-primary search-submit" type="button" @click="beginGuide()">
            开始查询 <ArrowRight :size="16" />
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
              <p>选择科室后，由导诊助手协助查询合适的预约方案</p>
            </div>
            <button class="text-action" type="button" @click="beginGuide('请帮我选择合适的科室并查询可预约号源')">
              咨询助手 <ArrowUpRight :size="15" />
            </button>
          </div>

          <div class="department-list">
            <button v-for="department in quickPrompts" :key="department.name" class="department-row" type="button" @click="beginGuide(department.prompt)">
              <span class="department-symbol"><span /></span>
              <span class="department-name">{{ department.name }}</span>
              <span class="department-action">智能预约 <ArrowRight :size="14" /></span>
            </button>
          </div>
          <div class="not-ready-note">
            <span class="note-mark">i</span>
            <span>科室目录与普通挂号查询接口尚未开放；当前可通过智能导诊体验预约编排。</span>
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
