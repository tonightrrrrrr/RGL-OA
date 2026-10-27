import type { FileStatus } from '../api/archive'

type TagType = 'default' | 'info' | 'success' | 'warning' | 'error'

export const STATUS_META: Record<FileStatus, { label: string; type: TagType }> = {
  QUEUED: { label: '排队中', type: 'default' },
  PARSING: { label: '识别中', type: 'info' },
  READY: { label: '待归档', type: 'success' },
  REVIEW: { label: '待确认', type: 'warning' },
  ARCHIVED: { label: '已归档', type: 'success' },
  UNDONE: { label: '已撤销', type: 'default' },
  FAILED: { label: '失败', type: 'error' },
  DUPLICATE: { label: '重复', type: 'warning' }
}

/** 识别信号的中文说明，用于展示判断依据 */
export const SIGNAL_LABEL: Record<string, string> = {
  FILENAME: '文件名',
  FILENAME_MENTION: '文件名提及',
  FILENAME_CONTAINS: '文件名含类型词',
  ANCHOR: '锚点字段',
  ANCHOR_PERSON: '人员字段',
  FILING: '报送/备案对象',
  TITLE_MENTION: '标题提及',
  TITLE_SUFFIX: '标题后缀',
  TITLE_CONTAINS: '标题含类型词',
  TITLE_REGION: '标题区',
  TITLE_YEAR_MONTH: '标题年月',
  KEYWORD_TITLE: '标题关键词',
  KEYWORD_BODY: '正文关键词',
  BODY_FREQ: '正文出现次数',
  CONTENT_FEATURE: '内容特征',
  TABLE_FEATURE: '表格特征',
  FOOTER_DATE: '落款日期',
  FOOTER_OTHER: '落款区日期',
  HEADER_ANCHOR: '抬头日期字段',
  MEETING_TIME: '会议时间',
  BODY_DATE: '正文日期',
  // v1.8 简历
  POSITION: '应聘岗位',
  APPLY_DEPT: '应聘部门',
  AI_SUGGEST: '小R推测',
  IMPORT_DATE: '导入当天',
  RESUME_NAME: '姓名和岗位',
  RESUME_POSITION: '应聘岗位'
}

export function confColor(c: number | null | undefined): string {
  if (c === null || c === undefined) return 'var(--text-3)'
  if (c >= 0.85) return 'var(--success)'
  if (c >= 0.75) return 'var(--warning)'
  return 'var(--error)'
}

export function pct(c: number | null | undefined): string {
  return c === null || c === undefined ? '-' : (c * 100).toFixed(0) + '%'
}

export function fileSize(bytes: number | null | undefined): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

export function dateTime(s: string | null | undefined): string {
  return s ? s.replace('T', ' ').slice(0, 19) : '-'
}
