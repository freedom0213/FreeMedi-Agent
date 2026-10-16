import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import AgentView from './views/AgentView.vue'
import BookingView from './views/BookingView.vue'
import AppointmentsView from './views/AppointmentsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/guide', name: 'guide', component: AgentView },
    { path: '/booking', name: 'booking', component: BookingView },
    { path: '/appointments', name: 'appointments', component: AppointmentsView },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

export default router
