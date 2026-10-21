<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NDrawer, NDrawerContent, NForm, NFormItem, NSelect, NDatePicker, NInput, NButton, NSpace, NTag, NTimeline,
  NTimelineItem, NCollapse, NCollapseItem, NAlert, NEmpty, NImage, NPagination, useMessage
} from 'naive-ui'
import { getFile, correctFile, retryFile, previewUrl, type FileDetail, type FieldResult } from '../api/archive'
import { errorMessage } from '../api/http'
import { STATUS_META, SIGNAL_LABEL, confColor, pct } from '../utils/format'

const props = defineProps<{ fileId: number | null; departments: string[]; docTypes: string[] }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'changed'): void }>()
const message = useMessage()

const detail = ref<FileDetail | null>(null)
const loading = ref(false)
const saving = ref(false)
const page = ref(1)
const previewFailed = ref(false)
const form = ref({ department: '', docType: '', docDate: null as number | null, title: '' })

const show = computed({ get: () => props.fileId !== null, set: (v) => !v && emit('close') })
const f = computed(() => detail.value?.file)
const editable = computed(() => f.value && !['ARCHIVED', 'QUEUED', 'PARSING', 'FAILED'].includes(f.value.status))
const pageCount = computed(() => f.value?.pageCount || 1)
const fields = computed(() => {
  const ev = detail.value?.evidence
  if (!ev) return []
  return [
    { key: '部门', r: ev.department },
    { key: '类型', r: ev.docType },
    { key: '日期', r: ev.date },
    { key: '标题', r: ev.title }
  ] as { key: string; r: FieldResult }[]
})

async function load(id: number) {
  loading.value = true
  previewFailed.value = false
  page.value = 1
  try {
    detail.value = await getFile(id)
    const r = detail.value.file
    form.value = {
      department: r.department || '',
      docType: r.docType || '',
      docDate: r.docDate ? new Date(r.docDate + 'T00:00:00').getTime() : null,
      title: r.title || ''
    }
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}

watch(() => props.fileId, (id) => { if (id !== null) load(id) }, { immediate: true })

function toIsoDate(ts: number | null): string | undefined {
  if (ts === null) return undefined
  const d = new Date(ts)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function save() {
  if (!f.value) return
  saving.value = true
  try {
    await correctFile(f.value.id, {
      department: form.value.department,
      docType: form.value.docType,
      docDate: toIsoDate(form.value.docDate),
      title: form.value.title
    })
    message.success('已保存，文件变为“待归档”')
    emit('changed')
    await load(f.value.id)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}

async function retry() {
  if (!f.value) return
  try {
    await retryFile(f.value.id)
    message.success('已重新加入处理队列')
    emit('changed')
    emit('close')
  } catch (e) {
    message.error(errorMessage(e))
  }
}

const deptOptions = computed(() => props.departments.map((d) => ({ label: d, value: d })))
const typeOptions = computed(() => props.docTypes.map((d) => ({ label: d, value: d })))
</script>

<template>
  <n-drawer v-model:show="show" :width="1080" placement="right">
    <n-drawer-content :title="f ? f.originalName : '文件详情'" closable :native-scrollbar="false">
      <div v-if="loading" class="muted">加载中…</div>
      <div v-else-if="f" class="detail">
        <div class="left">
          <div class="preview">
            <n-image v-if="!previewFailed" :src="previewUrl(f.id, page)" width="100%" object-fit="contain"
                     @error="previewFailed = true" />
            <pre v-else class="excerpt">{{ detail?.textExcerpt || '（无文字内容）' }}</pre>
          </div>
          <n-pagination v-if="!previewFailed && f.ext === 'pdf' && pageCount > 1" v-model:page="page"
                        :page-count="pageCount" size="small" style="margin-top: 8px" />
        </div>
        <div class="right">
          <n-space align="center" style="margin-bottom: 12px">
            <n-tag :type="STATUS_META[f.status].type">{{ STATUS_META[f.status].label }}</n-tag>
            <n-tag v-if="f.ocrUsed" type="info" size="small">已使用 OCR</n-tag>
            <n-tag v-if="f.corrected" size="small">人工修正</n-tag>
          </n-space>
          <n-alert v-if="f.status === 'FAILED'" type="error" :show-icon="false" style="margin-bottom: 12px">
            {{ f.errorMessage }}
            <div style="margin-top: 8px"><n-button size="small" @click="retry">重试</n-button></div>
          </n-alert>
          <n-alert v-if="f.reviewReasons" type="warning" :show-icon="false" style="margin-bottom: 12px">
            需要确认：{{ f.reviewReasons }}
          </n-alert>
          <n-alert v-if="f.status === 'DUPLICATE'" type="warning" :show-icon="false" style="margin-bottom: 12px">
            台账或本批次中已有内容相同的文件。默认不归档，在导入页勾选后可以强制归档。
          </n-alert>

          <n-form label-placement="left" label-width="56" size="small" :disabled="!editable">
            <n-form-item label="部门">
              <n-select v-model:value="form.department" :options="deptOptions" filterable />
            </n-form-item>
            <n-form-item label="类型">
              <n-select v-model:value="form.docType" :options="typeOptions" filterable />
            </n-form-item>
            <n-form-item label="日期">
              <n-date-picker v-model:value="form.docDate" type="date" style="width: 100%" />
            </n-form-item>
            <n-form-item label="标题">
              <n-input v-model:value="form.title" />
            </n-form-item>
          </n-form>
          <n-space v-if="editable" style="margin-bottom: 12px">
            <n-button type="primary" :loading="saving" @click="save">确认 / 保存修正</n-button>
          </n-space>
          <p class="muted small">预计新文件名：<span class="mono">{{ f.targetPath || '-' }}</span></p>

          <n-collapse :default-expanded-names="['evidence']">
            <n-collapse-item title="判断依据" name="evidence">
              <div v-for="x in fields" :key="x.key" class="field">
                <div class="field-head">
                  <b>{{ x.key }}</b>：{{ x.r.value || '未识别' }}
                  <span :style="{ color: confColor(x.r.confidence), marginLeft: '8px' }">置信度 {{ pct(x.r.confidence) }}</span>
                </div>
                <ul class="ev">
                  <li v-for="(e, i) in x.r.evidence" :key="i">
                    <n-tag size="tiny" :bordered="false">{{ SIGNAL_LABEL[e.signal] || e.signal }} {{ e.weight }}</n-tag>
                    <span class="snippet">{{ e.snippet }}</span>
                  </li>
                </ul>
                <div v-if="x.r.candidates.length > 1" class="muted small">
                  其他候选：{{ x.r.candidates.slice(1).map((c) => `${c.value} ${pct(c.score)}`).join('、') }}
                </div>
              </div>
            </n-collapse-item>
            <n-collapse-item title="扩展字段" name="extras">
              <n-empty v-if="!detail || Object.keys(detail.extras).length === 0" description="无" size="small" />
              <div v-else v-for="(v, k) in detail.extras" :key="k" class="small">{{ k }}：{{ v }}</div>
              <div v-if="f.keywords" class="small" style="margin-top: 6px">关键词：{{ f.keywords }}</div>
            </n-collapse-item>
            <n-collapse-item title="区域切分" name="regions">
              <div v-for="(l, i) in detail?.evidence?.regions || []" :key="i" class="small mono">{{ l }}</div>
            </n-collapse-item>
            <n-collapse-item title="处理过程" name="steps">
              <n-timeline size="medium">
                <n-timeline-item v-for="s in detail?.steps || []" :key="s.seq" :title="s.step"
                                 :content="s.detail" :time="s.durationMs ? s.durationMs + ' ms' : ''" />
              </n-timeline>
            </n-collapse-item>
          </n-collapse>
        </div>
      </div>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.detail { display: flex; gap: 20px; }
.left { flex: 1 1 52%; min-width: 0; }
.right { flex: 1 1 48%; min-width: 0; }
.preview { border: 1px solid var(--border); border-radius: 6px; background: var(--surface-2); min-height: 300px; max-height: 78vh; overflow: auto; }
.excerpt { white-space: pre-wrap; font-size: 13px; padding: 12px; margin: 0; font-family: inherit; }
.field { margin-bottom: 12px; }
.field-head { margin-bottom: 4px; }
.ev { margin: 0; padding-left: 16px; }
.ev li { margin: 2px 0; font-size: 12px; }
.snippet { margin-left: 6px; color: var(--text-2); }
.small { font-size: 12px; }
</style>
