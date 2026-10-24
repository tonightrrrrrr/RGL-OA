import { get, post, put, del } from './http'

export interface LedgerEntry {
  id: number
  archiveNo: string
  title: string
  department: string
  docType: string
  docDate: string | null
  originalName: string
  newName: string
  archivePath: string
  relativePath: string
  format: string
  size: number
  pageCount: number | null
  keywords: string | null
  contractNo: string | null
  docNo: string | null
  partyA: string | null
  partyB: string | null
  amount: string | null
  recognizeMode: string
  confidence: number | null
  ocrUsed: boolean
  archivedAt: string
}

export interface LedgerQuery {
  keyword?: string
  department?: string
  docType?: string
  format?: string
  recognizeMode?: string
  dateFrom?: string
  dateTo?: string
}

export const searchLedger = (q: LedgerQuery, page: number, size: number) =>
  get<{ items: LedgerEntry[]; total: number }>('/ledger', { ...clean(q), page, size })

/** 导出地址：按搜索条件导出；传 ids 时只导出勾选的记录 */
export function exportUrl(q: LedgerQuery, ids?: number[]) {
  const p = new URLSearchParams(clean(q))
  if (ids) ids.forEach((id) => p.append('ids', String(id)))
  return '/api/ledger/export?' + p.toString()
}

export const openEntry = (id: number, folder: boolean) => post<void>(`/ledger/${id}/open`, undefined, { folder })

export interface TemplateSummary {
  id: number
  name: string
  builtin: boolean
  updatedAt: string
}

export interface DeptDef {
  name: string
  code: string
  aliases: string[]
}

export interface TypeDef {
  name: string
  code: string
  suffixes: string[]
  features: string[]
}

export interface TemplateConfig {
  companyName: string
  stripCompanyPrefix: boolean
  departments: DeptDef[]
  docTypes: TypeDef[]
  keywordRules: { department: string; keywords: string[] }[]
  namingPattern: string
  pathPattern: string
  archiveNoPattern: string
  dateFormat: string
  threshold: number
  archiveMode: 'COPY' | 'MOVE'
}

export const listTemplates = () => get<TemplateSummary[]>('/templates')
export const getTemplate = (id: number) =>
  get<TemplateSummary & { content: string; config: TemplateConfig }>(`/templates/${id}`)
export const createTemplate = (name: string, content: string) => post<TemplateSummary>('/templates', { name, content })
/** 新建模板：基于已有模板（baseId），baseId 为 null 时从空白模板开始 */
export const createTemplateFrom = (name: string, baseId: number | null) =>
  post<TemplateSummary>('/templates', { name, content: null, baseId })
export const updateTemplate = (id: number, name: string, content: string) =>
  put<TemplateSummary>(`/templates/${id}`, { name, content })
export const deleteTemplate = (id: number) => del<void>(`/templates/${id}`)
export const copyTemplate = (id: number) => post<TemplateSummary>(`/templates/${id}/copy`)
export const exportTemplateUrl = (id: number) => `/api/templates/${id}/export`
export type IssueLevel = 'ERROR' | 'WARNING' | 'INFO'

export interface TemplateIssue {
  level: IssueLevel
  area: string
  message: string
  suggestion: string
  fix: { kind: 'ADD_DEPT' | 'ADD_TYPE' | 'APPEND_NAMING' | 'APPEND_ARCHIVE_NO' | 'SET_COPY'; value: string; label: string } | null
}

export interface TemplateDiff {
  settings: { field: string; label: string; base: string; target: string }[]
  departments: { name: string; change: 'ADDED' | 'REMOVED' | 'CHANGED'; detail: string | null }[]
  docTypes: { name: string; change: 'ADDED' | 'REMOVED' | 'CHANGED'; detail: string | null }[]
  keywords: { department: string; added: string[]; removed: string[] }[]
  total: number
}

export interface TemplateAnalysis {
  issues: TemplateIssue[]
  counts: { error: number; warning: number; info: number }
  diff: TemplateDiff | null
  samples: { original: string; fields: string; target: string; targetNo: string; base?: string; baseNo?: string }[]
}

/** 一个文件在某个模板下的预览结果 */
export interface PreviewSide {
  department: string
  deptConf: number
  docType: string
  typeConf: number
  date: string | null
  dateConf: number
  title: string
  overall: number
  blocked: boolean
  review: boolean
  reasons: string[]
  fileName: string
  dir: string
  archiveNo: string
  relativePath: string
  /** 与正确答案不一致的字段 */
  wrong: string[]
  /** 占位符取值，只在当前模板一侧提供 */
  vars: Record<string, string> | null
}

export interface PreviewRow {
  key: string
  originalName: string
  truth: { department: string; docType: string; date: string | null } | null
  base: PreviewSide
  target: PreviewSide
  changed: string[]
}

export interface PreviewStat { auto: number; review: number; wrong: number; wrongAuto: number; collisions: number }
export interface ThresholdPoint { threshold: number; auto: number; review: number; wrongAuto: number }
export interface PresetResult { pattern: string; examples: string[]; collisions: number; folders: number; issue: string | null }
export type PresetKind = 'naming' | 'path' | 'archiveNo' | 'dateFormat'

export interface PreviewResult {
  source: { kind: 'SAMPLES' | 'SAMPLES_RENAMED' | 'SAMPLES_NEGATIVE' | 'BATCH'; batchId: number | null; label: string; truthNote: string }
  rows: PreviewRow[]
  base: PreviewStat
  target: PreviewStat
  /** 归档位置相同的文件分组（原文件名） */
  collisions: string[][]
  thresholds: ThresholdPoint[]
  recommendation: { threshold: number | null; reason: string }
  presets: Partial<Record<PresetKind, PresetResult[]>>
  truncated: boolean
  skipped: string[]
}

/** 智能检查与对比（不保存） */
export const analyzeTemplate = (content: string, baseContent: string | null) =>
  post<TemplateAnalysis>('/templates/analyze', { content, baseContent })

/** 实时预览：用内置示例或已导入批次的真实文件，对比两个模板的识别和命名结果（不修改数据） */
export const previewTemplate = (src: { batchId: number | null; renamedSamples: boolean; negativeSamples?: boolean }, content: string,
  baseContent: string | null, presets: Record<PresetKind, string[]>) =>
  post<PreviewResult>('/templates/preview', { ...src, content, baseContent, presets })

function clean(q: object): Record<string, string> {
  const out: Record<string, string> = {}
  Object.entries(q).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = String(v)
  })
  return out
}
