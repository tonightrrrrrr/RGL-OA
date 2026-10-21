<script setup lang="ts">
import { computed, ref } from 'vue'
import { NAlert, NSwitch, NSpin } from 'naive-ui'
import type { PreviewResult } from '../api/ledger'

/** 修改部门词典、类型词典、关键词规则时，实时显示对真实文件识别结果的影响 */
const props = defineProps<{ preview: PreviewResult | null; loading: boolean; field: 'department' | 'docType' }>()

const label = computed(() => (props.field === 'department' ? '部门' : '类型'))
const onlyIssues = ref(true)

const rows = computed(() => (props.preview?.rows || []).map((r) => {
  const b = props.field === 'department' ? r.base.department : r.base.docType
  const t = props.field === 'department' ? r.target.department : r.target.docType
  const conf = props.field === 'department' ? r.target.deptConf : r.target.typeConf
  const truth = r.truth ? (props.field === 'department' ? r.truth.department : r.truth.docType) : null
  return {
    key: r.key, name: r.originalName, base: b, target: t, conf, truth,
    changed: b !== t, wrong: truth !== null && truth !== t, review: r.target.review
  }
}))
const shown = computed(() => rows.value.filter((r) => !onlyIssues.value || r.changed || r.wrong || r.review))
const stats = computed(() => ({
  correct: rows.value.filter((r) => r.truth !== null && !r.wrong).length,
  wrong: rows.value.filter((r) => r.wrong).length,
  changed: rows.value.filter((r) => r.changed).length,
  known: rows.value.filter((r) => r.truth !== null).length
}))
</script>

<template>
  <div class="ri">
    <div class="ri-head">
      <span class="ri-title">{{ label }}识别效果</span>
      <span class="muted small">用 {{ preview?.source.label || '示例文件' }} 实时计算</span>
      <n-spin v-if="loading" :size="12" />
    </div>
    <div v-if="!preview" class="muted small">正在计算…</div>
    <template v-else>
      <n-alert :type="stats.wrong ? 'error' : stats.changed ? 'warning' : 'success'" :show-icon="false" class="small">
        <template v-if="stats.known">{{ label }}识别正确 {{ stats.correct }}/{{ stats.known }}<span v-if="stats.wrong">，<b>{{ stats.wrong }} 个识别错</b></span>；</template>
        与基准模板相比，{{ stats.changed }} 个文件的{{ label }}有变化。
        <span v-if="stats.wrong">可以给正确的部门补充别名或关键词，再看这里的变化。</span>
      </n-alert>
      <div class="toolbar small"><n-switch v-model:value="onlyIssues" size="small" />只看有变化、识别错或待确认的文件</div>
      <table class="ri-table">
        <thead><tr><th>文件</th><th>基准模板</th><th>当前模板</th><th>置信度</th><th v-if="stats.known">正确答案</th></tr></thead>
        <tbody>
          <tr v-for="r in shown" :key="r.key" :class="{ wrong: r.wrong, changed: r.changed && !r.wrong }">
            <td class="name">{{ r.name }}</td>
            <td :class="{ old: r.changed }">{{ r.base }}</td>
            <td><b v-if="r.changed">{{ r.target }}</b><span v-else>{{ r.target }}</span><span v-if="r.review" class="tag">待确认</span></td>
            <td>{{ (r.conf * 100).toFixed(0) }}%</td>
            <td v-if="stats.known">{{ r.truth ?? '—' }}</td>
          </tr>
          <tr v-if="!shown.length"><td colspan="5" class="muted">没有需要关注的文件</td></tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<style scoped>
.ri { margin-bottom: 10px; border: 1px solid var(--border); background: var(--surface-2); border-radius: 8px; padding: 10px 12px; }
.ri-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ri-title { font-weight: 600; font-size: 13px; color: var(--accent); }
.toolbar { display: flex; align-items: center; gap: 6px; margin: 8px 0 4px; }
.ri-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ri-table th { text-align: left; color: var(--text-2); font-weight: normal; padding: 3px 6px; border-bottom: 1px solid var(--border); }
.ri-table td { padding: 4px 6px; border-bottom: 1px dashed var(--border); }
.ri-table tr.wrong td { background: var(--error-soft); }
.ri-table tr.changed td { background: var(--success-soft); }
.name { max-width: 220px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--text-2); }
.old { color: var(--text-3); text-decoration: line-through; }
.tag { margin-left: 6px; font-size: 11px; color: var(--warning); }
.small { font-size: 12px; }
</style>
