import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { checkUpdate, getUpdate, saveUpdateSettings, type UpdateInfo } from '../api/system'

/**
 * 联网检查更新。后端启动时在后台检查（没联网时只记一个状态），这里只读取结果。
 * 任何失败都不弹错误，不影响程序使用。
 */
export const useUpdateStore = defineStore('update', () => {
  const info = ref<UpdateInfo | null>(null)
  const checking = ref(false)
  const showDialog = ref(false)

  const available = computed(() => info.value?.state === 'AVAILABLE' && !!info.value.latest)

  async function refresh(opened = false) {
    try {
      info.value = await getUpdate(opened)
    } catch {
      /* 后台没连上时其他地方会提示，这里不处理 */
    }
  }

  /**
   * 页面打开时读取结果（v1.9：每次打开都提醒）。第一次读取时告诉后台“页面刚打开”，上次检查超过 1 小时就重新检查；
   * 后台还在检查时，每 3 秒再读一次，最多 30 秒。只要当前不是最新版，每次打开都弹提示，“以后再说”只关掉这一次。
   */
  async function watchStartup(onAvailable?: () => void) {
    await refresh(true)
    for (let i = 0; i < 10; i++) {
      const s = info.value?.state
      if (s && s !== 'NEVER' && s !== 'CHECKING') break
      await new Promise((r) => setTimeout(r, 3000))
      await refresh()
    }
    if (available.value) onAvailable?.()
  }

  /** 手动检查；返回结果供界面提示 */
  async function check() {
    checking.value = true
    try {
      info.value = await checkUpdate()
    } finally {
      checking.value = false
    }
    return info.value
  }

  async function setAutoCheck(on: boolean) {
    info.value = await saveUpdateSettings(on)
  }

  return { info, checking, showDialog, available, refresh, watchStartup, check, setAutoCheck }
})
