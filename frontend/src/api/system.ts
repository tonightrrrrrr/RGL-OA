import { get, postForm } from './http'

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

export const ocrTest = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return postForm<OcrTestResult>('/system/ocr-test', form)
}
