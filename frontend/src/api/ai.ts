import { ApiError, get, post, put } from './http'

export interface AiSettingsView {
  enabled: boolean
  baseUrl: string
  hasKey: boolean
  keyHint: string
  model: string
  allowContent: boolean
  proxy: string
  timeoutSeconds: number
  privacyAcknowledged: boolean
  ready: boolean
}

/** 只提交要改的字段；apiKey 留空表示不改 */
export interface AiSettingsUpdate {
  enabled?: boolean
  baseUrl?: string
  apiKey?: string
  model?: string
  allowContent?: boolean
  proxy?: string
  timeoutSeconds?: number
  privacyAcknowledged?: boolean
}

export interface AiTestResult {
  ok: boolean
  latencyMs: number
  reply: string | null
  modelCount: number | null
  modelListed: boolean | null
  toolCalls: boolean
  message: string
}

export interface AiChange {
  label: string
  before: string
  after: string
}

/** 小R 提出、等待用户确认的操作；danger 为 true 时要再确认一次 */
export interface AiActionView {
  id: string
  kind: 'template' | 'correction' | 'fileop' | 'delete' | 'import' | 'archive' | 'undo' | 'deleteBatch'
    | 'removeFile' | 'deleteTemplate' | 'retry' | 'rollback'
  title: string
  summary: string
  changes: AiChange[]
  stats: Record<string, number | string | null>
  examples: { from: string; to: string }[]
  warnings: string[]
  danger: boolean
}

export interface AiActionResult {
  message: string
  link: string | null
  /** 这次操作可以回档 */
  rollback: boolean
}

/** 发给模型的消息（OpenAI 格式），界面原样保存、下次一起发回后端 */
export interface ChatMessage {
  role: 'user' | 'assistant' | 'tool'
  content: string | null
  tool_calls?: { id: string; type: 'function'; function: { name: string; arguments: string } }[]
  tool_call_id?: string
}

export interface ToolEvent {
  id: string
  name: string
  label: string
  status: 'running' | 'done' | 'error'
  summary?: string
}

export interface ChatHandlers {
  onDelta: (text: string) => void
  onTool: (e: ToolEvent) => void
  onAction: (a: AiActionView) => void
  onDownload: (d: { url: string; label: string }) => void
  onDone: (added: ChatMessage[]) => void
  onError: (message: string) => void
}

export const getAiSettings = () => get<AiSettingsView>('/ai/settings')
export const saveAiSettings = (u: AiSettingsUpdate) => put<AiSettingsView>('/ai/settings', u)
export const testAi = (u: AiSettingsUpdate) => post<AiTestResult>('/ai/test', u)
export const listAiModels = (u: AiSettingsUpdate) => post<string[]>('/ai/models', u)
export const confirmAiAction = (id: string) => post<AiActionResult>(`/ai/actions/${id}/confirm`)
export const cancelAiAction = (id: string) => post<void>(`/ai/actions/${id}/cancel`)

/**
 * 流式对话。后端用 SSE 推送事件；EventSource 不能发 POST，这里用 fetch 读取响应流自己解析。
 * signal 中止时（用户点停止）直接返回，不触发 onError。
 */
export async function streamChat(messages: ChatMessage[], h: ChatHandlers, signal: AbortSignal): Promise<void> {
  let resp: Response
  try {
    resp = await fetch('/api/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ messages }),
      signal
    })
  } catch (e) {
    if (signal.aborted) return
    throw new ApiError(-1, '无法连接到程序后台，请确认程序仍在运行')
  }
  const type = resp.headers.get('Content-Type') || ''
  if (!resp.ok || !type.includes('text/event-stream') || !resp.body) {
    let msg = `请求失败（HTTP ${resp.status}）`
    try {
      const body = await resp.json()
      if (body?.message) msg = body.message
    } catch {
      /* 不是 JSON，用默认提示 */
    }
    throw new ApiError(resp.status, msg)
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let cut: number
      while ((cut = buf.search(/\r?\n\r?\n/)) >= 0) {
        const block = buf.slice(0, cut)
        buf = buf.slice(buf[cut] === '\r' ? cut + 4 : cut + 2)
        dispatch(block, h)
      }
    }
    if (buf.trim()) dispatch(buf, h)
  } catch (e) {
    if (signal.aborted) return
    throw new ApiError(-1, '和程序后台的连接中断了')
  }
}

function dispatch(block: string, h: ChatHandlers) {
  let event = 'message'
  const data: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  if (!data.length) return
  let payload: any
  try {
    payload = JSON.parse(data.join('\n'))
  } catch {
    return
  }
  switch (event) {
    case 'delta': h.onDelta(payload.text ?? ''); break
    case 'tool': h.onTool(payload); break
    case 'action': h.onAction(payload); break
    case 'download': h.onDownload(payload); break
    case 'done': h.onDone(payload.messages ?? []); break
    case 'error': h.onError(payload.message ?? '小R遇到了问题'); break
  }
}
