import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { getChangelog, type Release } from '../api/system'

/** 上次看过更新公告时的版本；没有记录表示第一次使用 */
const NOTICE_KEY = 'rgl-notice-seen'
/** 上次打开“版本更新”页面时的版本，用来决定侧栏是否显示 NEW */
const PAGE_KEY = 'rgl-changelog-seen'

/** 按数字比较版本号：1.10 > 1.9 */
export function compareVersion(a: string, b: string): number {
  const x = a.split('.').map(Number)
  const y = b.split('.').map(Number)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] || 0) - (y[i] || 0)
    if (d) return d
  }
  return 0
}

/** 版本更新记录与更新公告 */
export const useReleaseStore = defineStore('release', () => {
  const releases = ref<Release[]>([])
  const noticeSeen = ref(localStorage.getItem(NOTICE_KEY))
  const pageSeen = ref(localStorage.getItem(PAGE_KEY))
  const showNotice = ref(false)
  /** 公告是用户手动打开的（不是升级后自动弹出），这时显示当前版本的内容 */
  const manual = ref(false)

  const current = computed(() => releases.value[0]?.version ?? '')
  const firstRun = computed(() => !noticeSeen.value)
  /** 公告里要展示的版本：比上次看过的版本新的全部版本；第一次使用或手动打开时只显示当前版本 */
  const noticeReleases = computed(() => {
    if (!releases.value.length) return []
    if (manual.value || !noticeSeen.value) return releases.value.slice(0, 1)
    return releases.value.filter((r) => compareVersion(r.version, noticeSeen.value as string) > 0)
  })
  /** 侧栏 NEW 标记：有比上次打开“版本更新”页时更新的版本 */
  const hasNew = computed(() => !!current.value && (!pageSeen.value || compareVersion(current.value, pageSeen.value) > 0))

  async function load() {
    if (releases.value.length) return
    try {
      releases.value = await getChangelog()
    } catch {
      return // 后台没连上时不弹公告，其他地方会提示
    }
    if (current.value && (!noticeSeen.value || compareVersion(current.value, noticeSeen.value) > 0)) {
      manual.value = false
      showNotice.value = true
    }
  }

  function closeNotice() {
    showNotice.value = false
    if (current.value) {
      noticeSeen.value = current.value
      localStorage.setItem(NOTICE_KEY, current.value)
    }
  }

  function openNotice() {
    manual.value = true
    showNotice.value = true
  }

  function markPageSeen() {
    if (current.value) {
      pageSeen.value = current.value
      localStorage.setItem(PAGE_KEY, current.value)
    }
  }

  return { releases, current, firstRun, noticeReleases, noticeSeen, showNotice, manual, hasNew, load, closeNotice, openNotice, markPageSeen }
})
