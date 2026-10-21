<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NAlert, NTooltip } from 'naive-ui'
import type { PreviewResult } from '../api/ledger'

/**
 * 阈值顾问：同一批文件在不同阈值下，有多少自动归档、多少待确认、多少识别错却会被自动归档；
 * 给出推荐值，并列出置信度接近当前阈值、最容易受影响的文件。
 */
const props = defineProps<{ preview: PreviewResult | null; current: number; readonly: boolean }>()
const emit = defineEmits<{ (e: 'set', value: number): void }>()

const points = computed(() => props.preview?.thresholds || [])
const total = computed(() => props.preview?.rows.length || 0)
const hasTruth = computed(() => (props.preview?.rows || []).some((r) => r.truth))
const rec = computed(() => props.preview?.recommendation)
const near = (a: number, b: number) => Math.abs(a - b) < 0.001

/** 当前阈值下各文件的状态，按综合置信度从低到高 */
const files = computed(() => (props.preview?.rows || [])
  .map((r) => {
    const s = r.target
    const review = s.blocked || s.overall < props.current
    return {
      name: r.originalName, overall: s.overall, blocked: s.blocked, review,
      wrong: !!r.truth && s.wrong.length > 0, wrongFields: s.wrong, reasons: s.reasons
    }
  })
  .sort((a, b) => a.overall - b.overall))

function pct(v: number) {
  return (v * 100).toFixed(0) + '%'
}
</script>

<template>
  <div class="ta">
    <div class="ta-head">
      <span class="ta-title">阈值顾问</span>
      <span class="muted small">用 {{ preview?.source.label || '示例文件' }} 计算：阈值越高越稳妥，但要人工确认的文件越多</span>
    </div>
    <div v-if="!preview" class="muted small">正在计算…</div>
    <template v-else>
      <div class="chart">
        <n-tooltip v-for="p in points" :key="p.threshold">
          <template #trigger>
            <div class="col" :class="{ cur: near(p.threshold, current), rec: rec?.threshold !== null && near(p.threshold, rec?.threshold ?? -1), disabled: readonly }"
                 @click="!readonly && emit('set', p.threshold)">
              <div class="bar">
                <div class="seg review" :style="{ height: (p.review / Math.max(total, 1)) * 100 + '%' }" />
                <div class="seg auto" :style="{ height: ((p.auto - p.wrongAuto) / Math.max(total, 1)) * 100 + '%' }" />
                <div class="seg wrong" :style="{ height: (p.wrongAuto / Math.max(total, 1)) * 100 + '%' }" />
              </div>
              <div class="label">{{ p.threshold.toFixed(2) }}</div>
            </div>
          </template>
          阈值 {{ p.threshold.toFixed(2) }}：自动归档 {{ p.auto }}，待确认 {{ p.review }}<span v-if="hasTruth">，识别错却自动归档 {{ p.wrongAuto }}</span>
        </n-tooltip>
      </div>
      <div class="legend small">
        <span><i class="dot auto" />自动归档且正确</span>
        <span><i class="dot wrong" />识别错却会自动归档</span>
        <span><i class="dot review" />进入待确认</span>
        <span class="muted">点击柱子可以直接设为阈值</span>
      </div>

      <n-alert :type="rec?.threshold !== null && rec && !near(rec.threshold!, current) ? 'warning' : 'success'" :show-icon="false" class="rec">
        <div>{{ rec?.reason }}</div>
        <n-button v-if="rec?.threshold !== null && rec && !near(rec.threshold!, current) && !readonly" size="tiny" type="primary"
                  style="margin-top: 6px" @click="emit('set', rec.threshold!)">使用推荐值 {{ rec.threshold!.toFixed(2) }}</n-button>
      </n-alert>

      <div class="muted small" style="margin: 8px 0 4px">各文件的综合置信度（当前阈值 {{ current.toFixed(2) }}）：</div>
      <div class="files">
        <div v-for="f in files" :key="f.name" class="file" :class="{ review: f.review, wrong: f.wrong && !f.review }">
          <span class="fname">{{ f.name }}</span>
          <span class="meter"><span :style="{ width: pct(f.overall) }" /><i :style="{ left: pct(current) }" /></span>
          <span class="val">{{ pct(f.overall) }}</span>
          <span class="state">
            {{ f.blocked ? '待确认（' + (f.reasons[0] || '线索冲突') + '）' : f.review ? '待确认' : f.wrong ? '识别错：' + f.wrongFields.join('、') : '自动归档' }}
          </span>
        </div>
      </div>
      <div v-if="!hasTruth" class="muted small" style="margin-top: 6px">{{ preview.source.truthNote }}</div>
    </template>
  </div>
</template>
<style scoped>
.ta { margin-top: 6px; border: 1px solid var(--border); background: var(--surface-2); border-radius: 8px; padding: 10px 12px; width: 100%; box-sizing: border-box; }
.ta-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.ta-title { font-weight: 600; font-size: 13px; color: var(--accent); }
.chart { display: flex; gap: 6px; align-items: flex-end; height: 120px; padding: 0 2px; }
.col { flex: 1; display: flex; flex-direction: column; align-items: center; cursor: pointer; height: 100%; }
.col.disabled { cursor: default; }
.bar { flex: 1; width: 100%; max-width: 34px; display: flex; flex-direction: column; justify-content: flex-end; background: var(--surface-3); border-radius: 4px 4px 0 0; overflow: hidden; border: 2px solid transparent; }
.col.cur .bar { border-color: var(--accent); }
.col.rec:not(.cur) .bar { border-color: var(--warning); border-style: dashed; }
.seg.auto { background: var(--success); }
.seg.wrong { background: var(--error); }
.seg.review { background: color-mix(in srgb, var(--warning) 70%, transparent); }
.label { font-size: 11px; color: var(--text-2); margin-top: 3px; }
.col.cur .label { color: var(--accent); font-weight: 600; }
.legend { display: flex; gap: 14px; margin: 6px 0 8px; flex-wrap: wrap; color: var(--text-2); }
.dot { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 4px; vertical-align: -1px; }
.dot.auto { background: var(--success); }
.dot.wrong { background: var(--error); }
.dot.review { background: color-mix(in srgb, var(--warning) 70%, transparent); }
.rec { font-size: 12px; }
.files { display: flex; flex-direction: column; gap: 3px; max-height: 220px; overflow: auto; }
.file { display: grid; grid-template-columns: minmax(0, 1.6fr) 110px 38px minmax(0, 1.4fr); gap: 8px; align-items: center; font-size: 12px; padding: 2px 4px; border-radius: 4px; }
.file.review { background: var(--warning-soft); }
.file.wrong { background: var(--error-soft); }
.fname { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-2); }
.meter { position: relative; height: 6px; background: var(--border); border-radius: 3px; }
.meter span { position: absolute; left: 0; top: 0; bottom: 0; background: var(--info); border-radius: 3px; }
.meter i { position: absolute; top: -3px; bottom: -3px; width: 2px; background: var(--accent); }
.val { text-align: right; color: var(--text-2); }
.state { color: var(--text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.small { font-size: 12px; }
</style>