import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getRollback, runRollback, type RollbackEntry, type RollbackResult } from '../api/rollback'

/** 回档：顶栏按钮和弹窗共用；归档、撤销、删除等操作完成后调用 refresh 更新 */
export const useRollbackStore = defineStore('rollback', () => {
  const entry = ref<RollbackEntry | null>(null)
  const showDialog = ref(false)
  const running = ref(false)

  async function refresh() {
    try {
      entry.value = await getRollback()
    } catch {
      /* 后台没连上时其他地方会提示 */
    }
  }

  async function run(): Promise<RollbackResult> {
    if (!entry.value) throw new Error('没有可以回档的操作')
    running.value = true
    try {
      const r = await runRollback(entry.value.id)
      entry.value = null
      return r
    } finally {
      running.value = false
    }
  }

  return { entry, showDialog, running, refresh, run }
})

/** 操作成功提示后面加的一句回档提醒 */
export const ROLLBACK_TIP = '误操作可以点顶栏的“回档”撤回'
