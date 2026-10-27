<script setup lang="ts">
import { computed, h, ref, type VNode } from 'vue'
import {
  NCard, NTabs, NTabPane, NSelect, NSpace, NTag, NButton, NEmpty, NTable, NBadge, NAlert, NDataTable, NRadioGroup,
  NRadioButton, NText, NSpin, type DataTableColumns
} from 'naive-ui'
import type { TemplateAnalysis, TemplateIssue, PreviewResult, PreviewRow, PreviewSide } from '../api/ledger'

/** 右侧智能助手：智能检查、与基准模板对比、全部文件的效果预览。数据由模板页统一计算后传入 */
const props = defineProps<{
  analysis: TemplateAnalysis | null
  analyzing: boolean
  preview: PreviewResult | null
  previewLoading: boolean
  baseName: string
  baseOptions: { label: string; value: number }[]
  sourceOptions: { label: string; value: string }[]
  readonly: boolean
}>()
const baseId = defineModel<number | null>('baseId', { required: true })
const source = defineModel<string>('source', { required: true })
const emit = defineEmits<{ (e: 'fix', issue: TemplateIssue): void }>()

const tab = ref('preview')
const filter = ref<'issue' | 'changed' | 'all'>('issue')

const LEVEL = {
  ERROR: { type: 'error' as const, text: '错误' },
  WARNING: { type: 'warning' as const, text: '警告' },
  INFO: { type: 'info' as const, text: '提示' }
}
const CHANGE = {
  ADDED: { type: 'success' as const, text: '新增' },
  REMOVED: { type: 'error' as const, text: '删除' },
  CHANGED: { type: 'warning' as const, text: '修改' }
}

function tabTitle(text: string, count: number, type: 'error' | 'warning' | 'info') {
  return () => h('span', { style: 'display:inline-flex;align-items:center;gap:6px' },
    count > 0 ? [text, h(NBadge, { value: count, type })] : [text])
}
const checkTab = computed(() => {
  const c = props.analysis?.counts
  return tabTitle('智能检查', c ? c.error + c.warning : 0, c?.error ? 'error' : 'warning')
})
const diffTab = computed(() => tabTitle('对比', props.analysis?.diff?.total || 0, 'info'))
const previewTab = computed(() => {
  const t = props.preview?.target
  return tabTitle('效果预览', t ? t.wrongAuto + t.collisions : 0, 'error')
})

const collided = computed(() => new Set((props.preview?.collisions || []).flat()))
const rows = computed(() => (props.preview?.rows || []).filter((r) => {
  if (filter.value === 'all') return true
  if (filter.value === 'changed') return r.changed.length > 0
  return r.target.review || r.target.wrong.length > 0 || collided.value.has(r.originalName)
}))

function fields(s: PreviewSide) {
  return `${s.department} · ${s.docType} · ${s.date || '无日期'}`
}

/** 值相同只显示一个；不同则显示“基准（划掉）→ 当前” */
function cell(base: string, target: string, mono = false) {
  const cls = mono ? 'mono small' : 'small'
  if (base === target) return h('span', { class: cls }, target || '—')
  return h('div', { class: cls }, [h('div', { class: 'old' }, base || '—'), h('div', { class: 'new' }, target || '—')])
}

function status(r: PreviewRow) {
  const s = r.target
  const tags: VNode[] = []
  if (r.truth && s.wrong.length) tags.push(h(NTag, { size: 'tiny', type: 'error', bordered: false }, { default: () => '错：' + s.wrong.join('、') }))
  if (s.review) tags.push(h(NTag, { size: 'tiny', type: 'warning', bordered: false }, { default: () => '待确认' }))
  if (collided.value.has(r.originalName)) tags.push(h(NTag, { size: 'tiny', type: 'error', bordered: false }, { default: () => '重名' }))
  if (!tags.length) tags.push(h(NTag, { size: 'tiny', type: 'success', bordered: false }, { default: () => '自动归档' }))
  return h(NSpace, { size: 2, vertical: true }, { default: () => tags })
}

const columns: DataTableColumns<PreviewRow> = [
  { title: '文件', key: 'originalName', width: 120, ellipsis: { tooltip: true } },
  { title: '部门 · 类型 · 日期', key: 'fields', width: 140, render: (r) => cell(fields(r.base), fields(r.target)) },
  { title: '归档位置', key: 'path', className: 'path-col', render: (r) => cell(r.base.relativePath, r.target.relativePath, true) },
  { title: '结果', key: 'status', width: 84, render: status }
]
</script>
<template>
  <n-card size="small" class="assistant">
    <template #header>
      <n-space align="center" :size="8">
        <span class="title">智能助手</span>
        <n-spin v-if="analyzing || previewLoading" :size="14" />
      </n-space>
    </template>
    <div class="selectors">
      <div><span class="muted small">示例文件</span>
        <n-select v-model:value="source" :options="sourceOptions" size="small" style="width: 100%" /></div>
      <div><span class="muted small">对比基准</span>
        <n-select v-model:value="baseId" :options="baseOptions" size="small" style="width: 100%" placeholder="选择模板" /></div>
    </div>

    <n-tabs v-model:value="tab" type="segment" size="small">
      <n-tab-pane name="preview" :tab="previewTab">
        <n-empty v-if="!preview" description="正在计算…" />
        <template v-else>
          <div class="stats">
            <div class="stat"><b>{{ preview.target.auto - preview.target.wrongAuto }}</b><span>自动归档</span>
              <em v-if="preview.base.auto !== preview.target.auto">基准 {{ preview.base.auto - preview.base.wrongAuto }}</em></div>
            <div class="stat warn"><b>{{ preview.target.review }}</b><span>待确认</span>
              <em v-if="preview.base.review !== preview.target.review">基准 {{ preview.base.review }}</em></div>
            <div class="stat bad"><b>{{ preview.target.wrongAuto }}</b><span>识别错却自动归档</span>
              <em v-if="preview.base.wrongAuto !== preview.target.wrongAuto">基准 {{ preview.base.wrongAuto }}</em></div>
            <div class="stat bad"><b>{{ preview.target.collisions }}</b><span>重名组</span>
              <em v-if="preview.base.collisions !== preview.target.collisions">基准 {{ preview.base.collisions }}</em></div>
          </div>
          <n-radio-group v-model:value="filter" size="small" style="margin: 8px 0">
            <n-radio-button value="issue">需要关注</n-radio-button>
            <n-radio-button value="changed">与基准不同</n-radio-button>
            <n-radio-button value="all">全部 {{ preview.rows.length }}</n-radio-button>
          </n-radio-group>
          <n-alert v-if="!rows.length && filter === 'issue'" type="success" :show-icon="false">
            全部 {{ preview.rows.length }} 个文件都能自动归档，没有识别错、待确认或重名的文件。
          </n-alert>
          <n-alert v-else-if="!rows.length" type="info" :show-icon="false">与“{{ baseName }}”相比，所有文件的结果都一样。</n-alert>
          <n-data-table v-else :columns="columns" :data="rows" size="small" :max-height="440" :row-key="(r: PreviewRow) => r.key" />
          <n-text depth="3" class="small" style="display: block; margin-top: 6px">
            划线为“{{ baseName }}”的结果，绿色为当前模板。{{ preview.source.truthNote }}。只是预览，不修改任何文件和记录。
          </n-text>
          <p v-if="preview.truncated" class="muted small">只预览了前 30 个文件。</p>
          <p v-if="preview.skipped.length" class="muted small">已跳过：{{ preview.skipped.join('；') }}</p>
        </template>
      </n-tab-pane>

      <n-tab-pane name="check" :tab="checkTab">
        <template v-if="analysis">
          <n-alert v-if="analysis.issues.length === 0" type="success" :show-icon="false">没有发现问题，可以放心使用。</n-alert>
          <n-alert v-else-if="analysis.counts.error" type="error" :show-icon="false" class="summary">
            有 {{ analysis.counts.error }} 个错误需要先修正才能保存，另有 {{ analysis.counts.warning }} 个警告、{{ analysis.counts.info }} 个提示。
          </n-alert>
          <n-alert v-else type="info" :show-icon="false" class="summary">
            可以保存。有 {{ analysis.counts.warning }} 个警告、{{ analysis.counts.info }} 个提示，建议看一下。
          </n-alert>
          <div v-for="(it, i) in analysis.issues" :key="i" class="issue" :class="it.level.toLowerCase()">
            <div class="issue-head">
              <n-tag :type="LEVEL[it.level].type" size="tiny" :bordered="false">{{ LEVEL[it.level].text }}</n-tag>
              <n-tag size="tiny" :bordered="false">{{ it.area }}</n-tag>
              <span class="msg">{{ it.message }}</span>
            </div>
            <div class="sug">{{ it.suggestion }}</div>
            <n-button v-if="it.fix" size="tiny" secondary type="primary" :disabled="readonly" style="margin-top: 4px"
                      @click="emit('fix', it)">{{ it.fix.label }}</n-button>
          </div>
        </template>
        <n-empty v-else description="正在分析…" />
      </n-tab-pane>
      <n-tab-pane name="diff" :tab="diffTab">
        <n-empty v-if="!analysis" description="正在分析…" />
        <n-alert v-else-if="!analysis.diff || analysis.diff.total === 0" type="success" :show-icon="false">
          与“{{ baseName }}”完全相同。
        </n-alert>
        <template v-else>
          <p class="muted small" style="margin-top: 0">以“{{ baseName }}”为基准，当前模板有 {{ analysis.diff.total }} 处不同：</p>
          <template v-if="analysis.diff.settings.length">
            <div class="section-title">命名与归档设置</div>
            <div v-for="s in analysis.diff.settings" :key="s.field" class="change">
              <b>{{ s.label }}</b>
              <div class="mono small"><span class="old">{{ s.base || '（空）' }}</span> → <span class="new">{{ s.target || '（空）' }}</span></div>
            </div>
          </template>
          <template v-if="analysis.diff.departments.length">
            <div class="section-title">部门词典</div>
            <div v-for="d in analysis.diff.departments" :key="'d' + d.name" class="change">
              <n-tag :type="CHANGE[d.change].type" size="tiny" :bordered="false">{{ CHANGE[d.change].text }}</n-tag>
              <b style="margin-left: 6px">{{ d.name }}</b>
              <span v-if="d.detail" class="muted small" style="margin-left: 6px">{{ d.detail }}</span>
            </div>
          </template>
          <template v-if="analysis.diff.docTypes.length">
            <div class="section-title">类型词典</div>
            <div v-for="d in analysis.diff.docTypes" :key="'t' + d.name" class="change">
              <n-tag :type="CHANGE[d.change].type" size="tiny" :bordered="false">{{ CHANGE[d.change].text }}</n-tag>
              <b style="margin-left: 6px">{{ d.name }}</b>
              <span v-if="d.detail" class="muted small" style="margin-left: 6px">{{ d.detail }}</span>
            </div>
          </template>
          <template v-if="analysis.diff.positions?.length">
            <div class="section-title">岗位规则</div>
            <div v-for="k in analysis.diff.positions" :key="'p' + k.department" class="change">
              <b>{{ k.department || '（未指定部门）' }}</b>
              <n-space :size="4" style="margin-top: 4px">
                <n-tag v-for="w in k.added" :key="'+' + w" type="success" size="tiny" :bordered="false">+ {{ w }}</n-tag>
                <n-tag v-for="w in k.removed" :key="'-' + w" type="error" size="tiny" :bordered="false">− {{ w }}</n-tag>
              </n-space>
            </div>
          </template>
          <template v-if="analysis.diff.keywords.length">
            <div class="section-title">关键词规则</div>
            <div v-for="k in analysis.diff.keywords" :key="'k' + k.department" class="change">
              <b>{{ k.department || '（未指定部门）' }}</b>
              <n-space :size="4" style="margin-top: 4px">
                <n-tag v-for="w in k.added" :key="'+' + w" type="success" size="tiny" :bordered="false">+ {{ w }}</n-tag>
                <n-tag v-for="w in k.removed" :key="'-' + w" type="error" size="tiny" :bordered="false">− {{ w }}</n-tag>
              </n-space>
            </div>
          </template>
        </template>
        <template v-if="analysis?.samples.length">
          <div class="section-title">两份示例文件的命名对照</div>
          <n-table size="small" :single-line="false" class="samples">
            <thead><tr><th>示例</th><th>当前模板</th><th>{{ baseName }}</th></tr></thead>
            <tbody>
              <tr v-for="s in analysis.samples" :key="s.original">
                <td><div>{{ s.original }}</div><div class="muted small">{{ s.fields }}</div></td>
                <td :class="{ diffcell: s.base && s.base !== s.target }"><div class="mono small">{{ s.target }}</div></td>
                <td><div class="mono small">{{ s.base }}</div></td>
              </tr>
            </tbody>
          </n-table>
        </template>
      </n-tab-pane>
    </n-tabs>
  </n-card>
</template>

<style scoped>
.assistant { height: 100%; }
.title { font-weight: 600; }
.small { font-size: 12px; }
.selectors { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 10px; }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; }
.stat { background: var(--success-soft); border-radius: 6px; padding: 6px 8px; display: flex; flex-direction: column; }
.stat b { font-size: 20px; color: var(--success); line-height: 1.2; }
.stat span { font-size: 12px; color: var(--text-2); }
.stat em { font-style: normal; font-size: 11px; color: var(--text-3); }
.stat.warn { background: var(--warning-soft); }
.stat.warn b { color: var(--warning); }
.stat.bad { background: var(--error-soft); }
.stat.bad b { color: var(--error); }
.summary { margin-bottom: 10px; }
.issue { border-left: 3px solid var(--border); padding: 6px 10px; margin-bottom: 8px; background: var(--surface-2); border-radius: 0 6px 6px 0; }
.issue.error { border-left-color: var(--error); }
.issue.warning { border-left-color: var(--warning); }
.issue.info { border-left-color: var(--info); }
.issue-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.msg { font-size: 13px; }
.sug { font-size: 12px; color: var(--text-2); margin-top: 2px; }
.section-title { font-weight: 600; font-size: 13px; margin: 14px 0 6px; }
.change { padding: 6px 0; border-bottom: 1px dashed var(--border); }
.samples td { vertical-align: top; }
.diffcell { background: var(--success-soft); }
:deep(.path-col) { word-break: break-all; }
:deep(.old) { color: var(--text-3); text-decoration: line-through; }
:deep(.new) { color: var(--success); }
</style>