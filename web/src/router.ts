import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import AgentView from './views/AgentView.vue'
import BookingView from './views/BookingView.vue'
import AppointmentsView from './views/AppointmentsView.vue'
import VisitPreparationView from './views/VisitPreparationView.vue'
import LoginView from './views/LoginView.vue'
import { loginTarget, readSession } from './api/auth'
import { adminSession, canOpenAdmin, readAdminSession } from './api/admin'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/guide', name: 'guide', component: AgentView, meta: { requiresPatient: true } },
    { path: '/booking', name: 'booking', component: BookingView, meta: { requiresPatient: true } },
    { path: '/appointments', name: 'appointments', component: AppointmentsView, meta: { requiresPatient: true } },
    { path: '/account/password', name: 'patient-password', component: () => import('./views/PatientPassword.vue'), meta: { requiresPatient: true } },
    { path: '/appointments/:appointmentNo/preparation', name: 'visit-preparation', component: VisitPreparationView, meta: { requiresPatient: true } },
    { path: '/appointments/:appointmentNo/reschedule', name: 'appointment-reschedule',
      component: () => import('./views/RescheduleView.vue'), props: true, meta: { requiresPatient: true } },
    { path: '/admin/login', name: 'admin-login', component: () => import('./admin/AdminLogin.vue') },
    { path: '/admin/password', name: 'admin-password', component: () => import('./admin/AdminPassword.vue'), meta: { requiresAdmin: true } },
    {
      path: '/admin',
      component: () => import('./admin/AdminLayout.vue'),
      meta: { requiresAdmin: true },
      children: [
        { path: '', name: 'admin-home', component: () => import('./admin/AdminHome.vue') },
        { path: 'staff', name: 'admin-staff', component: () => import('./admin/AdminStaff.vue') },
        { path: 'setup', name: 'admin-setup', component: () => import('./admin/HospitalSetup.vue') },
        { path: 'departments', name: 'admin-departments', component: () => import('./admin/CatalogDirectory.vue'), props: { kind: 'departments' } },
        { path: 'doctors', name: 'admin-doctors', component: () => import('./admin/CatalogDirectory.vue'), props: { kind: 'doctors' } },
        { path: 'schedules', name: 'admin-schedules', component: () => import('./admin/CatalogSchedules.vue') },
        { path: 'appointments', name: 'admin-appointments', component: () => import('./admin/AdminAppointments.vue') },
        { path: 'today-triage', name: 'admin-today-triage', component: () => import('./admin/AdminAppointments.vue'), props: { visitDesk: 'TODAY' } },
        { path: 'visit-review', name: 'admin-visit-review', component: () => import('./admin/AdminAppointments.vue'), props: { visitDesk: 'REVIEW' } },
        { path: 'knowledge', name: 'admin-knowledge', component: () => import('./admin/KnowledgeList.vue') },
        { path: 'knowledge-library', name: 'admin-knowledge-library', component: () => import('./admin/KnowledgeLibrary.vue') },
        { path: 'knowledge-chunks', name: 'admin-knowledge-chunks', component: () => import('./admin/KnowledgeFragments.vue'), props: { vectors: false } },
        { path: 'knowledge-index', name: 'admin-knowledge-index', component: () => import('./admin/KnowledgeFragments.vue'), props: { vectors: true } },
        { path: 'knowledge/import/preview', name: 'admin-knowledge-import', component: () => import('./admin/KnowledgeImportPreview.vue') },
        { path: 'knowledge/:docNo', name: 'admin-document', component: () => import('./admin/KnowledgeDetail.vue') },
        { path: 'tool-logs', name: 'admin-tool-logs', component: () => import('./admin/ToolLogs.vue') },
        { path: 'agent-timeline', name: 'admin-agent-timeline', component: () => import('./admin/AgentTimeline.vue') },
        { path: 'visit-operations', name: 'admin-visit-operations', component: () => import('./admin/VisitOperations.vue') },
        { path: 'model-calls', name: 'admin-model-calls', component: () => import('./admin/ModelCallObservations.vue') },
        { path: 'tool-registry', name: 'admin-tool-registry', component: () => import('./admin/ToolRegistry.vue') },
        { path: 'model-config', name: 'admin-model-config', component: () => import('./admin/AiModelConfiguration.vue') },
        { path: 'prompt-templates', name: 'admin-prompt-templates', component: () => import('./admin/PromptTemplates.vue') },
      ],
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  if (to.meta.requiresAdmin || to.name === 'admin-login') {
    const state = await readAdminSession()
    if (state === 'signed-in' && adminSession.value?.mustChangePassword && to.name !== 'admin-password') {
      return { name: 'admin-password' }
    }
    if (to.name === 'admin-login' && state === 'signed-in') return { name: 'admin-home' }
    if (to.meta.requiresAdmin && state !== 'signed-in') {
      return { name: 'admin-login', query: { reason: state } }
    }
    if (to.meta.requiresAdmin && !canOpenAdmin(to.name)) return { name: 'admin-home', query: { denied: '1' } }
    return
  }
  const patient = await readSession().catch(() => null)
  if (to.meta.requiresPatient && !patient) return { name: 'login', query: { next: to.fullPath } }
  if (to.name === 'login' && patient) return loginTarget(to.query.next)
})

export default router
