<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NCard, NDescriptions, NDescriptionsItem, NTag, NUpload, NButton, NSpace, NTable, useMessage, type UploadFileInfo } from 'naive-ui'
import { getStatus, ocrTest, type SystemStatus, type OcrTestResult } from '../api/system'
import { errorMessage } from '../api/http'
import PageHeader from '../components/PageHeader.vue'

const message = useMessage()
const status = ref<SystemStatus | null>(null)
const ocrResult = ref<OcrTestResult | null>(null)
const testing = ref(false)

async function load() {
  try {
    status.value = await getStatus()
  } catch (e) {
    message.error(errorMessage(e))
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

onMounted(load)
</script>

<template>
  <div class="page">
    <page-header eyebrow="System" title="系统设置" desc="查看运行状态，检测 OCR 是否可用。" />
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
          <n-descriptions-item label="默认归档目录"><span class="mono">{{ status.defaultArchiveRoot }}</span></n-descriptions-item>
          <n-descriptions-item label="Java 版本">{{ status.javaVersion }}</n-descriptions-item>
          <n-descriptions-item label="临时目录"><span class="mono">{{ status.tmpDir }}</span></n-descriptions-item>
        </n-descriptions>
      </n-card>

      <n-card title="OCR 自检" size="small">
        <p class="muted" style="margin-top: 0">上传一张含文字的图片（截图、扫描件、照片），查看识别结果和耗时。</p>
        <n-upload accept="image/*" :show-file-list="false" :custom-request="() => {}" @before-upload="onPick">
          <n-button :loading="testing" type="primary">选择图片并识别</n-button>
        </n-upload>
        <div v-if="ocrResult" style="margin-top: 12px">
          <p>图片 {{ ocrResult.width }}×{{ ocrResult.height }}，识别耗时 {{ ocrResult.millis }} ms，共 {{ ocrResult.lines.length }} 行</p>
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
