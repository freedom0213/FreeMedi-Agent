import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import AgentView from './views/AgentView.vue'
import BookingView from './views/BookingView.vue'
import AppointmentsView from './views/AppointmentsView.vue'
import VisitPreparationView from './views/VisitPreparationView.vue'
import LoginView from './views/LoginView.vue'
import { loginTarget, readSession } from './api/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/guide', name: 'guide', component: AgentView, meta: { requiresPatient: true } },
    { path: '/booking', name: 'booking', component: BookingView, meta: { requiresPatient: true } },
    { path: '/appointments', name: 'appointments', component: AppointmentsView, meta: { requiresPatient: true } },
    { path: '/appointments/:appointmentNo/preparation', name: 'visit-preparation', component: VisitPreparationView, meta: { requiresPatient: true } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const patient = await readSession().catch(() => null)
  if (to.meta.requiresPatient && !patient) return { name: 'login', query: { next: to.fullPath } }
  if (to.name === 'login' && patient) return loginTarget(to.query.next)
})

export default router
