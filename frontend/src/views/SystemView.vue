<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NCard, NDescriptions, NDescriptionsItem, NTag, NUpload, NButton, NSpace, NSwitch, NTable, useMessage, type UploadFileInfo } from 'naive-ui'
import { getStatus, ocrTest, type SystemStatus, type OcrTestResult } from '../api/system'
import { errorMessage } from '../api/http'
import { getImportPrefs, resetDefaultArchiveRoot, setDefaultArchiveRoot, type ImportPrefs } from '../api/prefs'
import { chooseFolder } from '../api/archive'
import { useDictStore } from '../stores/dict'
import PageHeader from '../components/PageHeader.vue'
import OcrModelPicker from '../components/OcrModelPicker.vue'
import { useUpdateStore } from '../stores/update'

const message = useMessage()
const update = useUpdateStore()
const STATE_TEXT: Record<string, string> = {
  NEVER: '未检查', CHECKING: '检查中', UP_TO_DATE: '已是最新', AVAILABLE: '有新版本', OFFLINE: '未联网', ERROR: '检查失败', DISABLED: '已关闭自动检查'
}
const stateText = computed(() => STATE_TEXT[update.info?.state || 'NEVER'])
const tagType = computed(() => ({ AVAILABLE: 'success', UP_TO_DATE: 'info', OFFLINE: 'default', ERROR: 'warning' } as Record<string, any>)[update.info?.state || ''] || 'default')
const checkedAt = computed(() => (update.info?.checkedAt || '').replace('T', ' ').slice(0, 16))

async function manualCheck() {
  try {
    const r = await update.check()
    if (r?.state === 'AVAILABLE') update.showDialog = true
    else if (r?.state === 'UP_TO_DATE') message.success('已是最新版本')
    else if (r) message.info(r.message)
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function toggleAuto(on: boolean) {
  try {
    await update.setAutoCheck(on)
  } catch (e) {
    message.error(errorMessage(e))
  }
}
const status = ref<SystemStatus | null>(null)
const ocrResult = ref<OcrTestResult | null>(null)
const testing = ref(false)
/** 默认归档目录（v1.7.2）：记住上次导入用的，也可以手动指定或恢复系统默认 */
const prefs = ref<ImportPrefs | null>(null)
const rootBusy = ref(false)
const dict = useDictStore()
const ROOT_SOURCE: Record<string, string> = { LAST_USED: '上次导入用的', MANUAL: '手动指定', SYSTEM: '系统默认' }

async function load() {
  try {
    const [st, p] = await Promise.all([getStatus(), getImportPrefs()])
    status.value = st
    prefs.value = p
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function changeRoot() {
  try {
    const r = await chooseFolder('选择默认归档目录', prefs.value?.archiveRoot)
    if (!r.path) return
    rootBusy.value = true
    prefs.value = await setDefaultArchiveRoot(r.path)
    dict.load(true)
    message.success('已设为默认归档目录，导入识别页会默认用它')
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    rootBusy.value = false
  }
}

async function resetRoot() {
  rootBusy.value = true
  try {
    prefs.value = await resetDefaultArchiveRoot()
    dict.load(true)
    message.success('已恢复系统默认归档目录')
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    rootBusy.value = false
  }
}

async function onPick({ file }: { file: UploadFileInfo }) {
  if (!file.file) return false
  testing.value = true
  ocrResult.value = null
  try {
    ocrResult.value = await ocrTest(file.file)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    testing.value = false
  }
  return false
}

onMounted(() => {
  load()
  update.refresh()
})
</script>

<template>
  <div class="page">
    <page-header eyebrow="System" title="系统设置" desc="查看运行状态，检查更新，选择 OCR 模型、检测 OCR 是否可用。" />
    <n-space vertical :size="16">
      <n-card title="运行状态" size="small">
        <n-descriptions v-if="status" :column="2" label-placement="left" bordered size="small">
          <n-descriptions-item label="程序">{{ status.appName }} v{{ status.version }}</n-descriptions-item>
          <n-descriptions-item label="运行模式">{{ status.mode === 'docker' ? 'Docker' : '桌面版' }}</n-descriptions-item>
          <n-descriptions-item label="OCR">
            <n-tag :type="status.ocrAvailable ? 'success' : 'error'" size="small">
              {{ status.ocrAvailable ? '可用' : '不可用' }}
            </n-tag>
            <span style="margin-left: 8px">{{ status.ocrMessage }}</span>
          </n-descriptions-item>
          <n-descriptions-item label="端口">{{ status.port }}</n-descriptions-item>
          <n-descriptions-item label="数据目录"><span class="mono">{{ status.home }}</span></n-descriptions-item>
          <n-descriptions-item label="默认归档目录">
            <div class="root-row">
              <span class="mono">{{ prefs?.archiveRoot || status.defaultArchiveRoot }}</span>
              <n-tag v-if="prefs" size="small" round :type="prefs.available ? 'default' : 'error'">
                {{ prefs.available ? ROOT_SOURCE[prefs.archiveRootSource] : '磁盘不存在' }}
              </n-tag>
              <n-button v-if="status.mode !== 'docker'" size="tiny" secondary :loading="rootBusy" @click="changeRoot">更改</n-button>
              <n-button v-if="prefs && prefs.archiveRootSource !== 'SYSTEM'" size="tiny" quaternary :disabled="rootBusy" @click="resetRoot">
                恢复系统默认
              </n-button>
            </div>
            <div v-if="prefs && prefs.archiveRootSource !== 'SYSTEM'" class="root-sub">系统默认：<span class="mono">{{ prefs.systemDefaultRoot }}</span></div>
          </n-descriptions-item>
          <n-descriptions-item label="Java 版本">{{ status.javaVersion }}</n-descriptions-item>
          <n-descriptions-item label="临时目录"><span class="mono">{{ status.tmpDir }}</span></n-descriptions-item>
        </n-descriptions>
      </n-card>

      <n-card title="检查更新" size="small">
        <div class="upd">
          <div class="upd-main">
            <div class="upd-line">
              <span>当前版本 <b>v{{ status?.version }}</b></span>
              <n-tag v-if="update.info" size="small" :type="tagType" round>{{ stateText }}</n-tag>
            </div>
            <div class="muted small">
              {{ update.info?.message }}<template v-if="checkedAt"> · 上次检查 {{ checkedAt }}</template>
            </div>
            <div class="muted small">新版本发布在 GitHub（{{ update.info?.repo }}）。检查时只请求版本信息，不发送任何台账或文件数据；没联网不影响使用。</div>
          </div>
          <div class="upd-act">
            <n-button v-if="update.available" type="primary" @click="update.showDialog = true">查看新版本 v{{ update.info?.latest }}</n-button>
            <n-button :loading="update.checking" @click="manualCheck">检查更新</n-button>
          </div>
        </div>
        <div class="upd-auto">
          <n-switch :value="update.info?.autoCheck ?? true" size="small" @update:value="toggleAuto" />
          <span>启动时自动检查（每天最多一次）</span>
        </div>
      </n-card>

      <n-card title="OCR 模型" size="small">
        <p class="muted" style="margin-top: 0">扫描件、图片和没有文字层的 PDF 用 OCR 识别。内置三套离线模型，按需要选择，随时可以换回来。</p>
        <ocr-model-picker @changed="load" />
      </n-card>

      <n-card title="OCR 自检" size="small">
        <p class="muted" style="margin-top: 0">上传一张含文字的图片（截图、扫描件、照片），用当前模型识别，查看结果和耗时。</p>
        <n-upload accept="image/*" :show-file-list="false" :custom-request="() => {}" @before-upload="onPick">
          <n-button :loading="testing" type="primary">选择图片并识别</n-button>
        </n-upload>
        <div v-if="ocrResult" style="margin-top: 12px">
          <p>图片 {{ ocrResult.width }}×{{ ocrResult.height }}，{{ ocrResult.model }} 识别耗时 {{ ocrResult.millis }} ms，共 {{ ocrResult.lines.length }} 行</p>
          <n-table size="small" :single-line="false">
            <thead><tr><th style="width: 80px">置信度</th><th>文字</th></tr></thead>
            <tbody>
              <tr v-for="(l, i) in ocrResult.lines" :key="i">
                <td>{{ l.confidence.toFixed(2) }}</td>
                <td>{{ l.text }}</td>
              </tr>
            </tbody>
          </n-table>
        </div>
      </n-card>
    </n-space>
  </div>
</template>

<style scoped>
.upd { display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; }
.upd-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1; }
.upd-line { display: flex; align-items: center; gap: 10px; }
.upd-act { display: flex; gap: 8px; }
.upd-auto { display: flex; align-items: center; gap: 8px; margin-top: 12px; font-size: 13px; color: var(--text-2); }
.root-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.root-sub { margin-top: 4px; font-size: 12px; color: var(--text-3); }
</style>
