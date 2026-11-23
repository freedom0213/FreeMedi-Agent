import type { DocumentType, IndexStatus, VersionStatus } from '../api/admin'

export const documentTypeLabels: Record<DocumentType, string> = {
  APPOINTMENT_RULE: '预约规则',
  VISIT_NOTICE: '就诊须知',
  DEPARTMENT_INTRO: '科室介绍',
  DOCTOR_PROFILE: '医生资料',
  PREPARATION: '检查准备',
}

export const versionLabels: Record<VersionStatus, string> = {
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
  DRAFT: '草稿',
}

export const indexLabels: Record<IndexStatus, string> = {
  INDEXED: '已索引',
  PENDING: '待索引',
  FAILED: '索引失败',
}

const hospitalDate = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short',
})

export function publishedTime(value: string | null) {
  return value ? hospitalDate.format(new Date(value)) : '尚未发布'
}

export function knowledgeFragmentLink(chunk: { docNo: string; version: number; chunkNo: number | null }) {
  return { name: 'admin-document', params: { docNo: chunk.docNo },
    query: { version: chunk.version, chunk: chunk.chunkNo ?? undefined } }
}
