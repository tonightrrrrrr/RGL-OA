<script setup lang="ts">
import { computed } from 'vue'
import { NTag, NAlert, NSpin, NTooltip } from 'naive-ui'
import type { PreviewResult, PresetResult } from '../api/ledger'
import { PLACEHOLDERS, diffParts } from '../utils/templatePresets'

/** 编辑某条规则时，在输入框下方展示它套用到真实文件后的效果，以及常用写法的对比 */
const props = defineProps<{
  kind: 'naming' | 'path' | 'archiveNo' | 'dateFormat' | 'company'
  preview: PreviewResult | null
  loading: boolean
  current: string
  readonly: boolean
}>()
const emit = defineEmits<{ (e: 'apply', pattern: string): void; (e: 'insert', token: string): void }>()

const MAX_ROWS = 6
const rows = computed(() => props.preview?.rows.slice(0, MAX_ROWS) || [])
const more = computed(() => Math.max(0, (props.preview?.rows.length || 0) - MAX_ROWS))
const firstVars = computed(() => props.preview?.rows[0]?.target.vars || null)
const presets = computed<PresetResult[]>(() => {
  if (!props.preview || props.kind === 'company') return []
  return props.preview.presets[props.kind] || []
})

/** 按目录分组：目录 → 文件数 */
const folders = computed(() => {
  const m = new Map<string, { target: number; base: number }>()
  for (const r of props.preview?.rows || []) {
    const t = m.get(r.target.dir) || { target: 0, base: 0 }
    t.target++
    m.set(r.target.dir, t)
  }
  for (const r of props.preview?.rows || []) {
    if (m.has(r.base.dir)) m.get(r.base.dir)!.base++
  }
  return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]))
})
const baseFolderCount = computed(() => new Set((props.preview?.rows || []).map((r) => r.base.dir)).size)

/** 档号重复：同一个档号出现多次 */
const dupNumbers = computed(() => {
  const c = new Map<string, number>()
  for (const r of props.preview?.rows || []) c.set(r.target.archiveNo, (c.get(r.target.archiveNo) || 0) + 1)
  return new Set([...c.entries()].filter(([, n]) => n > 1).map(([k]) => k))
})
const collidedNames = computed(() => new Set((props.preview?.collisions || []).flat()))
const titleChanged = computed(() => (props.preview?.rows || []).filter((r) => r.base.title !== r.target.title).length)

/** 占位符文本，如 “{部门}”（模板里直接写会和插值的双大括号冲突） */
function token(p: string) {
  return '{' + p + '}'
}

function short(v: string | undefined) {
  if (!v) return ''
  return ' ' + (v.length > 10 ? v.slice(0, 10) + '…' : v)
}

const TITLES = {
  naming: '命名效果', path: '目录结构', archiveNo: '档号效果', dateFormat: '日期效果', company: '标题效果'
}
</script>
<template>
  <div class="fp">
    <div class="fp-head">
      <span class="fp-title">{{ TITLES[kind] }}</span>
      <span class="muted small">用 {{ preview?.source.label || '示例文件' }} 实时计算</span>
      <n-spin v-if="loading" :size="12" />
    </div>
    <div v-if="!preview" class="muted small">正在计算…</div>
    <template v-else>
      <!-- 命名规则 -->
      <template v-if="kind === 'naming'">
        <n-alert v-if="preview.collisions.length" type="error" :show-icon="false" class="fp-alert">
          有 {{ preview.collisions.length }} 组文件会重名：{{ preview.collisions.map((g) => g.join('、')).join('；') }}。
          归档时会自动加“(2)”区分，建议在命名里加上 {标题} 或 {序号4}。
        </n-alert>
        <table class="fp-table">
          <thead><tr><th>原文件</th><th>新文件名</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.key" :class="{ bad: collidedNames.has(r.originalName) }">
              <td class="orig">{{ r.originalName }}</td>
              <td class="mono">
                <template v-if="r.base.fileName !== r.target.fileName">
                  <div class="old">{{ r.base.fileName }}</div>
                  <div>{{ diffParts(r.base.fileName, r.target.fileName)[0] }}<mark>{{ diffParts(r.base.fileName, r.target.fileName)[1] }}</mark>{{ diffParts(r.base.fileName, r.target.fileName)[2] }}</div>
                </template>
                <span v-else>{{ r.target.fileName }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 归档路径 -->
      <template v-else-if="kind === 'path'">
        <n-alert v-if="folders.length <= 1 && preview.rows.length > 1" type="warning" :show-icon="false" class="fp-alert">
          所有文件都会放在同一个目录里，不分类。
        </n-alert>
        <div class="muted small" style="margin-bottom: 4px">
          {{ preview.rows.length }} 个文件分到 {{ folders.length }} 个目录<span v-if="baseFolderCount !== folders.length">（基准模板 {{ baseFolderCount }} 个）</span>：
        </div>
        <div class="tree mono">
          <div v-for="[dir, n] in folders" :key="dir">📁 {{ dir || '（归档根目录）' }}/ <span class="muted">{{ n.target }} 个文件</span></div>
        </div>
      </template>

      <!-- 档号规则 -->
      <template v-else-if="kind === 'archiveNo'">
        <n-alert v-if="dupNumbers.size" type="error" :show-icon="false" class="fp-alert">
          有 {{ dupNumbers.size }} 个档号重复（{{ [...dupNumbers].join('、') }}），归档时只能自动加“-2”区分，建议加上 {序号4}。
        </n-alert>
        <table class="fp-table">
          <thead><tr><th>原文件</th><th>档号</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.key" :class="{ bad: dupNumbers.has(r.target.archiveNo) }">
              <td class="orig">{{ r.originalName }}</td>
              <td class="mono">
                <span v-if="r.base.archiveNo !== r.target.archiveNo" class="old" style="margin-right: 8px">{{ r.base.archiveNo }}</span>
                {{ r.target.archiveNo }}
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 日期格式 -->
      <template v-else-if="kind === 'dateFormat'">
        <table class="fp-table">
          <thead><tr><th>原文件</th><th>识别出的日期</th><th>写进文件名后</th></tr></thead>
          <tbody>
            <tr v-for="r in rows.slice(0, 4)" :key="r.key">
              <td class="orig">{{ r.originalName }}</td>
              <td>{{ r.target.date || '未识别' }}</td>
              <td class="mono">{{ r.target.vars?.['日期'] }}</td>
            </tr>
          </tbody>
        </table>
      </template>

      <!-- 公司名称 -->
      <template v-else>
        <div class="muted small" style="margin-bottom: 4px">
          标题开头的公司名会被去掉。{{ titleChanged ? `与基准模板相比，${titleChanged} 个文件的标题有变化：` : '' }}
        </div>
        <table class="fp-table">
          <thead><tr><th>原文件</th><th>识别出的标题</th></tr></thead>
          <tbody>
            <tr v-for="r in rows" :key="r.key">
              <td class="orig">{{ r.originalName }}</td>
              <td><span v-if="r.base.title !== r.target.title" class="old" style="margin-right: 8px">{{ r.base.title }}</span>{{ r.target.title }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <div v-if="more && kind !== 'path'" class="muted small">还有 {{ more }} 个文件，完整对比见右侧“效果预览”。</div>
      <!-- 占位符：显示第一个文件的实际取值，点击插入到光标处 -->
      <div v-if="(kind === 'naming' || kind === 'path' || kind === 'archiveNo') && firstVars" class="chips">
        <span class="muted small">插入占位符（括号里是“{{ preview.rows[0].originalName }}”的取值）：</span>
        <div class="chip-list">
          <n-tooltip v-for="p in PLACEHOLDERS" :key="p" :disabled="!firstVars[p]">
            <template #trigger>
              <span class="chip" :class="{ disabled: readonly }" @mousedown.prevent="!readonly && emit('insert', token(p))">
                {{ token(p) }}<em>{{ short(firstVars[p]) }}</em>
              </span>
            </template>
            {{ firstVars[p] }}
          </n-tooltip>
        </div>
      </div>

      <!-- 常用写法：套用到同一批文件后的效果 -->
      <div v-if="presets.length" class="presets">
        <div class="muted small" style="margin-bottom: 6px">常用写法（点击套用）：</div>
        <div class="preset-grid">
          <div v-for="p in presets" :key="p.pattern" class="preset" :class="{ active: p.pattern === current, disabled: readonly }"
               @mousedown.prevent="!readonly && p.pattern !== current && emit('apply', p.pattern)">
            <div class="preset-head">
              <span class="mono">{{ p.pattern }}</span>
              <n-tag v-if="p.pattern === current" size="tiny" type="primary" :bordered="false">当前</n-tag>
              <n-tag v-else-if="p.issue" size="tiny" type="warning" :bordered="false">{{ p.issue }}</n-tag>
              <n-tag v-else size="tiny" type="success" :bordered="false">
                {{ kind === 'path' ? `${p.folders} 个目录` : '不重复' }}
              </n-tag>
            </div>
            <div v-for="(ex, i) in p.examples" :key="i" class="preset-ex mono">{{ ex }}</div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.fp { margin-top: 6px; border: 1px solid var(--border); background: var(--surface-2); border-radius: 8px; padding: 10px 12px; width: 100%; box-sizing: border-box; }
.fp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.fp-title { font-weight: 600; font-size: 13px; color: var(--accent); }
.fp-alert { margin-bottom: 8px; font-size: 12px; }
.fp-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.fp-table th { text-align: left; color: var(--text-2); font-weight: normal; padding: 3px 6px; border-bottom: 1px solid var(--border); }
.fp-table td { padding: 4px 6px; border-bottom: 1px dashed var(--border); vertical-align: top; word-break: break-all; }
.fp-table tr.bad td { background: var(--error-soft); }
.orig { width: 34%; color: var(--text-2); }
.old { color: var(--text-3); text-decoration: line-through; }
mark { background: var(--warning-soft); padding: 0 1px; border-radius: 2px; }
.tree { font-size: 12px; line-height: 1.8; }
.chips { margin-top: 10px; }
.chip-list { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.chip { font-family: Consolas, monospace; font-size: 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 1px 6px; cursor: pointer; }
.chip:hover { border-color: var(--accent); color: var(--accent); }
.chip em { font-style: normal; color: var(--text-3); font-family: 'Microsoft YaHei', sans-serif; }
.chip.disabled, .preset.disabled { cursor: not-allowed; opacity: 0.6; }
.presets { margin-top: 12px; }
.preset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; }
.preset { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 6px 8px; cursor: pointer; }
.preset:hover { border-color: var(--accent); }
.preset.active { border-color: var(--accent); background: var(--accent-soft); cursor: default; }
.preset-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; font-size: 12px; margin-bottom: 4px; }
.preset-ex { font-size: 11px; color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.small { font-size: 12px; }
</style>