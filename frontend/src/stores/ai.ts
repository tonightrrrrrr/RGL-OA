import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import {
  cancelAiAction, confirmAiAction, streamChat,
  type AiActionResult, type AiActionView, type ChatMessage, type ToolEvent
} from '../api/ai'
import { errorMessage } from '../api/http'
import { useRollbackStore } from './rollback'

/** 界面上一条回复由几段组成：思考过程、文字、工具标签、确认卡片、下载按钮、错误 */
export type Part =
  | { kind: 'thinking'; text: string }
  | { kind: 'text'; text: string }
  | { kind: 'tool'; tool: ToolEvent }
  | { kind: 'action'; action: AiActionView; state: 'pending' | 'working' | 'done' | 'cancelled' | 'failed'; result?: AiActionResult; error?: string }
  | { kind: 'download'; url: string; label: string }
  | { kind: 'error'; message: string }
  | { kind: 'stopped' }

export interface Turn {
  id: string
  role: 'user' | 'assistant'
  parts: Part[]
  at: number
}

const STORAGE_KEY = 'rgl-ai-chat'
const MAX_TURNS = 80
const MAX_HISTORY = 60

let seq = 0
const uid = () => `${Date.now().toString(36)}-${(seq++).toString(36)}`

/** 小R 的对话：界面显示用 turns，发给模型用 history（OpenAI 格式，包含工具调用），都保存在本机浏览器 */
export const useAiStore = defineStore('ai', () => {
  const saved = (() => {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as { turns?: Turn[]; history?: ChatMessage[]; notes?: string[] }
    } catch {
      return {}
    }
  })()
  // 刷新页面前没收到回复的那一轮不再显示“正在输入”
  const turns = ref<Turn[]>((saved.turns || []).map((t) => ({
    ...t,
    parts: t.parts.map((p) => (p.kind === 'tool' && p.tool.status === 'running' ? { ...p, tool: { ...p.tool, status: 'error' as const } } : p))
  })))
  const history = ref<ChatMessage[]>(saved.history || [])
  /** 用户确认或取消卡片后的结果，随下一次提问一起告诉小R */
  const notes = ref<string[]>(saved.notes || [])
  const streaming = ref(false)
  let controller: AbortController | null = null

  watch([turns, history, notes], () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      turns: turns.value.slice(-MAX_TURNS),
      history: history.value.slice(-MAX_HISTORY),
      notes: notes.value
    }))
  }, { deep: true })

  const empty = computed(() => turns.value.length === 0)

  async function send(question: string) {
    const q = question.trim()
    if (!q || streaming.value) return
    turns.value.push({ id: uid(), role: 'user', parts: [{ kind: 'text', text: q }], at: Date.now() })
    turns.value.push({ id: uid(), role: 'assistant', parts: [], at: Date.now() })
    // 取 reactive 代理，后面的修改界面能实时看到
    const reply = turns.value[turns.value.length - 1]
    const content = notes.value.length ? `（系统记录：${notes.value.join('；')}）\n${q}` : q
    const userMsg: ChatMessage = { role: 'user', content }
    notes.value = []

    streaming.value = true
    controller = new AbortController()
    let finished = false
    const lastText = () => {
      const last = reply.parts[reply.parts.length - 1]
      if (last?.kind === 'text') return last
      const p: Part = { kind: 'text', text: '' }
      reply.parts.push(p)
      return reply.parts[reply.parts.length - 1] as Extract<Part, { kind: 'text' }>
    }
    const lastThinking = () => {
      const last = reply.parts[reply.parts.length - 1]
      if (last?.kind === 'thinking') return last
      reply.parts.push({ kind: 'thinking', text: '' })
      return reply.parts[reply.parts.length - 1] as Extract<Part, { kind: 'thinking' }>
    }
    try {
      await streamChat([...history.value, userMsg], {
        onDelta: (t) => { lastText().text += t },
        onReasoning: (t) => { lastThinking().text += t },
        onTool: (e) => {
          const existing = reply.parts.find((p) => p.kind === 'tool' && p.tool.id === e.id)
          if (existing && existing.kind === 'tool') existing.tool = e
          else reply.parts.push({ kind: 'tool', tool: e })
        },
        onAction: (a) => { reply.parts.push({ kind: 'action', action: a, state: 'pending' }) },
        onDownload: (d) => { reply.parts.push({ kind: 'download', url: d.url, label: d.label }) },
        onDone: (added) => {
          finished = true
          history.value.push(userMsg, ...added)
        },
        onError: (m) => {
          finished = true
          reply.parts.push({ kind: 'error', message: m })
          history.value.push(userMsg, { role: 'assistant', content: `（出错：${m}）` })
        }
      }, controller.signal)
    } catch (e) {
      finished = true
      reply.parts.push({ kind: 'error', message: errorMessage(e) })
    } finally {
      if (!finished) {
        // 用户点了停止：保留已经输出的文字，让小R下次知道这一轮被打断了
        const said = reply.parts.filter((p) => p.kind === 'text').map((p) => (p as { text: string }).text).join('')
        reply.parts.push({ kind: 'stopped' })
        history.value.push(userMsg, { role: 'assistant', content: said ? said + '\n（用户中途停止了回答）' : '（用户中途停止了回答）' })
      }
      streaming.value = false
      controller = null
    }
  }

  function stop() {
    controller?.abort()
  }

  async function confirm(part: Extract<Part, { kind: 'action' }>) {
    if (part.state !== 'pending' && part.state !== 'failed') return
    part.state = 'working'
    try {
      part.result = await confirmAiAction(part.action.id)
      part.state = 'done'
      useRollbackStore().refresh()
      notes.value.push(`用户已确认“${part.action.title}”，结果：${part.result.message}`)
    } catch (e) {
      part.error = errorMessage(e)
      part.state = 'failed'
    }
  }

  async function cancel(part: Extract<Part, { kind: 'action' }>) {
    if (part.state !== 'pending' && part.state !== 'failed') return
    part.state = 'cancelled'
    notes.value.push(`用户取消了“${part.action.title}”`)
    try {
      await cancelAiAction(part.action.id)
    } catch {
      /* 取消失败不影响界面，卡片 30 分钟后自动过期 */
    }
  }

  function clear() {
    stop()
    turns.value = []
    history.value = []
    notes.value = []
  }

  return { turns, streaming, empty, send, stop, confirm, cancel, clear }
})
