import { get, post, put, del, upload } from './http'

export type FileStatus = 'QUEUED' | 'PARSING' | 'READY' | 'REVIEW' | 'ARCHIVED' | 'UNDONE' | 'FAILED' | 'DUPLICATE'

export interface Batch {
  id: number
  sourceType: 'FOLDER' | 'UPLOAD' | 'INBOX'
  sourcePath: string | null
  targetRoot: string
  templateId: number
  templateName: string
  status: 'RUNNING' | 'DONE' | 'ARCHIVED' | 'UNDONE'
  running: boolean
  skippedCount: number
  skippedSummary: string | null
  createdAt: string
  finishedAt: string | null
  archivedAt: string | null
  stats: Record<FileStatus, number>
}

export interface FileRecord {
  id: number
  batchId: number
  originalPath: string
  originalName: string
  ext: string
  size: number
  pageCount: number | null
  status: FileStatus
  ocrUsed: boolean
  title: string | null
  department: string | null
  docType: string | null
  docDate: string | null
  deptConf: number | null
  typeConf: number | null
  dateConf: number | null
  overallConf: number | null
  keywords: string | null
  reviewReasons: string | null
  newName: string | null
  targetPath: string | null
  corrected: boolean
  duplicateOf: number | null
  errorMessage: string | null
}

export interface Evidence {
  signal: string
  snippet: string
  weight: number
}

export interface FieldResult {
  value: string | null
  confidence: number
  margin: number
  evidence: Evidence[]
  candidates: { value: string; score: number }[]
}

export interface FileDetail {
  file: FileRecord
  evidence: {
    department: FieldResult
    docType: FieldResult
    date: FieldResult
    title: FieldResult
    regions: string[]
    notes: string[]
  } | null
  extras: Record<string, string>
  textExcerpt: string | null
  steps: { seq: number; step: string; detail: string; durationMs: number; createdAt: string }[]
}

export interface PreviewItem {
  fileId: number
  originalName: string
  status: FileStatus
  newName: string
  relativePath: string
  conflict: string | null
}

export interface ArchiveSummary {
  archived: number
  skipped: number
  failed: number
  messages: string[]
  ledgerFile: string | null
}

export const createFromFolder = (sourcePath: string, targetRoot: string, templateId: number | null) =>
  post<Batch>('/batches', { sourcePath, targetRoot, templateId })

export const createFromInbox = (targetRoot: string, templateId: number | null) =>
  post<Batch>('/batches/inbox', { targetRoot, templateId })

export const createFromUpload = (files: File[], targetRoot: string, templateId: number | null) => {
  const extra: Record<string, string> = {}
  if (targetRoot) extra.targetRoot = targetRoot
  if (templateId) extra.templateId = String(templateId)
  return upload<Batch>('/batches/upload', files, extra)
}

export const listBatches = () => get<Batch[]>('/batches')
export const getBatch = (id: number) => get<Batch>(`/batches/${id}`)
export const listFiles = (id: number) => get<FileRecord[]>(`/batches/${id}/files`)
export const previewArchive = (id: number, includeReview: boolean) =>
  post<PreviewItem[]>(`/batches/${id}/preview`, undefined, { includeReview })
export const runArchive = (id: number, fileIds: number[] | null, includeReview: boolean) =>
  post<ArchiveSummary>(`/batches/${id}/archive`, { fileIds, includeReview })
export const undoBatch = (id: number) =>
  post<{ undone: number; kept: number; messages: string[] }>(`/batches/${id}/undo`)

/** 删除批次记录（不删除原文件和已归档文件） */
export const deleteBatch = (id: number) =>
  del<{ deletedFiles: number; keptLedger: number; message: string }>(`/batches/${id}`)
/** 从批次中移除一个未归档的文件记录 */
export const deleteFile = (id: number) => del<void>(`/files/${id}`)

export const getFile = (id: number) => get<FileDetail>(`/files/${id}`)
export const reviewQueue = () => get<FileRecord[]>('/files/review')
export const correctFile = (id: number, body: { department?: string; docType?: string; docDate?: string; title?: string }) =>
  put<FileRecord>(`/files/${id}`, body)
export const retryFile = (id: number) => post<void>(`/files/${id}/retry`)
/** 重试批次里所有失败的文件 */
export const retryFailed = (batchId: number) => post<{ retried: number }>(`/batches/${batchId}/retry-failed`)
export const previewUrl = (id: number, page = 1) => `/api/files/${id}/preview?page=${page}`

export const chooseFolder = (title: string, initial?: string) =>
  post<{ path?: string }>('/fs/choose-folder', undefined, { title, initial })
