<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NTag, NUpload, NAlert, useMessage, type UploadFileInfo } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import { errorMessage } from '../api/http'
import { compareOcr, getOcrModels, switchOcrModel, type OcrCompareResult, type OcrModelsView } from '../api/ocr'

/**
 * OCR 模型选择（v1.9.1）：三套内置模型的优缺点和实测数据，点“使用”立即切换；
 * 上传一张图片，三套模型分别识别，对比耗时、行数和置信度。
 */
const emit = defineEmits<{ (e: 'changed'): void }>()
const message = useMessage()
const view = ref<OcrModelsView | null>(null)
const busy = ref('')
const comparing = ref(false)
const cmp = ref<OcrCompareResult | null>(null)
const openText = ref<string | null>(null)

/** 三套模型里的最大值，用来画条形图 */
const maxF1 = computed(() => Math.max(...(view.value?.models.map((m) => m.hardF1) ?? [1])))
const maxMs = computed(() => Math.max(...(view.value?.models.map((m) => m.pageMs) ?? [1])))
const fastest = computed(() => {
  const ok = cmp.value?.results.filter((r) => r.millis != null) ?? []
  return ok.length ? ok.reduce((a, b) => (a.millis! <= b.millis! ? a : b)).model : ''
})

async function load() {
  try {
    view.value = await getOcrModels()
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function use(id: string) {
  busy.value = id
  try {
    view.value = await switchOcrModel(id)
    const m = view.value.models.find((x) => x.id === id)
    message.success(`已切换到 ${m?.name}，之后导入的扫描件、图片都用它识别`)
    emit('changed')
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    busy.value = ''
  }
}

async function onPick({ file }: { file: UploadFileInfo }) {
  if (!file.file) return false
  comparing.value = true
  cmp.value = null
  openText.value = null
  try {
    cmp.value = await compareOcr(file.file)
    load() // 对比时会加载没用过的模型，刷新“已加载”状态
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    comparing.value = false
  }
  return false
}

onMounted(load)
</script>

<template>
  <div v-if="view">
    <n-alert v-if="view.fallbackNote" type="warning" :show-icon="false" style="margin-bottom: 12px">{{ view.fallbackNote }}</n-alert>
    <div class="models">
      <div v-for="m in view.models" :key="m.id" class="model" :class="{ on: view.current === m.id }">
        <div class="m-head">
          <b>{{ m.name }}</b>
          <n-tag size="small" round :type="m.id === view.recommended ? 'success' : 'default'">{{ m.tag }}</n-tag>
          <span v-if="view.current === m.id" class="using"><app-icon name="check" :size="14" />正在使用</span>
        </div>
        <p class="m-sum">{{ m.summary }}</p>
        <div class="bars">
          <div class="bar-row">
            <span class="bar-label">困难页准确率</span>
            <span class="bar"><i :style="{ width: (m.hardF1 / maxF1 * 100) + '%' }" class="acc" /></span>
            <span class="bar-val">{{ (m.hardF1 * 100).toFixed(1) }}%</span>
          </div>
          <div class="bar-row">
            <span class="bar-label">A4 页耗时</span>
            <span class="bar"><i :style="{ width: (m.pageMs / maxMs * 100) + '%' }" class="time" /></span>
            <span class="bar-val">{{ (m.pageMs / 1000).toFixed(1) }} 秒</span>
          </div>
          <div class="bar-row"><span class="bar-label">模型大小</span><span class="bar-val left">{{ m.sizeMb }} MB</span></div>
        </div>
        <ul class="pc">
          <li v-for="p in m.pros" :key="p" class="pro">{{ p }}</li>
          <li v-for="c in m.cons" :key="c" class="con">{{ c }}</li>
        </ul>
        <n-button size="small" :type="view.current === m.id ? 'default' : 'primary'" :disabled="view.current === m.id"
                  :loading="busy === m.id" block @click="use(m.id)">
          {{ view.current === m.id ? '正在使用' : '使用这个模型' }}
        </n-button>
      </div>
    </div>
    <p class="muted small note">
      准确率是 v1.9.1 实测：把示例文件里的文字用行楷、隶书、舒体等手写体排版，再加旋转、阴影、噪点和模糊模拟手机拍照，和原文逐字对比。
      清晰的印刷体三套都接近 100%，差别主要在扫描件和照片上。切换立即生效，不用重启；已经导入的文件不会重新识别。
    </p>

    <div class="cmp">
      <div class="cmp-head">
        <b>用自己的图片对比</b>
        <span class="muted small">上传一张扫描件或照片，三套模型分别识别（不会改变当前选择）</span>
        <n-upload accept="image/*" :show-file-list="false" :custom-request="() => {}" @before-upload="onPick">
          <n-button size="small" :loading="comparing">选择图片对比</n-button>
        </n-upload>
      </div>
      <div v-if="cmp" class="cmp-grid">
        <div v-for="r in cmp.results" :key="r.model" class="cmp-item">
          <div class="m-head">
            <b>{{ r.name }}</b>
            <n-tag v-if="r.model === fastest" size="small" round type="info">最快</n-tag>
          </div>
          <template v-if="!r.error">
            <div class="kv"><span>耗时</span><b>{{ r.millis }} ms</b></div>
            <div class="kv"><span>识别行数 / 字数</span><b>{{ r.lineCount }} / {{ r.charCount }}</b></div>
            <div class="kv"><span>平均置信度</span><b>{{ ((r.confidence ?? 0) * 100).toFixed(1) }}%</b></div>
            <n-button size="tiny" quaternary @click="openText = openText === r.model ? null : r.model">
              {{ openText === r.model ? '收起文字' : '查看识别的文字' }}
            </n-button>
            <pre v-if="openText === r.model" class="txt">{{ r.lines?.map((l) => l.text).join('\n') || '（没有读到文字）' }}</pre>
          </template>
          <div v-else class="muted small">{{ r.error }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.models { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
.model {
  display: flex; flex-direction: column; gap: 8px; padding: 14px; border-radius: 12px; border: 1px solid var(--border);
  background: var(--surface); transition: border-color .2s, box-shadow .2s;
}
.model.on { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-ring); }
.m-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.using { margin-left: auto; display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--accent); }
.m-sum { margin: 0; font-size: 12.5px; color: var(--text-2); line-height: 1.6; }
.bars { display: flex; flex-direction: column; gap: 6px; }
.bar-row { display: grid; grid-template-columns: 84px 1fr 56px; align-items: center; gap: 8px; font-size: 12px; }
.bar-label { color: var(--text-3); }
.bar { height: 6px; border-radius: 3px; background: var(--surface-2); overflow: hidden; }
.bar i { display: block; height: 100%; border-radius: 3px; }
.bar i.acc { background: var(--success); }
.bar i.time { background: var(--warning); }
.bar-val { text-align: right; font-variant-numeric: tabular-nums; }
.bar-val.left { grid-column: 2 / 4; text-align: left; }
.pc { margin: 0; padding-left: 18px; font-size: 12px; line-height: 1.7; flex: 1; }
.pc .pro::marker { content: '＋ '; color: var(--success); }
.pc .con::marker { content: '－ '; color: var(--text-3); }
.pc .con { color: var(--text-3); }
.note { margin: 10px 0 0; line-height: 1.6; }
.cmp { margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--border); }
.cmp-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.cmp-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 12px; margin-top: 12px; }
.cmp-item { padding: 12px; border-radius: 10px; background: var(--surface-2); display: flex; flex-direction: column; gap: 4px; }
.kv { display: flex; justify-content: space-between; font-size: 12.5px; }
.kv span { color: var(--text-3); }
.txt { margin: 4px 0 0; max-height: 220px; overflow: auto; white-space: pre-wrap; font-size: 12px; line-height: 1.6; background: var(--surface); padding: 8px; border-radius: 6px; }
</style>
