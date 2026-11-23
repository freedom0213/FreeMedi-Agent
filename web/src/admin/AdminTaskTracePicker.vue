<script setup lang="ts">
import { Search } from 'lucide-vue-next'
import type { AgentTaskSummary } from '../api/admin'
import { clock, taskName, taskStatus } from './agentTraceLabels'
import type { TaskTraceRoute } from './useAdminTaskTrace'

defineProps<{ routeName: TaskTraceRoute; recent: AgentTaskSummary[]; recentLoading: boolean; recentIssue: string; taskId: string }>()
defineEmits<{ locate: [] }>()
const search = defineModel<string>({ required: true })
</script>

<template>
  <section class="admin-panel admin-trace-picker" aria-labelledby="admin-recent-tasks">
    <div class="admin-panel-toolbar"><h2 id="admin-recent-tasks">最近任务</h2></div>
    <form class="admin-trace-search" @submit.prevent="$emit('locate')">
      <label for="trace-task-search">按任务编号定位</label>
      <div>
        <input id="trace-task-search" v-model="search" class="admin-input" type="search" maxlength="64" placeholder="输入完整任务编号" />
        <button class="admin-button" type="submit" aria-label="查找任务"><Search :size="17" aria-hidden="true" /></button>
      </div>
    </form>
    <p v-if="recentLoading && !recent.length" class="admin-empty" role="status">正在读取任务…</p>
    <p v-else-if="recentIssue" class="admin-empty" role="alert">{{ recentIssue }}</p>
    <p v-else-if="!recent.length" class="admin-empty">暂无任务记录。</p>
    <ul v-else class="admin-trace-tasks">
      <li v-for="task in recent" :key="task.taskId">
        <RouterLink :to="{ name: routeName, query: { taskId: task.taskId } }"
                    :aria-current="task.taskId === taskId ? 'true' : undefined">
          <span class="admin-trace-task-top"><strong>{{ taskName(task.taskType) }}</strong><small>{{ taskStatus(task.status) }}</small></span>
          <code :title="task.taskId">{{ task.taskId }}</code>
          <time :datetime="task.updatedAt">{{ clock.format(new Date(task.updatedAt)) }}</time>
        </RouterLink>
      </li>
    </ul>
  </section>
</template>
