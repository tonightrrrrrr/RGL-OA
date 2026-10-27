<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NCard, NSpace, NInput, NInputGroup, NButton, NSelect, NRadioGroup, NRadioButton, NUpload, NUploadDragger,
  NDataTable, NTag, NStatistic, NGrid, NGi, NModal, NAlert, NCheckbox, NText, useMessage, useDialog,
  type DataTableColumns, type UploadFileInfo
} from 'naive-ui'
import {
  createFromFolder, createFromUpload, createFromInbox, listBatches, getBatch, listFiles, previewArchive, runArchive,
  undoBatch, deleteBatch, deleteFile, chooseFolder, type Batch, type FileRecord, type PreviewItem
} from '../api/archive'
import { errorMessage } from '../api/http'
import { getImportPrefs, type ImportPrefs } from '../api/prefs'
import { useDictStore } from '../stores/dict'
import { ROLLBACK_TIP, useRollbackStore } from '../stores/rollback'
import { STATUS_META, dateTime } from '../utils/format'
import AppIcon from '../components/AppIcon.vue'
import ConfCell from '../components/ConfCell.vue'
import FileDetailDrawer from '../components/FileDetailDrawer.vue'
import PageHeader from '../components/PageHeader.vue'

const rb = useRollbackStore()
const message = useMessage()
const dialog = useDialog()
const dict = useDictStore()

const mode = ref<'folder' | 'upload'>('folder')
const sourcePath = ref('')
const targetRoot = ref('')
const templateId = ref<number | null>(null)
const uploadList = ref<UploadFileInfo[]>([])
const starting = ref(false)
/** 记住的导入习惯（v1.7.2）：归档目录、模板、导入方式、上次导入的文件夹 */
const prefs = ref<ImportPrefs | null>(null)
/** 记住的归档目录所在磁盘不存在（移动硬盘没插）：只在输入框还是这个目录时提示 */
const rootWarning = computed(() => (prefs.value && !prefs.value.available && targetRoot.value === prefs.value.archiveRoot
  ? prefs.value.unavailableReason : null))

const batches = ref<Batch[]>([])
const batch = ref<Batch | null>(null)
const files = ref<FileRecord[]>([])
const checked = ref<number[]>([])
const detailId = ref<number | null>(null)
const batchDict = ref({ departments: [] as string[], docTypes: [] as string[] })

const previewing = ref(false)
const previewItems = ref<PreviewItem[]>([])
const includeReview = ref(false)
const archiving = ref(false)
let source: EventSource | null = null
let refreshTimer: number | undefined

const isDocker = computed(() => dict.status?.mode === 'docker')
const templateOptions = computed(() => dict.templates.map((t) => ({ label: t.name + (t.builtin ? '（内置）' : ''), value: t.id })))
const batchOptions = computed(() => batches.value.map((b) => ({
  label: `#${b.id} ${dateTime(b.createdAt)} ${b.sourcePath ? '· ' + b.sourcePath : '· 上传'}（${b.templateName}）`,
  value: b.id
})))
const stats = computed(() => batch.value?.stats)
const done = computed(() => (stats.value ? Object.entries(stats.value)
  .filter(([k]) => k !== 'QUEUED' && k !== 'PARSING').reduce((s, [, v]) => s + v, 0) : 0))
const total = computed(() => (stats.value ? Object.values(stats.value).reduce((s, v) => s + v, 0) : 0))
const archivableCount = computed(() => files.value.filter((f) => ['READY', 'UNDONE'].includes(f.status)
  || (includeReview.value && f.status === 'REVIEW')).length)

onMounted(async () => {
  try {
    await dict.load()
    const def = dict.templates.find((t) => t.name === '默认模板')
    try {
      prefs.value = await getImportPrefs()
    } catch {
      prefs.value = null // 读不到就用系统默认，不影响导入
    }
    const p = prefs.value
    targetRoot.value = p?.archiveRoot || dict.status?.defaultArchiveRoot || ''
    // 上次用的模板还在就用它，被删掉了就用默认模板
    templateId.value = p?.templateId && dict.templates.some((t) => t.id === p.templateId) ? p.templateId : def ? def.id : null
    if (p?.mode) mode.value = p.mode
    batches.value = await listBatches()
    if (batches.value.length) await openBatch(batches.value[0].id)
  } catch (e) {
    message.error(errorMessage(e))
  }
})

onBeforeUnmount(() => closeStream())

async function pick(which: 'source' | 'target') {
  try {
    // 导入框为空时，从上次导入的文件夹旁边打开（导入框本身不预填，免得重复导入同一批文件）
    const initial = which === 'source' ? sourcePath.value || prefs.value?.sourceChooserStart || '' : targetRoot.value
    const r = await chooseFolder(which === 'source' ? '选择要导入的文件夹' : '选择归档目录', initial)
    if (r.path) {
      if (which === 'source') sourcePath.value = r.path
      else targetRoot.value = r.path
    }
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function start() {
  starting.value = true
  try {
    let b: Batch
    if (mode.value === 'upload') {
      const list = uploadList.value.map((u) => u.file).filter((x): x is File => !!x)
      if (!list.length) throw new Error('请先选择或拖入文件')
      b = await createFromUpload(list, targetRoot.value, templateId.value)
      uploadList.value = []
    } else if (isDocker.value && !sourcePath.value) {
      b = await createFromInbox(targetRoot.value, templateId.value)
    } else {
      b = await createFromFolder(sourcePath.value, targetRoot.value, templateId.value)
    }
    message.success(`已开始处理批次 #${b.id}`)
    getImportPrefs().then((p) => { prefs.value = p }).catch(() => {})
    batches.value = await listBatches()
    await openBatch(b.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    starting.value = false
  }
}
async function openBatch(id: number) {
  closeStream()
  checked.value = []
  batch.value = await getBatch(id)
  files.value = await listFiles(id)
  batchDict.value = await dict.dictOf(batch.value.templateId)
  if (batch.value.running) {
    // 处理进度：SSE 推送状态变化，合并刷新避免频繁请求
    source = new EventSource(`/api/batches/${id}/events`)
    source.addEventListener('file', () => scheduleRefresh())
    source.addEventListener('done', () => {
      closeStream()
      refresh()
    })
    source.onerror = () => scheduleRefresh()
  }
}

function scheduleRefresh() {
  if (refreshTimer) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = undefined
    refresh()
  }, 500)
}

async function refresh() {
  if (!batch.value) return
  const id = batch.value.id
  try {
    batch.value = await getBatch(id)
    files.value = await listFiles(id)
    if (!batch.value.running) closeStream()
  } catch (e) {
    message.error(errorMessage(e))
  }
}

function closeStream() {
  source?.close()
  source = null
}

async function openPreview() {
  if (!batch.value) return
  previewing.value = true
  try {
    previewItems.value = await previewArchive(batch.value.id, includeReview.value)
  } catch (e) {
    previewing.value = false
    message.error(errorMessage(e))
  }
}

async function doArchive(fileIds: number[] | null) {
  if (!batch.value) return
  archiving.value = true
  try {
    const r = await runArchive(batch.value.id, fileIds, includeReview.value)
    previewing.value = false
    rb.refresh()
    const msg = `归档完成：成功 ${r.archived} 个${r.skipped ? `，跳过 ${r.skipped} 个` : ''}${r.failed ? `，失败 ${r.failed} 个` : ''}`
    if (r.messages.length) {
      dialog.info({ title: msg, content: r.messages.join('\n') + (r.ledgerFile ? `\n\n台账已更新：${r.ledgerFile}` : '') })
    } else {
      message.success(msg + (r.ledgerFile ? '，台账已更新' : '') + (r.archived ? '。' + ROLLBACK_TIP : ''))
    }
    await refresh()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    archiving.value = false
  }
}

function archiveChecked() {
  const ids = checked.value
  if (!ids.length) return
  dialog.warning({
    title: '归档选中的文件',
    content: `将归档选中的 ${ids.length} 个文件（包括待确认、重复的文件）。确定继续吗？`,
    positiveText: '归档', negativeText: '取消',
    onPositiveClick: () => doArchive(ids)
  })
}

function undo() {
  if (!batch.value) return
  const id = batch.value.id
  dialog.warning({
    title: '撤销归档',
    content: '将删除本批次归档到目标目录的文件（原文件不受影响，归档后被修改过的文件会保留），并从台账中移除。确定撤销吗？',
    positiveText: '撤销', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const r = await undoBatch(id)
        rb.refresh()
        message.success(`已撤销 ${r.undone} 个文件${r.kept ? `，${r.kept} 个保留` : ''}。${ROLLBACK_TIP}`)
        if (r.messages.length) dialog.info({ title: '撤销说明', content: r.messages.join('\n') })
        await refresh()
      } catch (e) {
        message.error(errorMessage(e))
      }
    }
  })
}

function removeBatch() {
  if (!batch.value) return
  const b = batch.value
  const archived = b.stats.ARCHIVED
  dialog.warning({
    title: `删除批次 #${b.id} 的记录`,
    content: archived > 0
      ? `只删除这条导入记录，不会删除任何文件。\n本批次已归档的 ${archived} 个文件和台账记录会保留，但删除后这个批次不能再撤销。确定删除吗？`
      : '只删除这条导入记录和识别结果，原文件不受影响。确定删除吗？',
    positiveText: '删除记录', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        const r = await deleteBatch(b.id)
        rb.refresh()
        message.success(`${r.message}。${ROLLBACK_TIP}`)
        closeStream()
        batch.value = null
        files.value = []
        batches.value = await listBatches()
        if (batches.value.length) await openBatch(batches.value[0].id)
      } catch (e) {
        message.error(errorMessage(e))
      }
    }
  })
}

function removeFile(r: FileRecord) {
  dialog.warning({
    title: '从批次中移除',
    content: `移除“${r.originalName}”的识别记录，它不会参与归档。原文件不受影响。`,
    positiveText: '移除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteFile(r.id)
        rb.refresh()
        checked.value = checked.value.filter((id) => id !== r.id)
        message.success(`已移除。${ROLLBACK_TIP}`)
        await refresh()
      } catch (e) {
        message.error(errorMessage(e))
      }
    }
  })
}

const columns = computed<DataTableColumns<FileRecord>>(() => [
  { type: 'selection', disabled: (r) => ['ARCHIVED', 'QUEUED', 'PARSING', 'FAILED'].includes(r.status) },
  {
    title: '状态', key: 'status', width: 86,
    render: (r) => h(NTag, { type: STATUS_META[r.status].type, size: 'small' }, { default: () => STATUS_META[r.status].label })
  },
  {
    title: '原文件名', key: 'originalName', ellipsis: { tooltip: true }, minWidth: 220,
    render: (r) => h('a', { class: 'link', onClick: () => (detailId.value = r.id) }, r.originalName)
  },
  { title: '部门', key: 'department', width: 130, render: (r) => h(ConfCell, { value: r.department, conf: r.deptConf }) },
  { title: '类型', key: 'docType', width: 110, render: (r) => h(ConfCell, { value: r.docType, conf: r.typeConf }) },
  { title: '日期', key: 'docDate', width: 140, render: (r) => h(ConfCell, { value: r.docDate, conf: r.dateConf }) },
  {
    title: '新文件名 / 说明', key: 'targetPath', minWidth: 260, ellipsis: { tooltip: true },
    render: (r) => r.status === 'FAILED'
      ? h(NText, { type: 'error' }, { default: () => r.errorMessage })
      : r.status === 'REVIEW' ? h(NText, { type: 'warning' }, { default: () => r.reviewReasons })
        : h('span', { class: 'mono small' }, r.targetPath || '')
  },
  {
    title: '', key: 'ocr', width: 56,
    render: (r) => (r.ocrUsed ? h(NTag, { size: 'tiny', type: 'info', bordered: false }, { default: () => 'OCR' }) : null)
  },
  {
    title: '', key: 'op', width: 64,
    render: (r) => ['ARCHIVED', 'QUEUED', 'PARSING'].includes(r.status) ? null
      : h(NButton, { size: 'tiny', quaternary: true, type: 'error', onClick: () => removeFile(r) }, { default: () => '移除' })
  }
])

function rowProps(r: FileRecord) {
  return { style: r.status === 'REVIEW' ? 'background:var(--warning-soft)' : r.status === 'FAILED' ? 'background:var(--error-soft)' : '' }
}

const previewColumns: DataTableColumns<PreviewItem> = [
  { title: '原文件名', key: 'originalName', ellipsis: { tooltip: true } },
  { title: '归档位置（相对归档目录）', key: 'relativePath', ellipsis: { tooltip: true } },
  { title: '提示', key: 'conflict', width: 220, render: (r) => r.conflict ? h(NText, { type: 'warning' }, { default: () => r.conflict }) : '—' }
]
</script>

<template>
  <div class="page">
    <page-header eyebrow="Import" title="导入识别" desc="选择文件夹或上传文件，自动识别部门、类型、日期；确认无误后一键归档并生成台账。" />

    <n-card size="small" style="margin-bottom: 16px">
      <n-space vertical :size="12">
        <n-radio-group v-model:value="mode" size="small">
          <n-radio-button value="folder">{{ isDocker ? '收件箱 / 服务器路径' : '导入文件夹' }}</n-radio-button>
          <n-radio-button value="upload">上传文件</n-radio-button>
        </n-radio-group>
        <n-input-group v-if="mode === 'folder'">
          <n-input v-model:value="sourcePath" :placeholder="isDocker ? `留空则扫描收件箱 ${dict.status?.inbox || ''}` : '要导入的文件夹，如 D:\\OA待归档'" />
          <n-button v-if="!isDocker" @click="pick('source')">选择…</n-button>
        </n-input-group>
        <n-upload v-else v-model:file-list="uploadList" multiple directory-dnd :default-upload="false"
                  accept=".doc,.docx,.xls,.xlsx,.pdf,.txt,.rtf,.odt,.ods,.wps,.et,.jpg,.jpeg,.png,.bmp,.tif,.tiff,.gif,.webp">
          <n-upload-dragger>
            <div style="padding: 8px 0">把文件或文件夹拖到这里，或点击选择</div>
            <n-text depth="3" style="font-size: 12px">支持 Word、Excel、PDF、图片等格式</n-text>
          </n-upload-dragger>
        </n-upload>
        <n-space align="center" :wrap="false">
          <span class="label">归档目录</span>
          <n-input-group style="width: 520px">
            <n-input v-model:value="targetRoot" placeholder="归档结果放在哪里" />
            <n-button v-if="!isDocker" @click="pick('target')">选择…</n-button>
          </n-input-group>
          <span class="label">模板</span>
          <n-select v-model:value="templateId" :options="templateOptions" style="width: 200px" />
          <n-button type="primary" :loading="starting" @click="start">开始识别</n-button>
        </n-space>
        <div v-if="rootWarning" class="root-warn"><app-icon name="warning" :size="14" />{{ rootWarning }}</div>
        <div v-else-if="prefs && prefs.archiveRootSource !== 'SYSTEM' && targetRoot === prefs.archiveRoot" class="root-tip">
          {{ prefs.archiveRootSource === 'MANUAL' ? '默认归档目录（在系统设置里指定）' : '上次导入用的归档目录' }}，可以在系统设置里恢复系统默认
        </div>
      </n-space>
    </n-card>

    <n-card size="small">
      <template #header>
        <n-space align="center">
          <span>批次</span>
          <n-select :value="batch?.id" :options="batchOptions" style="width: 560px" size="small"
                    placeholder="还没有批次" @update:value="openBatch" />
          <n-button v-if="batch" size="small" type="error" ghost :disabled="batch.running" @click="removeBatch">
            删除记录
          </n-button>
        </n-space>
      </template>
      <template v-if="batch">
        <n-grid :cols="7" :x-gap="12" style="margin-bottom: 12px">
          <n-gi><n-statistic label="进度" :value="`${done} / ${total}`" /></n-gi>
          <n-gi><n-statistic label="待归档" :value="stats!.READY + stats!.UNDONE" /></n-gi>
          <n-gi><n-statistic label="待确认" :value="stats!.REVIEW" /></n-gi>
          <n-gi><n-statistic label="已归档" :value="stats!.ARCHIVED" /></n-gi>
          <n-gi><n-statistic label="重复" :value="stats!.DUPLICATE" /></n-gi>
          <n-gi><n-statistic label="失败" :value="stats!.FAILED" /></n-gi>
          <n-gi><n-statistic label="已跳过" :value="batch.skippedCount" /></n-gi>
        </n-grid>
        <n-alert v-if="batch.skippedCount" type="default" :show-icon="false" style="margin-bottom: 12px; font-size: 12px">
          导入时已跳过：{{ batch.skippedSummary }}
        </n-alert>
        <n-space align="center" style="margin-bottom: 12px">
          <n-checkbox v-model:checked="includeReview">同时归档“待确认”的文件</n-checkbox>
          <n-button type="primary" :disabled="batch.running || archivableCount === 0" @click="openPreview">
            预览并归档（{{ archivableCount }}）
          </n-button>
          <n-button :disabled="batch.running || checked.length === 0" @click="archiveChecked">
            归档选中（{{ checked.length }}）
          </n-button>
          <n-button :disabled="batch.running || stats!.ARCHIVED === 0" @click="undo">撤销本批次</n-button>
          <n-text depth="3" class="small">归档目录：{{ batch.targetRoot }} · 模板：{{ batch.templateName }}</n-text>
        </n-space>
        <n-data-table :columns="columns" :data="files" :row-key="(r: FileRecord) => r.id" size="small"
                      v-model:checked-row-keys="checked" :row-props="rowProps" :max-height="520" virtual-scroll />
      </template>
      <div v-else class="muted">还没有批次，在上方选择文件夹后点击“开始识别”。</div>
    </n-card>

    <n-modal v-model:show="previewing" preset="card" title="归档预览" style="width: 1000px">
      <p class="muted small" style="margin-top: 0">以下文件将复制到归档目录（原文件不动）。档号流水号在归档时分配。</p>
      <n-data-table :columns="previewColumns" :data="previewItems" size="small" :max-height="460" virtual-scroll />
      <template #footer>
        <n-space justify="end">
          <n-button @click="previewing = false">取消</n-button>
          <n-button type="primary" :loading="archiving" :disabled="previewItems.length === 0" @click="doArchive(null)">
            确认归档 {{ previewItems.length }} 个文件
          </n-button>
        </n-space>
      </template>
    </n-modal>

    <file-detail-drawer :file-id="detailId" :departments="batchDict.departments" :doc-types="batchDict.docTypes"
                        @close="detailId = null" @changed="refresh" />
  </div>
</template>

<style scoped>
.label { color: var(--text-2); font-size: 13px; white-space: nowrap; }
.small { font-size: 12px; }
/* 表格里每行都有文件名链接：平时用正文颜色，悬停才显示强调色（红白主题下整列红色太重） */
:deep(.link) { color: var(--text); cursor: pointer; transition: color .15s; }
:deep(.link:hover) { color: var(--accent); text-decoration: underline; }
.root-warn { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--error); }
.root-tip { font-size: 12px; color: var(--text-3); }
</style>