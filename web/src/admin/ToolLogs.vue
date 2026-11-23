<script setup lang="ts">
import { Activity, RefreshCw } from 'lucide-vue-next'
import AdminTaskTracePicker from './AdminTaskTracePicker.vue'
import { actionNames, clock, outcomes, reasons, taskStatus } from './agentTraceLabels'
import { toolNames } from './toolLabels'
import { useAdminTaskTrace } from './useAdminTaskTrace'

const { recent, trace, search, taskId, recentLoading, recentIssue, detailLoading, detailIssue,
  moreLoading, moreIssue, refresh, locate, loadMore } = useAdminTaskTrace('admin-tool-logs')
</script>

<template>
  <header class="admin-list-heading">
    <h1 class="admin-page-title">工具调用日志</h1>
    <button class="admin-button" type="button" :disabled="recentLoading || detailLoading" @click="refresh++">
      <RefreshCw :size="15" aria-hidden="true" /> 刷新日志
    </button>
  </header>
  <div class="admin-trace-grid">
    <AdminTaskTracePicker v-model="search" route-name="admin-tool-logs" :recent="recent" :recent-loading="recentLoading"
      :recent-issue="recentIssue" :task-id="taskId" @locate="locate" />
    <section class="admin-panel admin-trace-detail" aria-labelledby="admin-trace-title">
      <header class="admin-panel-toolbar">
        <h2 id="admin-trace-title"><Activity :size="18" aria-hidden="true" /> 调用记录</h2>
        <RouterLink v-if="trace" class="admin-button" :to="{ name: 'admin-agent-timeline', query: { taskId: trace.task.taskId } }">
          查看执行时间线
        </RouterLink>
      </header>
      <p v-if="detailLoading" class="admin-empty" role="status">正在读取调用记录…</p>
      <p v-else-if="detailIssue" class="admin-empty" role="alert">{{ detailIssue }}</p>
      <p v-else-if="!trace" class="admin-empty">选择一个任务以查看调用记录。</p>
      <template v-else>
        <dl class="admin-trace-summary">
          <div><dt>任务编号</dt><dd><code>{{ trace.task.taskId }}</code></dd></div>
          <div><dt>任务状态</dt><dd>{{ taskStatus(trace.task.status) }}</dd></div>
          <div><dt>对话轮次</dt><dd>{{ trace.task.turnCount }}</dd></div>
        </dl>
        <p v-if="!trace.entries.length" class="admin-empty">此任务暂无 S5 追踪记录。</p>
        <ol v-else class="admin-trace-events" aria-label="按时间排列的调用记录">
          <li v-for="event in trace.entries" :key="event.sequence" :data-outcome="event.outcome">
            <div class="admin-trace-event-clock">
              <time :datetime="event.recordedAt">{{ clock.format(new Date(event.recordedAt)) }}</time>
              <small>第 {{ event.turnNo }} 轮 · 步骤 {{ event.stepNo }}</small>
            </div>
            <div class="admin-trace-event-name">
              <strong>{{ event.tool ? toolNames[event.tool] ?? event.tool : actionNames[event.type] ?? event.type }}</strong>
              <code v-if="event.tool">{{ event.tool }}</code>
              <span v-if="event.reason" class="admin-trace-reason">
                {{ reasons[event.reason] ?? '原因码' }} · <code>{{ event.reason }}</code>
              </span>
              <small v-if="event.resultCount !== null">{{ event.resultCount }} 条结果</small>
              <small v-if="event.modelVersion">模型版本 {{ event.modelVersion === 'environment' ? '启动配置' : event.modelVersion }}</small>
            </div>
            <div class="admin-trace-event-result">
              <span class="admin-tag" :data-state="event.outcome === 'SUCCEEDED' ? 'INDEXED' : event.outcome === 'FAILED' ? 'FAILED' : 'PENDING'">
                {{ outcomes[event.outcome] ?? event.outcome }}
              </span>
              <small v-if="event.attempt > 1">第 {{ event.attempt }} 次 · 重试</small>
            </div>
            <span class="admin-trace-duration" :aria-label="event.durationMs === null ? '未记录耗时' : '耗时 ' + event.durationMs + ' 毫秒'">
              {{ event.durationMs === null ? '—' : event.durationMs + ' ms' }}
            </span>
          </li>
        </ol>
        <footer v-if="trace.nextAfter" class="admin-panel-toolbar admin-trace-footer">
          <p v-if="moreIssue" class="admin-form-error" role="alert">后续记录加载失败，可以重试。</p>
          <button class="admin-button" type="button" :disabled="moreLoading" @click="loadMore">
            {{ moreLoading ? '正在加载…' : '加载后续记录' }}
          </button>
        </footer>
      </template>
    </section>
  </div>
</template>
