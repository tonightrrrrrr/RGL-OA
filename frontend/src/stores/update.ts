import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { checkUpdate, getUpdate, saveUpdateSettings, type UpdateInfo } from '../api/system'

/** 某个新版本已经自动弹过一次提示，之后只在顶栏显示，不再打扰 */
const PROMPTED_KEY = 'rgl-update-prompted'

/**
 * 联网检查更新。后端启动时在后台检查（没联网时只记一个状态），这里只读取结果。
 * 任何失败都不弹错误，不影响程序使用。
 */
export const useUpdateStore = defineStore('update', () => {
  const info = ref<UpdateInfo | null>(null)
  const checking = ref(false)
  const showDialog = ref(false)

  const available = computed(() => info.value?.state === 'AVAILABLE' && !!info.value.latest)

  async function refresh() {
    try {
      info.value = await getUpdate()
    } catch {
      /* 后台没连上时其他地方会提示，这里不处理 */
    }
  }

  /** 页面打开时读取结果；后台还在检查时，每 5 秒再读一次，最多 6 次 */
  async function watchStartup(onAvailable?: () => void) {
    for (let i = 0; i < 7; i++) {
      await refresh()
      const s = info.value?.state
      if (s && s !== 'NEVER' && s !== 'CHECKING') break
      await new Promise((r) => setTimeout(r, 5000))
    }
    if (available.value && info.value?.latest && localStorage.getItem(PROMPTED_KEY) !== info.value.latest) {
      localStorage.setItem(PROMPTED_KEY, info.value.latest)
      onAvailable?.()
    }
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
