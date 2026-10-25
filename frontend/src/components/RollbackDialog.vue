<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NModal, useMessage } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import { useRollbackStore } from '../stores/rollback'
import { errorMessage } from '../api/http'
import type { RollbackResult } from '../api/rollback'

/** 回档：显示最近一次操作做了什么、回档会撤回什么；确认后撤回到操作之前 */
const rb = useRollbackStore()
const message = useMessage()
const emit = defineEmits<{ done: [] }>()
const e = computed(() => rb.entry)
const result = ref<RollbackResult | null>(null)
const error = ref('')
const at = computed(() => (e.value?.at || '').replace('T', ' ').slice(0, 19))

watch(() => rb.showDialog, (v) => {
  if (v) {
    result.value = null
    error.value = ''
    rb.refresh()
  }
})

async function confirm() {
  error.value = ''
  try {
    result.value = await rb.run()
    message.success(result.value.message)
    emit('done')
    if (!result.value.notes.length) rb.showDialog = false
  } catch (err) {
    error.value = errorMessage(err)
    rb.refresh()
  }
}
</script>

<template>
  <n-modal :show="rb.showDialog" :auto-focus="false" @update:show="(v: boolean) => { if (!v) rb.showDialog = false }">
    <div class="dlg" role="dialog" aria-labelledby="rb-title">
      <div class="head">
        <span class="icon"><app-icon name="rollback" :size="20" /></span>
        <h2 id="rb-title">回档</h2>
        <button class="x ripple" aria-label="关闭" @click="rb.showDialog = false"><app-icon name="close" :size="16" /></button>
      </div>

      <div class="tip">
        <app-icon name="info" :size="15" />
        <ul>
          <li>回档会把<b>最近一次</b>操作撤回到操作之前，比如误删、误归档、误撤销。</li>
          <li>只保留最近一次操作的记录：<b>有新的操作就会自动覆盖</b>，之前的操作就不能再回档了。</li>
          <li>每条记录只能回档一次。删除的文件同时也在系统回收站里。</li>
        </ul>
      </div>

      <template v-if="result">
        <div class="ok"><app-icon name="check" :size="16" />{{ result.message }}</div>
        <ul v-if="result.notes.length" class="warns">
          <li v-for="(n, i) in result.notes" :key="i">{{ n }}</li>
        </ul>
        <div class="foot"><n-button type="primary" @click="rb.showDialog = false">知道了</n-button></div>
      </template>

      <template v-else-if="e">
        <div class="op">
          <div class="op-title">{{ e.title }}</div>
          <div class="op-meta">{{ at }} · {{ e.source === 'AI' ? '小R执行' : '界面操作' }}</div>
        </div>
        <div v-if="e.done.length" class="sec">
          <div class="cap">这次操作</div>
          <ul><li v-for="(d, i) in e.done" :key="i">{{ d }}</li></ul>
        </div>
        <div v-if="e.canRollback" class="sec">
          <div class="cap">回档会</div>
          <ul><li v-for="(u, i) in e.undo" :key="i">{{ u }}</li></ul>
        </div>
        <ul v-if="e.warnings.length" class="warns">
          <li v-for="(w, i) in e.warnings" :key="i">{{ w }}</li>
        </ul>
        <div v-if="!e.canRollback" class="err">{{ e.reason || '这次操作不能回档' }}</div>
        <div v-if="error" class="err">{{ error }}</div>
        <div class="foot">
          <span class="muted small">之后在界面上手动修改的同一批次、模板，回档时也会一起恢复</span>
          <n-button quaternary @click="rb.showDialog = false">取消</n-button>
          <n-button type="warning" :disabled="!e.canRollback" :loading="rb.running" @click="confirm">确认回档</n-button>
        </div>
      </template>

      <template v-else>
        <div class="empty">目前没有可以回档的操作。归档、撤销、删除批次记录、删除模板，以及小R执行的操作，完成后都可以在这里回档。</div>
        <div class="foot"><n-button @click="rb.showDialog = false">关闭</n-button></div>
      </template>
    </div>
  </n-modal>
</template>

<style scoped>
.dlg {
  width: min(560px, calc(100vw - 32px)); border-radius: 16px; background: var(--surface); padding: 18px 20px 16px;
  box-shadow: var(--shadow-3, 0 20px 50px -12px rgba(0, 0, 0, .35)); border: 1px solid var(--border);
}
.head { display: flex; align-items: center; gap: 10px; }
.head h2 { margin: 0; font-size: 17px; flex: 1; }
.icon { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; background: var(--warning-soft); color: var(--warning); }
.x { border: none; background: none; color: var(--text-3); cursor: pointer; padding: 6px; border-radius: 8px; }
.x:hover { background: var(--surface-2); color: var(--text); }
.tip {
  margin-top: 14px; display: flex; gap: 8px; padding: 10px 12px; border-radius: 10px; font-size: 12.5px; line-height: 1.7;
  background: var(--accent-softer, var(--surface-2)); color: var(--text-2); border: 1px solid var(--border);
}
.tip ul { margin: 0; padding-left: 16px; }
.tip > .app-icon { flex: none; margin-top: 3px; color: var(--accent); }
.op { margin-top: 14px; padding: 10px 12px; border-radius: 10px; background: var(--surface-2); border: 1px solid var(--border); }
.op-title { font-weight: 600; }
.op-meta { font-size: 12px; color: var(--text-3); margin-top: 2px; }
.sec { margin-top: 12px; font-size: 13px; }
.cap { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.sec ul { margin: 0; padding-left: 18px; line-height: 1.7; }
.warns { margin: 12px 0 0; padding: 8px 10px 8px 28px; border-radius: 10px; background: var(--warning-soft); color: var(--warning); font-size: 12px; line-height: 1.7; }
.err { margin-top: 12px; color: var(--error); font-size: 13px; }
.ok { margin-top: 14px; display: flex; align-items: center; gap: 6px; color: var(--success); }
.empty { margin-top: 14px; color: var(--text-2); font-size: 13px; line-height: 1.7; }
.foot { margin-top: 16px; display: flex; gap: 8px; align-items: center; justify-content: flex-end; flex-wrap: wrap; }
.foot .muted { margin-right: auto; }
</style>
