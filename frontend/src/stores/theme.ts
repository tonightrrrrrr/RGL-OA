import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { darkTheme } from 'naive-ui'
import { ACCENTS, BASES, accentOf, cssVars, naiveOverrides, type AccentKey, type BaseKey, type Mode, type Preset } from '../theme/themes'

const STORAGE_KEY = 'rgl-theme'

/**
 * 主题：底色（8 套 + 跟随系统）+ 强调色（9 种），保存在浏览器本地。
 * v1.7.1 起新用户默认“极简白”（纯白 + 墨黑）；已经选过主题的保持原来的选择。
 */
export const useThemeStore = defineStore('theme', () => {
  const saved = (() => {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as { mode?: Mode; accent?: AccentKey }
    } catch {
      return {}
    }
  })()
  const mode = ref<Mode>(saved.mode && (saved.mode === 'auto' || saved.mode in BASES) ? saved.mode : 'paper')
  const accent = ref<AccentKey>(saved.accent && saved.accent in ACCENTS ? saved.accent : 'ink')

  const media = window.matchMedia('(prefers-color-scheme: dark)')
  const systemDark = ref(media.matches)
  media.addEventListener('change', (e) => (systemDark.value = e.matches))

  const baseKey = computed<BaseKey>(() => (mode.value === 'auto' ? (systemDark.value ? 'ink' : 'paper') : mode.value))
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

  /** 推荐搭配：底色和强调色一起换，只展开一次动画 */
  function applyPreset(p: Preset, e?: MouseEvent) {
    if (p.mode === mode.value && p.accent === accent.value) return
    switchWith(() => {
      mode.value = p.mode
      accent.value = p.accent
    }, e)
  }

  /** 当前选择正好是某个推荐搭配 */
  function isPreset(p: Preset) {
    return mode.value === p.mode && accent.value === p.accent
  }

  /** 侧栏底部的快捷切换：浅色与深色之间来回切，尽量切到风格对应的那一套 */
  const PAIR: Partial<Record<BaseKey, BaseKey>> = { paper: 'ink', ink: 'paper', light: 'midnight', midnight: 'light', warm: 'graphite', graphite: 'warm' }
  function toggleDark(e?: MouseEvent) {
    const next = PAIR[baseKey.value] ?? (base.value.dark ? 'paper' : 'ink')
    setMode(next, e)
  }

  return { mode, accent, baseKey, base, accentColors, naiveTheme, overrides, setMode, setAccent, applyPreset, isPreset, toggleDark }
})
