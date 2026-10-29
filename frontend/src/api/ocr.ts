import { get, postForm, put } from './http'

/** 内置 OCR 模型（v1.9.1）：实测数据来自对比测试，见技术难点文档 3.7 */
export interface OcrModel {
  id: string
  name: string
  tag: string
  summary: string
  pros: string[]
  cons: string[]
  sizeMb: number
  /** 困难页（手写体、书法体、模糊拍照）的字符 F1 */
  hardF1: number
  hardMs: number
  /** 清晰 A4 页（300dpi）平均耗时 */
  pageMs: number
}

export interface OcrModelsView {
  /** 实际在用的模型（选择的模型加载失败时退回 ppocrv4） */
  current: string
  selected: string
  recommended: string
  fallbackNote: string | null
  loaded: string[]
  failed: Record<string, string>
  models: OcrModel[]
}

export interface OcrCompareItem {
  model: string
  name: string
  error?: string
  millis?: number
  lineCount?: number
  charCount?: number
  confidence?: number
  lines?: { text: string; confidence: number }[]
}

export interface OcrCompareResult {
  width: number
  height: number
  current: string
  results: OcrCompareItem[]
}

export const getOcrModels = () => get<OcrModelsView>('/ocr/models')
export const switchOcrModel = (model: string) => put<OcrModelsView>('/ocr/model', { model })
export const compareOcr = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return postForm<OcrCompareResult>('/ocr/compare', form)
}
