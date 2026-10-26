import { get, post, put } from './http'

/** 最近一次可以回档的操作（只保留一次，新的操作会覆盖） */
export interface RollbackEntry {
  id: string
  kind: string
  title: string
  /** UI：在界面上操作；AI：小R执行 */
  source: 'UI' | 'AI'
  at: string
  done: string[]
  undo: string[]
  warnings: string[]
  canRollback: boolean
  reason: string | null
}

export interface RollbackResult {
  message: string
  notes: string[]
}

export interface ScopeRoot {
  path: string
  source: 'ARCHIVE' | 'IMPORT' | 'EXTRA' | 'AI'
}

/** 小R 文件操作的允许范围 */
export interface ScopeView {
  archiveRoots: boolean
  importFolders: boolean
  extraRoots: string[]
  /** 对话里授权的文件夹（v1.7） */
  aiRoots: string[]
  effective: ScopeRoot[]
}

export const getRollback = () => get<RollbackEntry | null>('/rollback')
export const runRollback = (id: string) => post<RollbackResult>(`/rollback/${id}`)
export const getScope = () => get<ScopeView>('/ai/scope')
export const saveScope = (u: Partial<Pick<ScopeView, 'archiveRoots' | 'importFolders' | 'extraRoots' | 'aiRoots'>>) =>
  put<ScopeView>('/ai/scope', u)
