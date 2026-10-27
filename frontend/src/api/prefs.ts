import { del, get, put } from './http'

/** 导入习惯（v1.7.2）：上次用的归档目录、模板、导入方式和导入过的文件夹，保存在程序的数据目录 */
export interface ImportPrefs {
  /** 实际生效的默认归档目录 */
  archiveRoot: string
  /** LAST_USED 上次导入用的；MANUAL 在系统设置里手动设的；SYSTEM 系统默认 */
  archiveRootSource: 'LAST_USED' | 'MANUAL' | 'SYSTEM'
  systemDefaultRoot: string
  /** 归档目录所在的磁盘是否存在（移动硬盘没插时为 false） */
  available: boolean
  unavailableReason: string | null
  /** 上次导入用的模板；为空或已被删除时用默认模板 */
  templateId: number | null
  mode: 'folder' | 'upload'
  lastSourceDir: string | null
  /** “选择文件夹”窗口打开的位置：上次导入的文件夹所在的上一级 */
  sourceChooserStart: string | null
}

export const getImportPrefs = () => get<ImportPrefs>('/prefs/import')
export const setDefaultArchiveRoot = (path: string) => put<ImportPrefs>('/prefs/import/archive-root', { path })
export const resetDefaultArchiveRoot = () => del<ImportPrefs>('/prefs/import/archive-root')
