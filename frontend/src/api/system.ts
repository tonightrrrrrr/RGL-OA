import { get, post, postForm, put } from './http'

export interface SystemStatus {
  appName: string
  version: string
  mode: string
  home: string
  port: string
  defaultArchiveRoot: string
  inbox: string | null
  ocrAvailable: boolean
  ocrMessage: string
  javaVersion: string
  tmpDir: string
}

export interface OcrTestResult {
  millis: number
  width: number
  height: number
  lines: { text: string; confidence: number }[]
}

/** 一个版本的更新记录；changes 的 type：new 新增、improve 优化、fix 修复 */
export interface Release {
  version: string
  date: string
  title: string
  summary: string
  note?: string | null
  highlights: { icon: string; title: string; desc: string }[]
  changes: { type: 'new' | 'improve' | 'fix'; text: string }[]
}

export const getStatus = () => get<SystemStatus>('/system/status')

/** 版本更新记录，最新版本在前 */
export const getChangelog = () => get<Release[]>('/system/changelog')

/** 联网检查更新的结果；OFFLINE 表示没连上 GitHub，不影响使用 */
export interface UpdateInfo {
  state: 'NEVER' | 'CHECKING' | 'UP_TO_DATE' | 'AVAILABLE' | 'OFFLINE' | 'ERROR' | 'DISABLED'
  current: string
  latest: string | null
  title: string | null
  notes: string | null
  publishedAt: string | null
  releaseUrl: string | null
  downloadUrl: string | null
  downloadSize: number | null
  message: string
  checkedAt: string | null
  autoCheck: boolean
  repo: string
}

export const getUpdate = () => get<UpdateInfo>('/system/update')
export const checkUpdate = () => post<UpdateInfo>('/system/update/check')
export const saveUpdateSettings = (autoCheck: boolean) => put<UpdateInfo>('/system/update/settings', { autoCheck })

export const ocrTest = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return postForm<OcrTestResult>('/system/ocr-test', form)
}
