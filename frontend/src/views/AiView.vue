<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { NButton, NInput, useDialog } from 'naive-ui'
import AppIcon from '../components/AppIcon.vue'
import AiActionCard from '../components/AiActionCard.vue'
import AiSettingsDrawer from '../components/AiSettingsDrawer.vue'
import { useAiStore } from '../stores/ai'
import { getAiSettings, type AiSettingsView } from '../api/ai'
import { renderMarkdown } from '../utils/markdown'

const ai = useAiStore()
const dialog = useDialog()

const SUGGESTIONS = [
  { label: '各部门归档统计', q: '各部门分别归档了多少份文件？' },
  { label: '找出所有合同', q: '帮我找一下所有的合同，列出甲乙方' },
  { label: '待确认的文件', q: '现在有哪些待确认的文件？为什么要确认？' },
  { label: '归档最新批次', q: '把最新一个批次里待归档的文件归档' },
  { label: '改命名规则', q: '把命名规则改成日期在前面，另存为一个新模板' },
  { label: '导出台账', q: '导出 2026 年人力资源部的台账' },
  { label: '撤回刚才的操作', q: '刚才的操作是误操作，帮我回档' }
]

const input = ref('')
const settings = ref<AiSettingsView | null>(null)
const showSettings = ref(false)
const scroller = ref<HTMLElement | null>(null)
const stick = ref(true)

const ready = computed(() => settings.value?.ready ?? false)
const statusText = computed(() => {
  const s = settings.value
  if (!s) return '读取设置中'
  if (!s.enabled) return '未启用'
  if (!s.ready) return '未配置完整'
  return s.model
})

onMounted(async () => {
  try {
    settings.value = await getAiSettings()
  } catch {
    settings.value = null
  }
  if (settings.value && !settings.value.ready && ai.empty) showSettings.value = true
  scrollToBottom(true)
})

function onScroll() {
  const el = scroller.value
  if (el) stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = scroller.value
    if (el && (force || stick.value)) el.scrollTop = el.scrollHeight
  })
}

watch(() => ai.turns.map((t) => t.parts.length + t.parts.reduce((n, p) => n + (p.kind === 'text' ? p.text.length : 1), 0)).join(),
  () => scrollToBottom())

function submit(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || ai.streaming) return
  if (!ready.value) {
    showSettings.value = true
    return
  }
  input.value = ''
  stick.value = true
  ai.send(q)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

function clearChat() {
  dialog.warning({
    title: '清空对话',
    content: '只清空这里的聊天记录，不会撤销已经确认的修改。',
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: () => ai.clear()
  })
}
</script>

<template>
  <div class="ai-page" :class="{ empty: ai.empty }">
    <header class="ai-head">
      <div class="who">
        <img src="/logo.png" class="avatar lg" alt="" />
        <div>
          <div class="who-name">RGL <span class="tag">小R</span></div>
          <div class="who-sub"><i class="dot" :class="{ on: ready }" />{{ statusText }}</div>
        </div>
      </div>
      <div class="head-actions">
        <n-button v-if="!ai.empty" quaternary size="small" :disabled="ai.streaming" @click="clearChat">
          <template #icon><app-icon name="trash" :size="16" /></template>清空对话
        </n-button>
        <n-button secondary size="small" @click="showSettings = true">
          <template #icon><app-icon name="system" :size="16" /></template>连接设置
        </n-button>
      </div>
    </header>

    <div ref="scroller" class="ai-scroll" @scroll="onScroll">
      <div class="ai-inner">
        <div v-if="ai.empty" class="welcome rise">
          <h2>你好，这里是小R</h2>
          <p>查台账、整理文件夹、归档和撤销都可以交给我。会改动数据的操作我先出方案，你确认后才执行，误操作可以回档。</p>
          <div v-if="settings && !ready" class="setup">
            <app-icon name="info" :size="16" />
            <span>先在“连接设置”里填好接口地址、API Key 和模型。支持 GPT、DeepSeek、通义千问等 OpenAI 兼容接口和中转站。</span>
            <n-button size="small" type="primary" @click="showSettings = true">去设置</n-button>
          </div>
        </div>

        <template v-for="t in ai.turns" :key="t.id">
          <div v-if="t.role === 'user'" class="row me">
            <div class="bubble me">{{ (t.parts[0] as any)?.text }}</div>
          </div>
          <div v-else class="row bot">
            <img src="/logo.png" class="avatar" alt="" />
            <div class="bot-body">
              <template v-for="(p, i) in t.parts" :key="i">
                <details v-if="p.kind === 'thinking' && p.text" class="thinking">
                  <summary>
                    <app-icon name="sparkle" :size="13" />
                    {{ ai.streaming && t === ai.turns[ai.turns.length - 1] && p === t.parts[t.parts.length - 1] ? '正在思考…' : '思考过程' }}
                    <span class="think-len">{{ p.text.length }} 字</span>
                  </summary>
                  <div class="think-body">{{ p.text }}</div>
                </details>
                <div v-else-if="p.kind === 'text' && p.text" class="bubble bot md" v-html="renderMarkdown(p.text)" />
                <div v-else-if="p.kind === 'tool'" class="tool" :class="p.tool.status">
                  <app-icon :name="p.tool.status === 'running' ? 'loader' : p.tool.status === 'error' ? 'close' : 'check'"
                            :size="13" :class="{ spin: p.tool.status === 'running' }" />
                  <span>{{ p.tool.label }}</span>
                  <span v-if="p.tool.summary" class="tool-sum">· {{ p.tool.summary }}</span>
                </div>
                <ai-action-card v-else-if="p.kind === 'action'" :part="p" @confirm="ai.confirm(p)" @cancel="ai.cancel(p)" />
                <a v-else-if="p.kind === 'download'" class="download ripple" :href="p.url" download>
                  <app-icon name="download" :size="16" />{{ p.label }}
                </a>
                <div v-else-if="p.kind === 'error'" class="bubble err">
                  <app-icon name="close" :size="14" />{{ p.message }}
                  <a v-if="!ready || /Key|地址|模型|代理|连不上/.test(p.message)" class="link" @click="showSettings = true">检查连接设置</a>
                </div>
                <div v-else-if="p.kind === 'stopped'" class="stopped">已停止</div>
              </template>
              <div v-if="ai.streaming && t === ai.turns[ai.turns.length - 1] && !t.parts.some((p) => p.kind === 'text' && p.text)"
                   class="typing"><i /><i /><i /><span>小R 正在处理…</span></div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <div class="composer">
      <div class="composer-box">
        <n-input v-model:value="input" type="textarea" :autosize="{ minRows: 1, maxRows: 6 }" :bordered="false"
                 :placeholder="ready ? '你好，这里是小R，有什么可以帮你？（Enter 发送，Shift + Enter 换行）' : '先在连接设置里配置模型'"
                 @keydown="onKey" />
        <n-button v-if="ai.streaming" circle secondary class="send" title="停止" @click="ai.stop()">
          <template #icon><app-icon name="stop" :size="16" /></template>
        </n-button>
        <n-button v-else circle type="primary" class="send" :disabled="!input.trim()" title="发送" @click="submit()">
          <template #icon><app-icon name="send" :size="16" /></template>
        </n-button>
      </div>
      <div v-if="ai.empty" class="chips rise">
        <button v-for="s in SUGGESTIONS" :key="s.label" class="chip ripple" :title="s.q" @click="submit(s.q)">{{ s.label }}</button>
      </div>
      <div class="hint">小R 可能会出错，修改前请看清确认卡片上的内容。台账数据只在你提问时按需发送。</div>
    </div>

    <ai-settings-drawer v-model:show="showSettings" @saved="(v) => (settings = v)" />
  </div>
</template>

<style scoped>
/* 整页高度固定（减去顶栏），只有消息区滚动，输入框始终在底部 */
.ai-page { height: calc(100vh - 56px); display: flex; flex-direction: column; }
.ai-head {
  display: flex; align-items: center; justify-content: space-between; padding: 14px 32px;
  border-bottom: 1px solid var(--border);
}
/* 还没有对话时：标题、输入框、建议居中，像 OpenAI 首页那样 */
.ai-page.empty .ai-head { border-bottom-color: transparent; }
.ai-page.empty .ai-scroll { flex: 0 0 auto; margin-top: auto; overflow: visible; }
.ai-page.empty .composer { margin-bottom: auto; padding-top: 4px; }
.who { display: flex; align-items: center; gap: 12px; }
.who-name { font-weight: 700; font-size: 16px; letter-spacing: .5px; display: flex; align-items: center; gap: 8px; }
.tag { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 999px; color: var(--accent); background: var(--accent-soft); }
.who-sub { font-size: 12px; color: var(--text-3); display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-3); }
.dot.on { background: var(--success); box-shadow: 0 0 0 3px var(--success-soft); }
.head-actions { display: flex; gap: 8px; }

.avatar { width: 30px; height: 30px; border-radius: 50%; flex: none; border: 1px solid var(--border); }
.avatar.lg { width: 40px; height: 40px; }

.ai-scroll { flex: 1; overflow-y: auto; }
.ai-inner { max-width: 880px; margin: 0 auto; padding: 24px 32px 12px; display: flex; flex-direction: column; gap: 18px; }

.welcome { text-align: center; padding: 8px 0 20px; display: flex; flex-direction: column; align-items: center; }
.welcome h2 { margin: 0 0 10px; font-size: 32px; font-weight: 700; letter-spacing: -0.5px; }
.welcome p { margin: 0; color: var(--text-2); line-height: 1.7; max-width: 560px; }
.setup {
  margin-top: 18px; display: flex; align-items: center; gap: 10px; text-align: left; font-size: 13px; color: var(--text-2);
  padding: 10px 14px; border-radius: 14px; background: var(--accent-softer); border: 1px solid var(--accent-soft); max-width: 620px;
}
.chips { max-width: 816px; margin: 14px auto 0; display: flex; flex-wrap: wrap; justify-content: center; gap: 8px; }
.chip {
  font: inherit; font-size: 13px; padding: 7px 14px; border-radius: 999px; cursor: pointer; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border); transition: background-color .15s, color .15s, border-color .15s;
}
.chip:hover { background: var(--surface-2); color: var(--text); border-color: var(--border-strong); }
.chip:active { transform: scale(.97); }

.row { display: flex; gap: 10px; animation: rise .3s cubic-bezier(.2, .7, .3, 1) both; }
.row.me { justify-content: flex-end; }
.bot-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
.bubble { padding: 10px 16px; border-radius: 20px; line-height: 1.75; font-size: 14.5px; max-width: 100%; word-break: break-word; }
/* 参考 ChatGPT：自己的问题放在浅灰气泡里，小R的回答直接排在页面上，不加框 */
.bubble.me { max-width: 76%; white-space: pre-wrap; color: var(--text); background: var(--surface-3); }
.bubble.bot { padding: 4px 2px; background: transparent; }
.bubble.err { display: inline-flex; align-items: center; gap: 8px; flex-wrap: wrap; color: var(--error); background: var(--error-soft); font-size: 13px; }
.link { color: var(--accent); cursor: pointer; text-decoration: underline; }

.tool {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); padding: 3px 10px;
  border-radius: 999px; background: var(--surface-2); border: 1px solid var(--border);
}
.tool.done { color: var(--text-2); }
.tool.done :deep(.app-icon) { color: var(--success); }
.tool.error { color: var(--error); }
.tool-sum { color: var(--text-3); }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.download {
  display: inline-flex; align-items: center; gap: 8px; padding: 8px 14px; border-radius: 10px; font-size: 13px; text-decoration: none;
  color: var(--accent); background: var(--accent-softer); border: 1px solid var(--accent-soft); transition: transform .15s;
}
.download:hover { transform: translateY(-1px); }
.stopped { font-size: 12px; color: var(--text-3); }
.typing span { margin-left: 6px; font-size: 12px; color: var(--text-3); }
.typing { display: inline-flex; align-items: center; gap: 4px; padding: 10px 14px; border-radius: 14px; background: var(--surface); border: 1px solid var(--border); }
.typing i { width: 6px; height: 6px; border-radius: 50%; background: var(--text-3); animation: blink 1.2s infinite ease-in-out; }
.typing i:nth-child(2) { animation-delay: .15s; }
.typing i:nth-child(3) { animation-delay: .3s; }
@keyframes blink { 0%, 80%, 100% { opacity: .25; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-3px); } }

.composer { padding: 10px 32px 16px; }
.composer-box {
  max-width: 816px; margin: 0 auto; display: flex; align-items: flex-end; gap: 8px; padding: 12px 12px 12px 18px; border-radius: 26px;
  background: var(--surface); border: 1px solid var(--border-strong); box-shadow: 0 8px 28px -14px rgba(0, 0, 0, .28);
  transition: border-color .15s, box-shadow .2s;
}
.ai-page.empty .composer-box { min-height: 76px; align-items: flex-end; }
.composer-box:focus-within { border-color: var(--text-3); }
.composer-box :deep(.n-input) { background: transparent; }
.send { flex: none; }
.hint { max-width: 816px; margin: 6px auto 0; font-size: 11px; color: var(--text-3); text-align: center; }

/* 小R 回复里的 Markdown */
.md :deep(p) { margin: 0 0 6px; }
.md :deep(p:last-child) { margin-bottom: 0; }
.md :deep(h3), .md :deep(h4), .md :deep(h5), .md :deep(h6) { margin: 8px 0 4px; font-size: 14px; }
.md :deep(ul), .md :deep(ol) { margin: 2px 0 6px; padding-left: 20px; }
.md :deep(li) { margin: 2px 0; }
.md :deep(code) { font-family: Consolas, monospace; font-size: 12.5px; padding: 1px 5px; border-radius: 4px; background: var(--surface-3); }
.md :deep(pre) { margin: 6px 0; padding: 10px 12px; border-radius: 8px; background: var(--surface-2); border: 1px solid var(--border); overflow-x: auto; }
.md :deep(pre code) { padding: 0; background: none; }
.md :deep(blockquote) { margin: 6px 0; padding: 2px 10px; border-left: 3px solid var(--accent-soft); color: var(--text-2); }
.md :deep(a) { color: var(--accent); }
.md :deep(.md-table) { overflow-x: auto; margin: 6px 0; }
.md :deep(table) { border-collapse: collapse; font-size: 13px; }
.md :deep(th), .md :deep(td) { border: 1px solid var(--border); padding: 5px 10px; text-align: left; }
.md :deep(th) { background: var(--surface-2); font-weight: 600; }

@media (max-width: 900px) {
  .ai-head, .composer, .ai-inner { padding-left: 16px; padding-right: 16px; }
  .welcome h2 { font-size: 26px; }
}
.thinking { max-width: 640px; font-size: 12.5px; color: var(--text-3); }
.thinking summary { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; padding: 3px 10px; border-radius: 999px;
  border: 1px solid var(--border); background: var(--surface); list-style: none; user-select: none; }
.thinking summary::-webkit-details-marker { display: none; }
.thinking[open] summary { color: var(--text-2); }
.think-len { opacity: .7; }
.think-body { margin: 6px 0 2px; padding: 8px 12px; border-left: 2px solid var(--border-strong, var(--border)); white-space: pre-wrap;
  line-height: 1.7; color: var(--text-2); max-height: 320px; overflow: auto; }
</style>
