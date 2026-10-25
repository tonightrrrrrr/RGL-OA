<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import {
  NCard, NSpace, NButton, NSelect, NInput, NTag, NAlert, NUpload, NModal, NForm, NFormItem, NRadioGroup, NRadio,
  useMessage, useDialog, type UploadFileInfo
} from 'naive-ui'
import {
  listTemplates, getTemplate, createTemplate, createTemplateFrom, updateTemplate, deleteTemplate, copyTemplate,
  exportTemplateUrl, analyzeTemplate, previewTemplate, type TemplateSummary, type TemplateConfig, type TemplateIssue,
  type TemplateAnalysis, type PreviewResult
} from '../api/ledger'
import { listBatches, type Batch } from '../api/archive'
import { errorMessage } from '../api/http'
import { useDictStore } from '../stores/dict'
import { PRESETS, sourceArgs, type PreviewSourceKey } from '../utils/templatePresets'
import { dateTime } from '../utils/format'
import TemplateEditor from '../components/TemplateEditor.vue'
import TemplateAssistant from '../components/TemplateAssistant.vue'
import PageHeader from '../components/PageHeader.vue'
import { ROLLBACK_TIP, useRollbackStore } from '../stores/rollback'

const rb = useRollbackStore()
const message = useMessage()
const dialog = useDialog()
const dict = useDictStore()

const list = ref<TemplateSummary[]>([])
const current = ref<TemplateSummary | null>(null)
const name = ref('')
const cfg = ref<TemplateConfig | null>(null)
const loadedJson = ref('')
const loadedName = ref('')
const saving = ref(false)

// 智能检查与实时预览
const analysis = ref<TemplateAnalysis | null>(null)
const analyzing = ref(false)
const preview = ref<PreviewResult | null>(null)
const previewLoading = ref(false)
const baseId = ref<number | null>(null)
const baseContent = ref<string | null>(null)
const source = ref<PreviewSourceKey>('S')
const batches = ref<Batch[]>([])

const showCreate = ref(false)
const createName = ref('')
const createMode = ref<'base' | 'blank'>('base')
const createBase = ref<number | null>(null)
const creating = ref(false)
const showSaveAs = ref(false)
const saveAsName = ref('')

/** 内置模板可以随意试改、看效果，但只能“另存为新模板” */
const isBuiltin = computed(() => !!current.value?.builtin)
const dirty = computed(() => !!cfg.value && (JSON.stringify(cfg.value) !== loadedJson.value || name.value !== loadedName.value))
const errorCount = computed(() => analysis.value?.counts.error || 0)
const templateOptions = computed(() => list.value.map((t) => ({ label: t.name + (t.builtin ? '（内置）' : ''), value: t.id })))
const baseOptions = computed(() => list.value.filter((t) => t.id !== current.value?.id)
  .map((t) => ({ label: t.name + (t.builtin ? '（内置）' : ''), value: t.id })))
const baseName = computed(() => list.value.find((t) => t.id === baseId.value)?.name || '基准模板')
const sourceOptions = computed(() => [
  { label: '内置示例 · 原文件名（赛题 10 份）', value: 'S' },
  { label: '内置示例 · 文件名改成“文档N”（模拟乱命名）', value: 'R' },
  { label: '内置示例 · 反面案例（待确认、失败、重复、重名）', value: 'N' },
  ...batches.value.map((b) => ({
    label: `批次 #${b.id} ${dateTime(b.createdAt)} · ${b.sourcePath || '上传'}（${Object.values(b.stats).reduce((s, v) => s + v, 0)} 个）`,
    value: `b:${b.id}`
  }))
])
async function loadList(selectId?: number) {
  list.value = await listTemplates()
  const target = list.value.find((t) => t.id === selectId) || list.value.find((t) => t.id === current.value?.id) || list.value[0]
  if (target) await open(target.id)
}

async function open(id: number) {
  const r = await getTemplate(id)
  current.value = list.value.find((t) => t.id === id) || r
  name.value = r.name
  loadedName.value = r.name
  r.config.keywordRules = r.config.keywordRules || []
  cfg.value = r.config
  loadedJson.value = JSON.stringify(r.config)
  // 默认用“默认模板”做对比基准；正在编辑默认模板时，换成第一个其他模板
  const def = list.value.find((t) => t.builtin && t.name === '默认模板')
  if (!baseId.value || baseId.value === id) {
    baseId.value = def && def.id !== id ? def.id : baseOptions.value[0]?.value ?? null
  }
}

watch(baseId, async (id) => {
  baseContent.value = id ? (await getTemplate(id)).content : null
  scheduleRefresh(0)
})
watch(source, () => scheduleRefresh(0))
watch(cfg, () => scheduleRefresh(400), { deep: true })

/** 合并频繁的修改，400ms 后统一重新计算；用序号丢弃过期的响应 */
let timer: number | undefined
let analyzeSeq = 0
let previewSeq = 0
function scheduleRefresh(delay: number) {
  window.clearTimeout(timer)
  timer = window.setTimeout(refresh, delay)
}

function refresh() {
  if (!cfg.value) return
  const content = JSON.stringify(cfg.value)
  const a = ++analyzeSeq
  analyzing.value = true
  analyzeTemplate(content, baseContent.value)
    .then((r) => { if (a === analyzeSeq) analysis.value = r })
    .catch(() => { if (a === analyzeSeq) analysis.value = null })
    .finally(() => { if (a === analyzeSeq) analyzing.value = false })
  const p = ++previewSeq
  previewLoading.value = true
  previewTemplate(sourceArgs(source.value), content, baseContent.value, PRESETS)
    .then((r) => { if (p === previewSeq) preview.value = r })
    .catch((e) => { if (p === previewSeq) message.error('预览失败：' + errorMessage(e)) })
    .finally(() => { if (p === previewSeq) previewLoading.value = false })
}

/** 有未保存的修改时先确认 */
function confirmDiscard(): Promise<boolean> {
  if (!dirty.value) return Promise.resolve(true)
  return new Promise((resolve) => {
    dialog.warning({
      title: '有未保存的修改',
      content: isBuiltin.value ? '对内置模板的试改还没另存，放弃这些修改吗？' : '当前模板的修改还没保存，放弃这些修改吗？',
      positiveText: '放弃修改', negativeText: '继续编辑',
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false)
    })
  })
}

async function switchTo(id: number) {
  if (id === current.value?.id) return
  if (await confirmDiscard()) {
    try {
      await open(id)
    } catch (e) {
      message.error(errorMessage(e))
    }
  }
}

onBeforeRouteLeave(() => confirmDiscard())

async function refreshAll(selectId?: number) {
  await loadList(selectId)
  await dict.load(true)
}

async function save() {
  if (!current.value || !cfg.value) return
  if (isBuiltin.value) {
    openSaveAs()
    return
  }
  if (errorCount.value) {
    message.error(`模板有 ${errorCount.value} 个错误，请先按“智能检查”的提示修正`)
    return
  }
  saving.value = true
  try {
    await updateTemplate(current.value.id, name.value, JSON.stringify(cfg.value))
    message.success('已保存，新建的批次会使用新规则')
    loadedJson.value = JSON.stringify(cfg.value)
    loadedName.value = name.value
    await refreshAll(current.value.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
/** 另存为新模板：把当前编辑的内容（包括对内置模板的试改）保存成一个新模板，原模板不变 */
function openSaveAs() {
  if (errorCount.value) {
    message.error(`模板有 ${errorCount.value} 个错误，请先按“智能检查”的提示修正`)
    return
  }
  const base = name.value.trim() || '新模板'
  saveAsName.value = list.value.some((t) => t.name === base) ? base + ' - 副本' : base
  showSaveAs.value = true
}

async function doSaveAs() {
  if (!cfg.value) return
  saving.value = true
  try {
    const t = await createTemplate(saveAsName.value.trim(), JSON.stringify(cfg.value))
    showSaveAs.value = false
    loadedJson.value = JSON.stringify(cfg.value) // 试改已另存，不再提示未保存
    loadedName.value = name.value
    message.success(`已另存为“${t.name}”`)
    await refreshAll(t.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}

function openCreate() {
  createName.value = ''
  createMode.value = 'base'
  createBase.value = current.value?.id ?? list.value[0]?.id ?? null
  showCreate.value = true
}

async function doCreate() {
  if (!(await confirmDiscard())) return
  creating.value = true
  try {
    const t = await createTemplateFrom(createName.value.trim(), createMode.value === 'blank' ? null : createBase.value)
    showCreate.value = false
    loadedJson.value = JSON.stringify(cfg.value)
    message.success(`已新建“${t.name}”`)
    await refreshAll(t.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    creating.value = false
  }
}

async function copy() {
  if (!current.value || !(await confirmDiscard())) return
  try {
    const t = await copyTemplate(current.value.id)
    loadedJson.value = JSON.stringify(cfg.value)
    message.success(`已复制为“${t.name}”，可以在副本上修改`)
    await refreshAll(t.id)
  } catch (e) {
    message.error(errorMessage(e))
  }
}

function remove() {
  if (!current.value || current.value.builtin) return
  const t = current.value
  dialog.warning({
    title: '删除模板',
    content: `确定删除“${t.name}”吗？已经用这个模板导入的批次不受影响（批次里保存了模板快照）。`,
    positiveText: '删除', negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteTemplate(t.id)
        rb.refresh()
        loadedJson.value = JSON.stringify(cfg.value)
        current.value = null
        if (baseId.value === t.id) baseId.value = null
        message.success(`已删除。${ROLLBACK_TIP}`)
        await refreshAll()
      } catch (e) {
        message.error(errorMessage(e))
      }
    }
  })
}

async function importJson({ file }: { file: UploadFileInfo }) {
  if (!file.file) return false
  try {
    const text = await file.file.text()
    const base = file.name.replace(/\.json$/i, '')
    const t = await createTemplate(list.value.some((x) => x.name === base) ? base + ' - 导入' : base, text)
    message.success(`已导入模板“${t.name}”`)
    await refreshAll(t.id)
  } catch (e) {
    message.error(errorMessage(e))
  }
  return false
}

/** 智能检查里的一键修复 */
function applyFix(issue: TemplateIssue) {
  const c = cfg.value
  if (!c || !issue.fix) return
  const f = issue.fix
  if (f.kind === 'ADD_DEPT') {
    if (!c.departments.some((d) => d.name === f.value)) c.departments.push({ name: f.value, code: '', aliases: [] })
  } else if (f.kind === 'ADD_TYPE') {
    if (!c.docTypes.some((t) => t.name === f.value)) c.docTypes.push({ name: f.value, code: 'QT', suffixes: [], features: [] })
  } else if (f.kind === 'APPEND_NAMING') {
    c.namingPattern = (c.namingPattern || '') + f.value
  } else if (f.kind === 'APPEND_ARCHIVE_NO') {
    c.archiveNoPattern = (c.archiveNoPattern || '') + f.value
  } else if (f.kind === 'SET_COPY') {
    c.archiveMode = 'COPY'
  }
  message.success('已修复：' + f.label + (isBuiltin.value ? '（内置模板请另存为新模板）' : '（记得保存）'))
}

onMounted(async () => {
  try {
    await loadList()
    batches.value = await listBatches()
  } catch (e) {
    message.error(errorMessage(e))
  }
})
</script>
<template>
  <div class="page">
    <page-header eyebrow="Template" title="模板设置" desc="模板就是一套归档规则。点击左侧任意一条规则，下方会用真实文件实时显示修改后的效果；右侧“智能助手”统计自动归档、待确认、识别错和重名的数量，并和基准模板逐个文件对比。" />

    <n-card size="small" style="margin-bottom: 12px">
      <n-space align="center" justify="space-between">
        <n-space align="center">
          <span class="label">当前模板</span>
          <n-select :value="current?.id" :options="templateOptions" style="width: 240px" size="small" @update:value="switchTo" />
          <n-tag v-if="isBuiltin" size="small" :bordered="false">内置 · 改动需另存</n-tag>
          <n-tag v-if="dirty" size="small" type="warning" :bordered="false">有未保存的修改</n-tag>
        </n-space>
        <n-space>
          <n-button size="small" type="primary" ghost @click="openCreate">新建模板</n-button>
          <n-button size="small" @click="copy">复制</n-button>
          <n-upload accept=".json" :show-file-list="false" :custom-request="() => {}" @before-upload="importJson">
            <n-button size="small">导入 JSON</n-button>
          </n-upload>
          <n-button v-if="current" size="small" tag="a" :href="exportTemplateUrl(current.id)">导出 JSON</n-button>
          <n-button v-if="current && !isBuiltin" size="small" type="error" ghost @click="remove">删除</n-button>
        </n-space>
      </n-space>
    </n-card>

    <div v-if="cfg && current" class="layout">
      <n-card size="small" class="editor">
        <template #header>
          <n-space align="center" :size="8">
            <span class="label">名称</span>
            <n-input v-model:value="name" :disabled="isBuiltin" size="small" style="width: 240px" />
          </n-space>
        </template>
        <template #header-extra>
          <n-space :size="8">
            <n-button size="small" :disabled="!!errorCount" @click="openSaveAs">另存为新模板</n-button>
            <n-button v-if="!isBuiltin" type="primary" size="small" :loading="saving" :disabled="!dirty" @click="save">
              保存{{ errorCount ? `（${errorCount} 个错误）` : '' }}
            </n-button>
          </n-space>
        </template>
        <n-alert v-if="isBuiltin" type="info" :show-icon="false" style="margin-bottom: 10px">
          内置模板可以直接试改，下方和右侧会实时显示效果；满意后点“另存为新模板”保存，内置模板本身不会被修改。
        </n-alert>
        <template-editor v-model="cfg" :readonly="false" :diff="analysis?.diff || null"
                         :preview="preview" :preview-loading="previewLoading" />
      </n-card>
      <div class="side">
        <template-assistant v-model:base-id="baseId" v-model:source="source"
                            :analysis="analysis" :analyzing="analyzing" :preview="preview" :preview-loading="previewLoading"
                            :base-name="baseName" :base-options="baseOptions" :source-options="sourceOptions"
                            :readonly="false" @fix="applyFix" />
      </div>
    </div>

    <n-modal v-model:show="showCreate" preset="card" title="新建模板" style="width: 480px">
      <n-form label-placement="left" label-width="72">
        <n-form-item label="名称">
          <n-input v-model:value="createName" placeholder="如：我司归档模板（留空自动命名）" />
        </n-form-item>
        <n-form-item label="从哪开始">
          <n-radio-group v-model:value="createMode">
            <n-space vertical>
              <n-radio value="base">基于已有模板（复制它的全部规则后再改）</n-radio>
              <n-radio value="blank">空白模板（只有一个部门和两个类型，从头配置）</n-radio>
            </n-space>
          </n-radio-group>
        </n-form-item>
        <n-form-item v-if="createMode === 'base'" label="基于">
          <n-select v-model:value="createBase" :options="templateOptions" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showCreate = false">取消</n-button>
          <n-button type="primary" :loading="creating" @click="doCreate">创建</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="showSaveAs" preset="card" title="另存为新模板" style="width: 440px">
      <p class="muted small" style="margin-top: 0">把当前编辑的全部规则保存成一个新模板，“{{ current?.name }}”保持不变。</p>
      <n-input v-model:value="saveAsName" placeholder="新模板名称" @keyup.enter="doSaveAs" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="showSaveAs = false">取消</n-button>
          <n-button type="primary" :loading="saving" :disabled="!saveAsName.trim()" @click="doSaveAs">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.layout { display: flex; gap: 12px; align-items: flex-start; }
.editor { flex: 1 1 56%; min-width: 0; }
.side { flex: 1 1 44%; min-width: 0; position: sticky; top: 12px; }
.label { color: var(--text-2); font-size: 13px; white-space: nowrap; }
.small { font-size: 12px; }
</style>