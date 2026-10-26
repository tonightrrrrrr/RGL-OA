<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import type { Part } from '../stores/ai'
import { useRollbackStore } from '../stores/rollback'

/**
 * 小R 提出的操作：列出改前改后（或要做的事）、示例文件上的效果和风险提示，用户确认后才执行。
 * 高风险操作（删除、一次改动很多文件）要点两次确认；执行后提示可以回档。
 */
const props = defineProps<{ part: Extract<Part, { kind: 'action' }> }>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const router = useRouter()
const rb = useRollbackStore()

const a = computed(() => props.part.action)
/** 第一次点确认后进入“再确认一次”状态 */
const armed = ref(false)
watch(() => props.part.state, () => { armed.value = false })
/** 文件操作、项目操作的卡片：每行一句说明，不是“改前 → 改后” */
const lines = computed(() => a.value.changes.every((c) => !c.label && !c.before) ? a.value.changes.map((c) => c.after) : [])
const pairs = computed(() => (lines.value.length ? [] : a.value.changes))
const icon = computed(() => ({
  template: 'template', correction: 'review', fileop: 'folder', delete: 'trash', import: 'import', archive: 'ledger',
  undo: 'rollback', deleteBatch: 'trash', removeFile: 'trash', deleteTemplate: 'trash', retry: 'scan', rollback: 'rollback',
  grant: 'folderPlus', revoke: 'folder'
} as Record<string, string>)[a.value.kind] || 'sparkle')
const facts = computed(() => {
  const s = a.value.stats || {}
  const out: string[] = []
  if (a.value.kind !== 'template' && a.value.kind !== 'correction') {
    if (s.files) out.push(`${s.files} 个文件`)
    if (s.size) out.push(String(s.size))
  }
  return out
})

function onConfirm() {
  if (a.value.danger && props.part.state === 'pending' && !armed.value) {
    armed.value = true
    return
  }
  emit('confirm')
}
const stats = computed(() => {
  const s = a.value.stats || {}
  if (a.value.kind !== 'template' || s.autoAfter == null) return []
  const row = (label: string, before: number | null | undefined, after: number | null | undefined, bad = false) => ({
    label, before, after, changed: before != null && before !== after,
    worse: before != null && after != null && (bad ? after > before : after < before)
  })
  return [
    row('自动归档', s.autoBefore as number, s.autoAfter as number),
    row('待确认', s.reviewBefore as number, s.reviewAfter as number, true),
    { label: '识别错却自动归档', before: null, after: s.wrongAutoAfter, changed: false, worse: Number(s.wrongAutoAfter ?? 0) > 0 },
    { label: '重名', before: null, after: s.collisionsAfter, changed: false, worse: Number(s.collisionsAfter ?? 0) > 0 }
  ]
})
const stateLabel = computed(() => ({
  pending: '等你确认', working: '正在执行…', done: '已完成', cancelled: '已取消', failed: '执行失败'
})[props.part.state])
</script>

<template>
  <div class="card" :class="[part.state, { danger: a.danger }]">
    <div class="card-head">
      <span class="card-icon"><app-icon :name="icon" :size="16" /></span>
      <div class="card-title">{{ a.title }}</div>
      <span v-if="a.danger && part.state === 'pending'" class="risk"><app-icon name="warning" :size="12" />高风险</span>
      <span class="card-state">{{ stateLabel }}</span>
    </div>
    <p v-if="a.summary" class="card-reason">{{ a.summary }}</p>

    <ul v-if="lines.length" class="lines">
      <li v-for="(l, i) in lines" :key="i">{{ l }}</li>
    </ul>
    <div v-if="facts.length" class="facts">{{ facts.join(' · ') }}</div>

    <table v-if="pairs.length" class="changes">
      <tr v-for="(c, i) in pairs" :key="i">
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
        <span v-else-if="armed" class="err">这是高风险操作，请再点一次确认</span>
        <span v-else class="muted small">确认后才会执行，执行后可以在顶栏“回档”撤回</span>
        <n-button size="small" quaternary :disabled="part.state === 'working'" @click="armed ? (armed = false) : emit('cancel')">
          {{ armed ? '再想想' : '取消' }}
        </n-button>
        <n-button size="small" :type="a.danger ? 'error' : 'primary'" :loading="part.state === 'working'" @click="onConfirm">
          {{ part.state === 'failed' ? '重试' : armed ? '确定，执行' : '确认执行' }}
        </n-button>
      </template>
      <template v-else-if="part.state === 'done' && part.result">
        <span class="ok"><app-icon name="check" :size="14" />{{ part.result.message }}</span>
        <n-button v-if="part.result.rollback" size="small" quaternary @click="rb.showDialog = true">
          <template #icon><app-icon name="rollback" :size="14" /></template>回档
        </n-button>
        <n-button v-if="part.result.link" size="small" secondary @click="router.push(part.result.link)">去看看</n-button>
      </template>
      <span v-else class="muted small">没有做任何修改</span>
    </div>
  </div>
</template>

<style scoped>
.card {
  margin: 4px 0; border: 1px solid var(--border); border-radius: 16px; background: var(--surface);
  box-shadow: none; padding: 12px 14px; max-width: 640px;
  border-left: 3px solid var(--accent); transition: opacity .2s, border-color .2s;
}
.card.done { border-left-color: var(--success); }
.card.cancelled { opacity: .62; border-left-color: var(--text-3); }
.card.failed { border-left-color: var(--error); }
.card.danger.pending, .card.danger.working { border-left-color: var(--error); }
.card.danger .card-icon { background: var(--error-soft); color: var(--error); }
.risk { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; color: var(--error); background: var(--error-soft);
  padding: 1px 8px; border-radius: 999px; }
.lines { margin: 10px 0 0; padding-left: 18px; font-size: 13px; line-height: 1.7; word-break: break-all; color: var(--text); }
.facts { margin-top: 6px; font-size: 12px; color: var(--text-3); }
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
