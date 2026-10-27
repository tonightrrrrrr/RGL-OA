<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  NCard, NSpace, NInput, NSelect, NDatePicker, NButton, NButtonGroup, NDataTable, NTag, NDropdown, useMessage,
  type DataTableColumns, type DataTableRowKey
} from 'naive-ui'
import { searchLedger, exportUrl, openEntry, type LedgerEntry, type LedgerQuery } from '../api/ledger'
import { errorMessage } from '../api/http'
import { useDictStore } from '../stores/dict'
import { confColor, pct, fileSize, dateTime } from '../utils/format'
import PageHeader from '../components/PageHeader.vue'

const message = useMessage()
const dict = useDictStore()
const q = reactive<LedgerQuery>({})
const range = ref<[number, number] | null>(null)
const rows = ref<LedgerEntry[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const isDocker = computed(() => dict.status?.mode === 'docker')

const deptOptions = computed(() => dict.departments.map((d) => ({ label: d, value: d })))
const typeOptions = computed(() => dict.docTypes.map((d) => ({ label: d, value: d })))
const formatOptions = ['docx', 'doc', 'xlsx', 'xls', 'pdf', 'jpg', 'png'].map((f) => ({ label: f, value: f }))
const modeOptions = ['自动', '人工确认', '人工修正'].map((f) => ({ label: f, value: f }))

function iso(ts: number) {
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function query(): LedgerQuery {
  return { ...q, dateFrom: range.value ? iso(range.value[0]) : undefined, dateTo: range.value ? iso(range.value[1]) : undefined }
}

/** 每次检索编号：连续改条件时，只显示最后一次的结果，先发出的慢请求回来也不覆盖 */
let seq = 0

async function load(p = page.value) {
  const my = ++seq
  loading.value = true
  page.value = p
  try {
    const r = await searchLedger(query(), page.value, size.value)
    if (my !== seq) return
    rows.value = r.items
    total.value = r.total
  } catch (e) {
    if (my === seq) message.error(errorMessage(e))
  } finally {
    if (my === seq) loading.value = false
  }
}

/**
 * 即时检索（v1.8）：部门、类型、日期、格式、识别方式一改就检索；
 * 关键词停止输入 0.4 秒后检索，按回车立即检索。“搜索”按钮保留。
 */
let typing: ReturnType<typeof setTimeout> | undefined

function searchNow() {
  clearTimeout(typing)
  load(1)
}

watch(() => [q.department, q.docType, q.format, q.recognizeMode, range.value], searchNow)
watch(() => q.keyword, () => {
  clearTimeout(typing)
  typing = setTimeout(() => load(1), 400)
})
onBeforeUnmount(() => clearTimeout(typing))

function reset() {
  Object.keys(q).forEach((k) => delete (q as Record<string, unknown>)[k])
  range.value = null
  searchNow()
}

/** 勾选的台账记录，翻页、改每页条数后保留 */
const checked = ref<DataTableRowKey[]>([])
const MAX_SELECTED = 1000

const exportOptions = computed(() => [
  { label: `只导出勾选的 ${checked.value.length} 条`, key: 'selected', disabled: !checked.value.length },
  { label: `导出全部搜索结果（${total.value} 条）`, key: 'all' },
  { type: 'divider', key: 'd' },
  { label: '清除勾选', key: 'clear', disabled: !checked.value.length }
])

function exportExcel(kind: 'selected' | 'all' = checked.value.length ? 'selected' : 'all') {
  if (kind === 'selected') {
    if (checked.value.length > MAX_SELECTED) {
      message.warning(`一次最多导出勾选的 ${MAX_SELECTED} 条，更多请用搜索条件筛选后导出全部结果`)
      return
    }
    // 只按 id 导出，不带搜索条件：勾选后又改了搜索条件，也只导出勾选的那些
    window.location.href = exportUrl({}, checked.value.map(Number))
    message.success(`正在导出勾选的 ${checked.value.length} 条记录`)
    return
  }
  window.location.href = exportUrl(query())
}

function onExportSelect(key: string) {
  if (key === 'clear') checked.value = []
  else exportExcel(key as 'selected' | 'all')
}

async function open(r: LedgerEntry, folder: boolean) {
  try {
    await openEntry(r.id, folder)
  } catch (e) {
    message.error(errorMessage(e))
  }
}

const columns = computed<DataTableColumns<LedgerEntry>>(() => [
  { type: 'selection', fixed: 'left' },
  { title: '档号', key: 'archiveNo', width: 150, fixed: 'left' },
  { title: '标题', key: 'title', minWidth: 260, ellipsis: { tooltip: true } },
  { title: '部门', key: 'department', width: 100 },
  { title: '类型', key: 'docType', width: 84 },
  { title: '文件日期', key: 'docDate', width: 104 },
  { title: '归档位置', key: 'relativePath', minWidth: 280, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono small' }, r.relativePath) },
  { title: '原文件名', key: 'originalName', minWidth: 200, ellipsis: { tooltip: true } },
  { title: '格式', key: 'format', width: 60 },
  { title: '大小', key: 'size', width: 80, render: (r) => fileSize(r.size) },
  { title: '关键词', key: 'keywords', width: 160, ellipsis: { tooltip: true } },
  { title: '合同编号', key: 'contractNo', width: 160, ellipsis: { tooltip: true } },
  {
    title: '识别', key: 'recognizeMode', width: 120,
    render: (r) => h('span', [
      h(NTag, { size: 'tiny', bordered: false, type: r.recognizeMode === '自动' ? 'success' : 'warning' }, { default: () => r.recognizeMode }),
      h('span', { style: { color: confColor(r.confidence), marginLeft: '4px', fontSize: '11px' } }, pct(r.confidence))
    ])
  },
  { title: '归档时间', key: 'archivedAt', width: 150, render: (r) => dateTime(r.archivedAt) },
  {
    title: '', key: 'op', width: 80, fixed: 'right',
    render: (r) => isDocker.value ? null : h(NDropdown, {
      trigger: 'click',
      options: [{ label: '打开文件', key: 'file' }, { label: '打开所在文件夹', key: 'folder' }],
      onSelect: (k: string) => open(r, k === 'folder')
    }, { default: () => h(NButton, { size: 'tiny' }, { default: () => '打开' }) })
  }
])

onMounted(async () => {
  try {
    await dict.load()
  } catch (e) {
    message.error(errorMessage(e))
  }
  load(1)
})
</script>

<template>
  <div class="page">
    <page-header eyebrow="Ledger" title="文件目录台账" desc="按关键词搜索标题、文件名、关键词、合同编号、甲乙方等；导出的 Excel 中归档路径可以直接点击打开。" />
    <n-card size="small" style="margin-bottom: 16px">
      <n-space :wrap="true" align="center">
        <n-input v-model:value="q.keyword" placeholder="关键词，多个词用空格分隔，输入后自动检索" clearable style="width: 280px" @keyup.enter="searchNow" />
        <n-select v-model:value="q.department" :options="deptOptions" placeholder="部门" clearable style="width: 140px" />
        <n-select v-model:value="q.docType" :options="typeOptions" placeholder="类型" clearable style="width: 120px" />
        <n-date-picker v-model:value="range" type="daterange" clearable style="width: 260px" />
        <n-select v-model:value="q.format" :options="formatOptions" placeholder="格式" clearable style="width: 100px" />
        <n-select v-model:value="q.recognizeMode" :options="modeOptions" placeholder="识别方式" clearable style="width: 120px" />
        <n-button type="primary" :loading="loading" @click="searchNow">搜索</n-button>
        <n-button @click="reset">重置</n-button>
        <n-button-group>
          <n-button @click="exportExcel()">
            {{ checked.length ? `导出勾选的 ${checked.length} 条` : '导出 Excel' }}
          </n-button>
          <n-dropdown trigger="click" :options="exportOptions" @select="onExportSelect">
            <n-button style="padding: 0 8px" aria-label="更多导出方式">▾</n-button>
          </n-dropdown>
        </n-button-group>
      </n-space>
    </n-card>
    <transition name="page">
      <div v-if="checked.length" class="sel-bar">
        <span>已勾选 <b>{{ checked.length }}</b> 条（翻页后勾选会保留）</span>
        <n-button size="small" type="primary" @click="exportExcel('selected')">只导出这 {{ checked.length }} 条</n-button>
        <n-button size="small" quaternary @click="checked = []">清除勾选</n-button>
      </div>
    </transition>
    <n-card size="small">
      <n-data-table remote :columns="columns" :data="rows" :loading="loading" size="small" :scroll-x="2140"
                    :row-key="(r: LedgerEntry) => r.id" v-model:checked-row-keys="checked"
                    :pagination="{ page, pageSize: size, itemCount: total, showSizePicker: true, pageSizes: [20, 50, 100],
                                   prefix: () => `共 ${total} 条` }"
                    @update:page="load" @update:page-size="(s: number) => { size = s; load(1) }" />
    </n-card>
  </div>
</template>

<style scoped>
.small { font-size: 12px; }
.sel-bar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding: 8px 14px; border-radius: 10px; font-size: 13px;
  background: var(--accent-softer); border: 1px solid var(--accent-soft); color: var(--text-2);
}
.sel-bar b { color: var(--accent); }
.sel-bar > span { margin-right: auto; }
</style>
