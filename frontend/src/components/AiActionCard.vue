<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import type { Part } from '../stores/ai'

/** 小R 提出的修改：列出改前改后、示例文件上的效果和风险提示，用户确认后才执行 */
const props = defineProps<{ part: Extract<Part, { kind: 'action' }> }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const router = useRouter()

const a = computed(() => props.part.action)
const stats = computed(() => {
  const s = a.value.stats || {}
  if (a.value.kind !== 'template' || s.autoAfter == null) return []
  const row = (label: string, before: number | null | undefined, after: number | null | undefined, bad = false) => ({
    label, before, after, changed: before != null && before !== after,
    worse: before != null && after != null && (bad ? after > before : after < before)
  })
  return [
    row('自动归档', s.autoBefore, s.autoAfter),
    row('待确认', s.reviewBefore, s.reviewAfter, true),
    { label: '识别错却自动归档', before: null, after: s.wrongAutoAfter, changed: false, worse: (s.wrongAutoAfter ?? 0) > 0 },
    { label: '重名', before: null, after: s.collisionsAfter, changed: false, worse: (s.collisionsAfter ?? 0) > 0 }
  ]
})
const stateLabel = computed(() => ({
  pending: '等你确认', working: '正在执行…', done: '已完成', cancelled: '已取消', failed: '执行失败'
})[props.part.state])
</script>

<template>
  <div class="card" :class="part.state">
    <div class="card-head">
      <span class="card-icon"><app-icon :name="a.kind === 'template' ? 'template' : 'review'" :size="16" /></span>
      <div class="card-title">{{ a.title }}</div>
      <span class="card-state">{{ stateLabel }}</span>
    </div>
    <p v-if="a.summary" class="card-reason">{{ a.summary }}</p>

    <table v-if="a.changes.length" class="changes">
      <tr v-for="(c, i) in a.changes" :key="i">
        <th>{{ c.label }}</th>
        <template v-if="c.before">
          <td><span class="old">{{ c.before }}</span></td>
          <td class="arrow">→</td>
        </template>
        <td :colspan="c.before ? 1 : 3"><span v-if="c.after" class="new">{{ c.after }}</span><span v-else class="muted">—</span></td>
      </tr>
    </table>

    <div v-if="stats.length" class="stats">
      <div class="stats-cap">用内置 {{ a.stats.files }} 份示例试算</div>
      <div class="stats-row">
        <div v-for="s in stats" :key="s.label" class="stat" :class="{ worse: s.worse }">
          <b>{{ s.after ?? '-' }}</b>
          <span>{{ s.label }}<template v-if="s.changed">（原 {{ s.before }}）</template></span>
        </div>
      </div>
    </div>

    <div v-if="a.examples.length" class="examples">
      <div v-for="(e, i) in a.examples" :key="i" class="ex">
        <span class="mono old">{{ e.from }}</span><span class="arrow">→</span><span class="mono new">{{ e.to }}</span>
      </div>
    </div>

    <ul v-if="a.warnings.length" class="warns">
      <li v-for="(w, i) in a.warnings" :key="i">{{ w }}</li>
    </ul>

    <div class="card-foot">
      <template v-if="part.state === 'pending' || part.state === 'failed' || part.state === 'working'">
        <span v-if="part.error" class="err">{{ part.error }}</span>
        <span v-else class="muted small">确认后才会保存，可以随时在对应页面里改回</span>
        <n-button size="small" quaternary :disabled="part.state === 'working'" @click="emit('cancel')">取消</n-button>
        <n-button size="small" type="primary" :loading="part.state === 'working'" @click="emit('confirm')">
          {{ part.state === 'failed' ? '重试' : '确认执行' }}
        </n-button>
      </template>
      <template v-else-if="part.state === 'done' && part.result">
        <span class="ok"><app-icon name="check" :size="14" />{{ part.result.message }}</span>
        <n-button v-if="part.result.link" size="small" secondary @click="router.push(part.result.link)">去看看</n-button>
      </template>
      <span v-else class="muted small">没有做任何修改</span>
    </div>
  </div>
</template>

<style scoped>
.card {
  margin: 4px 0; border: 1px solid var(--border); border-radius: 12px; background: var(--surface);
  box-shadow: inset 0 1px 0 var(--highlight), var(--shadow-2); padding: 12px 14px; max-width: 640px;
  border-left: 3px solid var(--accent); transition: opacity .2s, border-color .2s;
}
.card.done { border-left-color: var(--success); }
.card.cancelled { opacity: .62; border-left-color: var(--text-3); }
.card.failed { border-left-color: var(--error); }
.card-head { display: flex; align-items: center; gap: 8px; }
.card-icon { width: 26px; height: 26px; border-radius: 8px; display: grid; place-items: center; background: var(--accent-soft); color: var(--accent); }
.card-title { font-weight: 600; flex: 1; min-width: 0; }
.card-state { font-size: 12px; color: var(--text-3); }
.card.pending .card-state { color: var(--accent); }
.card-reason { margin: 8px 0 0; color: var(--text-2); font-size: 13px; }
.changes { margin-top: 10px; border-collapse: collapse; font-size: 13px; }
.changes th { text-align: left; font-weight: normal; color: var(--text-3); white-space: nowrap; padding: 4px 14px 4px 0; vertical-align: top; }
.changes td { padding: 4px 0; vertical-align: top; word-break: break-all; }
.changes .arrow, .ex .arrow { color: var(--text-3); padding: 0 8px; }
.old { color: var(--text-3); text-decoration: line-through; }
.new { color: var(--success); }
.stats { margin-top: 10px; padding: 8px 10px; border-radius: 10px; background: var(--surface-2); border: 1px solid var(--border); }
.stats-cap { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.stats-row { display: flex; gap: 18px; flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; }
.stat b { font-size: 18px; line-height: 1.2; color: var(--text); }
.stat span { font-size: 12px; color: var(--text-3); }
.stat.worse b { color: var(--warning); }
.examples { margin-top: 10px; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }
.ex { display: flex; align-items: baseline; flex-wrap: wrap; word-break: break-all; }
.warns { margin: 10px 0 0; padding: 8px 10px 8px 28px; border-radius: 10px; background: var(--warning-soft); color: var(--warning); font-size: 12px; }
.card-foot { margin-top: 12px; display: flex; align-items: center; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
.card-foot > span:first-child { margin-right: auto; }
.ok { display: inline-flex; align-items: center; gap: 6px; color: var(--success); font-size: 13px; }
.err { color: var(--error); font-size: 12px; }
</style>
