import type { GlobalThemeOverrides } from 'naive-ui'

/** 一套底色：背景、侧栏、卡片层级、边框、文字 */
export interface BaseTheme {
  key: BaseKey
  name: string
  dark: boolean
  bg: string
  /** 页面背景上的柔和光晕，营造层次 */
  glow: string
  sidebar: string
  surface: string
  surface2: string
  surface3: string
  border: string
  borderStrong: string
  text: string
  text2: string
  text3: string
  success: string
  warning: string
  error: string
  info: string
}

export type BaseKey = 'light' | 'warm' | 'midnight' | 'graphite'
export type AccentKey = 'indigo' | 'teal' | 'violet' | 'emerald' | 'rose' | 'amber'
export type Mode = BaseKey | 'auto'

export const BASES: Record<BaseKey, BaseTheme> = {
  light: {
    key: 'light', name: '浅色', dark: false,
    bg: '#f3f5f9', glow: 'rgba(99,125,201,0.10)', sidebar: '#fbfcfe',
    surface: '#ffffff', surface2: '#f7f9fc', surface3: '#eef2f7',
    border: '#e3e8ef', borderStrong: '#cfd7e2',
    text: '#17202b', text2: '#4c5a6b', text3: '#8792a2',
    success: '#16a36a', warning: '#c98a0b', error: '#dc4646', info: '#3d7fe0'
  },
  warm: {
    key: 'warm', name: '暖白', dark: false,
    bg: '#f6f3ee', glow: 'rgba(201,150,99,0.10)', sidebar: '#fbf9f5',
    surface: '#fffdfa', surface2: '#f9f6f1', surface3: '#f1ece4',
    border: '#e8e1d6', borderStrong: '#d8cebf',
    text: '#272219', text2: '#5b5244', text3: '#968c7c',
    success: '#2f9a63', warning: '#c27c10', error: '#cf4a3c', info: '#3f7bc4'
  },
  midnight: {
    key: 'midnight', name: '午夜蓝', dark: true,
    bg: '#0b1220', glow: 'rgba(80,110,220,0.14)', sidebar: '#0e1626',
    surface: '#131d2f', surface2: '#18243a', surface3: '#1f2c45',
    border: '#24324c', borderStrong: '#31415f',
    text: '#e6ebf3', text2: '#a3afc2', text3: '#6b788e',
    success: '#34d399', warning: '#fbbf24', error: '#f87171', info: '#60a5fa'
  },
  graphite: {
    key: 'graphite', name: '石墨', dark: true,
    bg: '#121314', glow: 'rgba(160,160,170,0.08)', sidebar: '#161718',
    surface: '#1c1d1f', surface2: '#222326', surface3: '#2a2b2f',
    border: '#2f3034', borderStrong: '#3c3d42',
    text: '#ececee', text2: '#a8a9ae', text3: '#717278',
    success: '#4ade80', warning: '#facc15', error: '#f87171', info: '#7dd3fc'
  }
}

export const ACCENTS: Record<AccentKey, { name: string; color: string }> = {
  indigo: { name: '靛蓝', color: '#5b7cfa' },
  teal: { name: '青碧', color: '#2f9aa8' },
  violet: { name: '雾紫', color: '#8b6cf0' },
  emerald: { name: '翠绿', color: '#22a06b' },
  rose: { name: '玫红', color: '#e05a7a' },
  amber: { name: '琥珀', color: '#e0951b' }
}

// ---------------------------------------------------------------- 颜色工具

function rgb(hex: string): [number, number, number] {
  const h = hex.replace('#', '')
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]
}

function hex(r: number, g: number, b: number) {
  return '#' + [r, g, b].map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('')
}

/** 把 a 向 b 混合 t（0–1） */
export function mix(a: string, b: string, t: number) {
  const x = rgb(a), y = rgb(b)
  return hex(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t)
}

export function alpha(c: string, a: number) {
  const [r, g, b] = rgb(c)
  return `rgba(${r},${g},${b},${a})`
}

function luminance(c: string) {
  const [r, g, b] = rgb(c).map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
/** 强调色在当前底色下的各种状态 */
export interface Accent {
  base: string
  hover: string
  pressed: string
  soft: string
  softer: string
  ring: string
  /** 强调色上的文字颜色（琥珀这类亮色上用深色字） */
  on: string
}

export function accentOf(color: string, base: BaseTheme): Accent {
  return {
    base: color,
    hover: base.dark ? mix(color, '#ffffff', 0.12) : mix(color, '#000000', 0.08),
    pressed: base.dark ? mix(color, '#000000', 0.12) : mix(color, '#000000', 0.18),
    soft: alpha(color, base.dark ? 0.18 : 0.12),
    softer: alpha(color, base.dark ? 0.10 : 0.06),
    ring: alpha(color, 0.35),
    on: luminance(color) > 0.45 ? '#1a1406' : '#ffffff'
  }
}

/** 写到 :root 上的 CSS 变量，页面和组件的样式都只引用这些变量 */
export function cssVars(b: BaseTheme, a: Accent): Record<string, string> {
  const shadowColor = b.dark ? '0,0,0' : '20,32,56'
  return {
    '--bg': b.bg,
    '--glow': b.glow,
    '--sidebar': b.sidebar,
    '--surface': b.surface,
    '--surface-2': b.surface2,
    '--surface-3': b.surface3,
    '--border': b.border,
    '--border-strong': b.borderStrong,
    '--text': b.text,
    '--text-2': b.text2,
    '--text-3': b.text3,
    '--accent': a.base,
    '--accent-hover': a.hover,
    '--accent-pressed': a.pressed,
    '--accent-soft': a.soft,
    '--accent-softer': a.softer,
    '--accent-ring': a.ring,
    '--on-accent': a.on,
    '--success': b.success,
    '--warning': b.warning,
    '--error': b.error,
    '--info': b.info,
    '--success-soft': alpha(b.success, b.dark ? 0.16 : 0.10),
    '--warning-soft': alpha(b.warning, b.dark ? 0.16 : 0.12),
    '--error-soft': alpha(b.error, b.dark ? 0.16 : 0.10),
    '--info-soft': alpha(b.info, b.dark ? 0.16 : 0.10),
    // 立体感：顶部一道高光 + 多层柔和投影
    '--highlight': b.dark ? 'rgba(255,255,255,0.05)' : 'rgba(255,255,255,0.9)',
    '--shadow-1': `0 1px 2px rgba(${shadowColor},${b.dark ? 0.35 : 0.06}), 0 1px 1px rgba(${shadowColor},${b.dark ? 0.2 : 0.04})`,
    '--shadow-2': `0 2px 4px rgba(${shadowColor},${b.dark ? 0.3 : 0.05}), 0 8px 20px -6px rgba(${shadowColor},${b.dark ? 0.45 : 0.12})`,
    '--shadow-3': `0 4px 8px rgba(${shadowColor},${b.dark ? 0.3 : 0.06}), 0 18px 40px -12px rgba(${shadowColor},${b.dark ? 0.6 : 0.22})`,
    '--shadow-accent': `0 6px 16px -6px ${alpha(a.base, b.dark ? 0.7 : 0.55)}`
  }
}

/** Naive UI 组件的颜色覆盖，和 CSS 变量来自同一套取值 */
export function naiveOverrides(b: BaseTheme, a: Accent): GlobalThemeOverrides {
  const font = "'Inter', 'Microsoft YaHei UI', 'Microsoft YaHei', 'PingFang SC', system-ui, sans-serif"
  return {
    common: {
      fontFamily: font,
      fontFamilyMono: "'JetBrains Mono', Consolas, 'Courier New', monospace",
      primaryColor: a.base,
      primaryColorHover: a.hover,
      primaryColorPressed: a.pressed,
      primaryColorSuppl: a.hover,
      infoColor: b.info,
      successColor: b.success,
      warningColor: b.warning,
      errorColor: b.error,
      textColorBase: b.text,
      textColor1: b.text,
      textColor2: b.text2,
      textColor3: b.text3,
      bodyColor: b.bg,
      cardColor: b.surface,
      modalColor: b.surface,
      popoverColor: b.surface2,
      tableColor: b.surface,
      tableHeaderColor: b.surface2,
      inputColor: b.dark ? b.surface2 : b.surface,
      borderColor: b.border,
      dividerColor: b.border,
      hoverColor: b.dark ? 'rgba(255,255,255,0.05)' : 'rgba(20,32,56,0.04)',
      borderRadius: '8px',
      borderRadiusSmall: '6px'
    },
    Card: { borderRadius: '12px', borderColor: b.border, color: b.surface, colorEmbedded: b.surface2 },
    DataTable: {
      thColor: b.surface2, tdColor: b.surface, tdColorHover: b.surface2, borderColor: b.border,
      thTextColor: b.text2, thFontWeight: '500', borderRadius: '10px'
    },
    Menu: {
      itemColorActive: a.soft, itemColorActiveHover: a.soft, itemColorHover: b.dark ? 'rgba(255,255,255,0.05)' : 'rgba(20,32,56,0.04)',
      itemTextColorActive: a.base, itemTextColorActiveHover: a.base, itemIconColorActive: a.base, itemIconColorActiveHover: a.base,
      itemTextColor: b.text2, itemIconColor: b.text3, borderRadius: '8px', itemHeight: '40px'
    },
    Button: { borderRadiusMedium: '8px', borderRadiusSmall: '7px', fontWeight: '500', textColorPrimary: a.on, textColorHoverPrimary: a.on, textColorPressedPrimary: a.on, textColorFocusPrimary: a.on },
    Input: { borderRadius: '8px', color: b.dark ? b.surface2 : b.surface, colorFocus: b.dark ? b.surface2 : b.surface, border: `1px solid ${b.border}`, borderHover: `1px solid ${b.borderStrong}` },
    Tabs: { tabBorderRadius: '7px', colorSegment: b.surface3, tabColorSegment: b.surface },
    Tag: { borderRadius: '6px' },
    Layout: { color: 'transparent', siderColor: b.sidebar, headerColor: 'transparent' },
    Drawer: { color: b.surface },
    Dialog: { color: b.surface, borderRadius: '12px' },
    Alert: { borderRadius: '8px' }
  }
}