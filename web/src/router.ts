import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import AgentView from './views/AgentView.vue'
import ServiceUnavailableView from './views/ServiceUnavailableView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/guide', name: 'guide', component: AgentView },
    { path: '/booking', name: 'booking', component: ServiceUnavailableView, props: { title: '普通预约挂号' } },
    { path: '/appointments', name: 'appointments', component: ServiceUnavailableView, props: { title: '我的预约' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

export default router
