<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import {
  NCard, NSpace, NInput, NSelect, NDatePicker, NButton, NDataTable, NTag, NDropdown, useMessage, type DataTableColumns
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

async function load(p = page.value) {
  loading.value = true
  page.value = p
  try {
    const r = await searchLedger(query(), page.value, size.value)
    rows.value = r.items
    total.value = r.total
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.keys(q).forEach((k) => delete (q as Record<string, unknown>)[k])
  range.value = null
  load(1)
}

function exportExcel() {
  window.location.href = exportUrl(query())
}

async function open(r: LedgerEntry, folder: boolean) {
  try {
    await openEntry(r.id, folder)
  } catch (e) {
    message.error(errorMessage(e))
  }
}

const columns = computed<DataTableColumns<LedgerEntry>>(() => [
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
        <n-input v-model:value="q.keyword" placeholder="关键词，多个词用空格分隔" clearable style="width: 260px" @keyup.enter="load(1)" />
        <n-select v-model:value="q.department" :options="deptOptions" placeholder="部门" clearable style="width: 140px" />
        <n-select v-model:value="q.docType" :options="typeOptions" placeholder="类型" clearable style="width: 120px" />
        <n-date-picker v-model:value="range" type="daterange" clearable style="width: 260px" />
        <n-select v-model:value="q.format" :options="formatOptions" placeholder="格式" clearable style="width: 100px" />
        <n-select v-model:value="q.recognizeMode" :options="modeOptions" placeholder="识别方式" clearable style="width: 120px" />
        <n-button type="primary" @click="load(1)">搜索</n-button>
        <n-button @click="reset">重置</n-button>
        <n-button @click="exportExcel">导出 Excel</n-button>
      </n-space>
    </n-card>
    <n-card size="small">
      <n-data-table remote :columns="columns" :data="rows" :loading="loading" size="small" :scroll-x="2100"
                    :row-key="(r: LedgerEntry) => r.id"
                    :pagination="{ page, pageSize: size, itemCount: total, showSizePicker: true, pageSizes: [20, 50, 100],
                                   prefix: () => `共 ${total} 条` }"
                    @update:page="load" @update:page-size="(s: number) => { size = s; load(1) }" />
    </n-card>
  </div>
</template>

<style scoped>
.small { font-size: 12px; }
</style>
