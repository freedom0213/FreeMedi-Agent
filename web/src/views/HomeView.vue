<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ArrowRight, Brain, CalendarCheck, ChevronRight, Heart, LoaderCircle, Stethoscope } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import AppointmentNotifications from '../components/AppointmentNotifications.vue'
import { patientSession } from '../api/auth'
import { listDepartments } from '../api/portal'
import consultationImage from '../assets/home-consultation.webp'
import lungsIcon from '../assets/department-lungs.png'
import stomachIcon from '../assets/department-stomach.png'
import boneIcon from '../assets/department-bone.png'
import type { Department } from '../types/portal'

const router = useRouter()
const query = ref('')
const departments = ref<Department[]>([])
const loading = ref(true)
const catalogueUnavailable = ref(false)

const featuredCodes = ['RESPIRATORY', 'CARDIOLOGY', 'GASTROENTEROLOGY', 'ORTHOPEDICS', 'NEUROLOGY']
const featuredRank = new Map(featuredCodes.map((code, index) => [code, index]))
// Health Icons (CC0), from the 24px outline set.
const departmentIcons: Partial<Record<string, string>> = {
  RESPIRATORY: lungsIcon,
  GASTROENTEROLOGY: stomachIcon,
  ORTHOPEDICS: boneIcon,
}

onMounted(loadDepartments)

async function loadDepartments() {
  loading.value = true
  catalogueUnavailable.value = false
  try {
    const catalogue = await listDepartments()
    departments.value = catalogue.sort((left, right) =>
      (featuredRank.get(left.code) ?? featuredCodes.length) - (featuredRank.get(right.code) ?? featuredCodes.length),
    ).slice(0, 4)
  } catch {
    catalogueUnavailable.value = true
  } finally {
    loading.value = false
  }
}

function beginGuide() {
  const prompt = query.value.trim()
  return router.push({ name: 'guide', query: prompt ? { intent: prompt } : { new: '1' } })
}
</script>

<template>
  <AppShell class="home-frame">
    <div class="home-page page-wrap">
      <AppointmentNotifications v-if="patientSession" compact />
      <section class="home-hero" aria-labelledby="home-title">
        <div class="home-intro">
          <h1 id="home-title">找到科室，<br />约好就诊时间。</h1>
          <p>不确定挂哪个科？可以先描述你的情况。</p>
          <form class="home-query" @submit.prevent="beginGuide">
            <input
              v-model="query"
              type="text"
              placeholder="例如：咳嗽一周，上午方便就诊"
              aria-label="描述就诊需求"
            />
            <button class="button-primary" type="submit">
              开始智能导诊 <ArrowRight :size="17" aria-hidden="true" />
            </button>
          </form>
          <RouterLink class="button-secondary home-booking-link" :to="{ name: 'booking' }">
            直接预约挂号 <ChevronRight :size="16" aria-hidden="true" />
          </RouterLink>
        </div>
        <div class="home-illustration">
          <img :src="consultationImage" width="1200" height="800" alt="医生在诊室里倾听患者讲述近况" fetchpriority="high" />
        </div>
      </section>

      <div class="home-services">
        <section id="service-note" class="home-service-note" aria-labelledby="home-service-title">
          <h2 id="home-service-title">就诊怎么安排？</h2>
          <ol>
            <li>描述不适或选择科室</li>
            <li>选医生和就诊时间</li>
            <li>核对信息后确认预约</li>
          </ol>
          <p>导诊提供就诊方向与预约协助。诊断和治疗请以医生面诊为准。</p>
        </section>

        <section class="home-departments" aria-labelledby="home-departments-title" :aria-busy="loading">
          <header class="home-section-heading">
            <h2 id="home-departments-title">按科室挂号</h2>
            <RouterLink class="home-text-link" :to="{ name: 'booking' }">
              查看全部科室 <ChevronRight :size="16" aria-hidden="true" />
            </RouterLink>
          </header>
          <p v-if="loading" class="home-feedback" role="status">
            <LoaderCircle class="spin" :size="16" aria-hidden="true" /> 正在读取科室目录…
          </p>
          <div v-else-if="catalogueUnavailable" class="home-feedback">
            <p role="status">科室目录暂时无法读取。</p>
            <button class="home-text-link" type="button" @click="loadDepartments">重试</button>
          </div>
          <div v-else-if="departments.length" class="home-department-grid">
            <RouterLink
              v-for="department in departments"
              :key="department.id"
              class="home-department"
              :data-code="department.code"
              :to="{ name: 'booking', query: { departmentId: String(department.id) } }"
            >
              <span class="home-department-icon" aria-hidden="true">
                <span
                  v-if="departmentIcons[department.code]"
                  class="home-department-art"
                  :style="{ '--department-image': 'url(' + departmentIcons[department.code] + ')' }"
                />
                <Heart v-else-if="department.code === 'CARDIOLOGY'" :size="21" :stroke-width="1.6" />
                <Brain v-else-if="department.code === 'NEUROLOGY'" :size="21" :stroke-width="1.6" />
                <Stethoscope v-else :size="21" :stroke-width="1.6" />
              </span>
              <span>{{ department.name }}</span>
              <ChevronRight class="home-department-arrow" :size="16" aria-hidden="true" />
            </RouterLink>
          </div>
          <p v-else class="home-feedback" role="status">暂时没有可预约的科室。</p>
        </section>

        <section class="home-appointment-card" aria-labelledby="home-appointment-title">
          <CalendarCheck :size="27" :stroke-width="1.6" aria-hidden="true" />
          <h2 id="home-appointment-title">已经预约？</h2>
          <p>查看就诊时间，或从预约记录进入「就诊准备」。</p>
          <RouterLink class="home-text-link" :to="{ name: 'appointments' }">
            查看我的预约 <ArrowRight :size="16" aria-hidden="true" />
          </RouterLink>
        </section>
      </div>
    </div>
  </AppShell>
</template>

<style scoped>
.home-frame {
  min-width: 0;
  --home-ink: #182c43;
  --home-blue: #244f95;
  --home-sky: #e9f3fa;
  --home-border: #cadce9;
  --home-muted: #42586c;
  background: linear-gradient(145deg, #d1deea, #e3ebf2 68%, #dbe4ee);
}

.home-frame :deep(main) { display: grid; align-content: safe center; }
.home-frame :deep(.site-footer) { margin-top: 0; }
.home-frame :deep(.site-header), .home-frame :deep(.site-footer) { background: #f8fafc; border-color: #ccdbe8; }

.home-page {
  width: min(1440px, calc(100% - 72px));
  display: grid;
  gap: clamp(20px, 2.35vw, 34px);
  margin-block: clamp(19px, 3.1vh, 40px);
  padding: clamp(17px, 2.5vw, 36px);
  border: 1px solid #e0e8ef;
  border-radius: 26px;
  background: #fff;
  box-shadow: 0 22px 55px #273e5620;
  color: var(--home-ink);
}

.home-hero {
  display: grid;
  grid-template-columns: minmax(0, .93fr) minmax(0, 1.07fr);
  overflow: hidden;
  background: linear-gradient(113deg, #ecf4f9 32%, #daeafa);
  border-radius: 20px;
}

.home-intro {
  display: flex;
  flex-direction: column;
  align-items: start;
  justify-content: center;
  padding: clamp(26px, 4vw, 60px) 0 clamp(26px, 4vw, 60px) clamp(26px, 4vw, 60px);
}

.home-intro h1 {
  margin: 0;
  font-size: clamp(36px, 4.4vw, 66px);
  font-weight: 700;
  line-height: 1.14;
  letter-spacing: -.035em;
  color: var(--home-ink);
}

.home-intro p { margin: 16px 0 23px; color: var(--home-muted); line-height: 1.6; font-size: 16px; }

.home-query {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 5px;
  border: 1px solid var(--home-border);
  border-radius: 12px;
  background: #fff;
}

.home-query input {
  flex: 1;
  min-width: 0;
  width: 100%;
  min-height: 42px;
  padding: 7px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--brand-ink);
  font-size: 15px;
}

.home-query input::placeholder { color: var(--home-muted); opacity: 1; }
.home-query .button-primary { flex-shrink: 0; background: var(--home-blue); }
.home-query .button-primary:hover { background: #1c3e77; }
.home-booking-link { margin-top: 12px; color: var(--home-blue); border-color: var(--home-border); background: #f7fbfe; }
.home-illustration {
  position: relative;
  isolation: isolate;
  display: grid;
  align-items: end;
  min-height: clamp(345px, 34vw, 490px);
  padding: 20px 18px 10px;
}

.home-illustration::before {
  content: '';
  position: absolute;
  z-index: -1;
  inset: 12% 9% 16%;
  border-radius: 55% 46% 50% 38%;
  background: #cbdff2;
  transform: rotate(-7deg);
}

.home-illustration img {
  display: block;
  width: 100%;
  height: auto;
}

.home-services { display: grid; grid-template-columns: minmax(0, .88fr) minmax(0, 1.45fr) minmax(0, .8fr); align-items: stretch; gap: clamp(12px, 1.5vw, 22px); }
.home-departments {
  padding: clamp(14px, 1.45vw, 21px);
  border: 1px solid #d8e7f2;
  border-radius: 17px;
  background: #edf5fb;
}

.home-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-bottom: 10px;
}

.home-section-heading h2 { margin: 0; font-size: 19px; }

.home-text-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  min-height: 32px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--home-blue);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}

.home-text-link:hover { text-decoration: underline; text-underline-offset: 3px; }
.home-department-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }

.home-department {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 8px 10px;
  border: 1px solid #d7e6f0;
  border-radius: 10px;
  background: #fff;
  font-size: 14px;
  font-weight: 700;
  transition: background .16s ease, border-color .16s ease;
}

.home-department:hover { background: #f7fbff; border-color: #92b1cb; }
.home-department-arrow { margin-left: auto; flex: none; color: var(--home-blue); }

.home-department-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 35px;
  height: 35px;
  border-radius: 9px;
  background: #e1eefb;
  color: var(--home-blue);
}

.home-department[data-code="CARDIOLOGY"] .home-department-icon { background: var(--brand-peach); color: #794833; }
.home-department[data-code="GASTROENTEROLOGY"] .home-department-icon { background: #ecebf7; color: #554776; }
.home-department[data-code="NEUROLOGY"] .home-department-icon { background: #e4f0e7; color: #2c6350; }

.home-department-art {
  width: 83%;
  height: 83%;
  background: currentColor;
  mask: var(--department-image) center / contain no-repeat;
}

.home-feedback {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 56px;
  margin: 0;
  color: var(--brand-muted);
  font-size: 14px;
}

.home-feedback p { margin: 0; }
.home-service-note { align-self: center; padding: 4px 8px; }
.home-service-note h2, .home-appointment-card h2 { margin: 0 0 7px; font-size: 19px; }
.home-service-note ol { display: grid; gap: 4px; margin: 0 0 10px; padding-inline-start: 21px; color: var(--home-ink); font-size: 14px; line-height: 1.55; }
.home-service-note li::marker { color: var(--home-blue); font-weight: 700; }
.home-service-note p, .home-appointment-card p { margin: 0; color: var(--home-muted); font-size: 14px; line-height: 1.6; }
.home-appointment-card { display: flex; flex-direction: column; align-items: start; padding: clamp(14px, 1.5vw, 22px); border: 1px solid #dbe7cb; border-radius: 17px; background: #edf3e2; }
.home-appointment-card > svg { margin-bottom: 10px; color: #36583d; }
.home-appointment-card .home-text-link { margin-top: auto; padding-top: 8px; color: #30513b; }

@media (max-width: 920px) {
  .home-query { flex-wrap: wrap; }
  .home-query input { flex-basis: 100%; }
  .home-query .button-primary { width: 100%; }
  .home-page { width: calc(100% - 40px); }
  .home-services { grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); }
  .home-service-note { grid-column: 1 / -1; grid-row: 2; }
}

@media (max-width: 720px) {
  .home-frame :deep(main) { align-content: start; }
  .home-page { width: calc(100% - 24px); margin-block: 12px; gap: 13px; padding: 10px; border-radius: 17px; }
  .home-hero { grid-template-columns: minmax(0, 1fr); }
  .home-intro { padding: 21px; }
  .home-intro h1 { font-size: clamp(32px, 7vw, 44px); }
  .home-intro p { margin: 11px 0 14px; }
  .home-query input { font-size: 16px; }
  .home-illustration { min-height: 0; padding-top: 0; }
  .home-illustration img { max-height: 360px; object-fit: contain; }
  .home-services { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .home-service-note { grid-column: auto; grid-row: auto; order: 1; padding: 8px; }
}

@media (max-width: 420px) {
  .home-intro, .home-departments { padding: 13px; }
  .home-section-heading h2 { font-size: 17px; }
  .home-text-link { font-size: 13px; }
  .home-department { gap: 5px; padding-inline: 6px; font-size: 13px; }
  .home-department-icon { width: 25px; height: 25px; }
  .home-department-icon :deep(svg) { width: 17px; height: 17px; }
  .home-department-arrow { width: 12px; }
}
</style>
