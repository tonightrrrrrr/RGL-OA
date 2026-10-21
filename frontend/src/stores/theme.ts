import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { darkTheme } from 'naive-ui'
import { ACCENTS, BASES, accentOf, cssVars, naiveOverrides, type AccentKey, type BaseKey, type Mode } from '../theme/themes'

const STORAGE_KEY = 'rgl-theme'

/** 主题：底色（浅色 / 暖白 / 午夜蓝 / 石墨 / 跟随系统）+ 强调色，保存在浏览器本地 */
export const useThemeStore = defineStore('theme', () => {
  const saved = (() => {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as { mode?: Mode; accent?: AccentKey }
    } catch {
      return {}
    }
  })()
  const mode = ref<Mode>(saved.mode && (saved.mode === 'auto' || saved.mode in BASES) ? saved.mode : 'midnight')
  const accent = ref<AccentKey>(saved.accent && saved.accent in ACCENTS ? saved.accent : 'indigo')

  const media = window.matchMedia('(prefers-color-scheme: dark)')
  const systemDark = ref(media.matches)
  media.addEventListener('change', (e) => (systemDark.value = e.matches))

  const baseKey = computed<BaseKey>(() => (mode.value === 'auto' ? (systemDark.value ? 'midnight' : 'light') : mode.value))
  const base = computed(() => BASES[baseKey.value])
  const accentColors = computed(() => accentOf(ACCENTS[accent.value].color, base.value))
  const naiveTheme = computed(() => (base.value.dark ? darkTheme : null))
  const overrides = computed(() => naiveOverrides(base.value, accentColors.value))

  function apply() {
    const root = document.documentElement
    for (const [k, v] of Object.entries(cssVars(base.value, accentColors.value))) {
      root.style.setProperty(k, v)
    }
    root.dataset.theme = baseKey.value
    root.style.colorScheme = base.value.dark ? 'dark' : 'light'
  }

  watch([baseKey, accent], () => {
    apply()
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ mode: mode.value, accent: accent.value }))
  }, { immediate: true })
  watch(mode, () => localStorage.setItem(STORAGE_KEY, JSON.stringify({ mode: mode.value, accent: accent.value })))

  /** 切换主题时从点击位置圆形展开（浏览器支持 View Transitions 时），否则直接切换 */
  function switchWith(change: () => void, e?: MouseEvent) {
    const doc = document as Document & { startViewTransition?: (cb: () => void) => { ready: Promise<void> } }
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (!doc.startViewTransition || reduce || !e) {
      change()
      return
    }
    const x = e.clientX, y = e.clientY
    const r = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y))
    doc.startViewTransition(change).ready.then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${r}px at ${x}px ${y}px)`] },
        { duration: 520, easing: 'cubic-bezier(.4,0,.2,1)', pseudoElement: '::view-transition-new(root)' }
      )
    })
  }

  function setMode(m: Mode, e?: MouseEvent) {
    if (m !== mode.value) switchWith(() => (mode.value = m), e)
  }

  function setAccent(a: AccentKey, e?: MouseEvent) {
    if (a !== accent.value) switchWith(() => (accent.value = a), e)
  }

  /** 侧栏底部的快捷切换：浅色与深色之间来回切 */
  function toggleDark(e?: MouseEvent) {
    setMode(base.value.dark ? (mode.value === 'graphite' ? 'warm' : 'light') : 'midnight', e)
  }

  return { mode, accent, baseKey, base, accentColors, naiveTheme, overrides, setMode, setAccent, toggleDark }
})
