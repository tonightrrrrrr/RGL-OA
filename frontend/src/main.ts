import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { installRipple } from './utils/ripple'
import './style.css'

/**
 * 页面文件加载失败时自动刷新一次（v1.9，技术难点 7.11）：浏览器缓存了旧版 index.html 时，
 * 它引用的页面文件在新版里已经不存在，页面会一直空白。30 秒内最多自动刷新一次，文件真的缺失时不会死循环。
 */
const RELOADED_KEY = 'rgl-chunk-reloaded'
function reloadOnceForStaleAssets(err: unknown) {
  const msg = String((err as Error)?.message ?? err)
  if (!/dynamically imported module|Importing a module script failed|Failed to fetch|preload/i.test(msg)) return false
  const last = Number(sessionStorage.getItem(RELOADED_KEY) || 0)
  if (Date.now() - last < 30000) return false
  sessionStorage.setItem(RELOADED_KEY, String(Date.now()))
  location.reload()
  return true
}
window.addEventListener('vite:preloadError', (e) => {
  if (reloadOnceForStaleAssets((e as Event & { payload?: unknown }).payload)) e.preventDefault()
})
router.onError((err) => { reloadOnceForStaleAssets(err) })

createApp(App).use(createPinia()).use(router).mount('#app')
installRipple()
