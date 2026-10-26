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

export type BaseKey = 'paper' | 'light' | 'warm' | 'hotpot' | 'celadon' | 'midnight' | 'graphite' | 'ink'
export type AccentKey = 'ink' | 'crimson' | 'gold' | 'indigo' | 'teal' | 'violet' | 'emerald' | 'rose' | 'amber'
export type Mode = BaseKey | 'auto'

export const BASES: Record<BaseKey, BaseTheme> = {
  // v1.7.1：参考 OpenAI 首页——纯白底、大量留白、细边框、近黑文字，几乎不用阴影和光晕
  paper: {
    key: 'paper', name: '纯白', dark: false,
    bg: '#ffffff', glow: 'rgba(0,0,0,0)', sidebar: '#f9f9f9',
    surface: '#ffffff', surface2: '#f7f7f8', surface3: '#efefef',
    border: '#e5e5e5', borderStrong: '#d4d4d4',
    text: '#0d0d0d', text2: '#5d5d5d', text3: '#8f8f8f',
    success: '#10a37f', warning: '#c27c0e', error: '#d93025', info: '#2f6fe4'
  },
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
  // v1.7.1：参考海底捞官网——白底、#333 文字、浅粉提示块、金色点缀，配中国红强调色
  hotpot: {
    key: 'hotpot', name: '红白', dark: false,
    bg: '#f6f6f6', glow: 'rgba(230,0,18,0.05)', sidebar: '#ffffff',
    surface: '#ffffff', surface2: '#fff6f6', surface3: '#feecec',
    border: '#efe3e3', borderStrong: '#e2cfcf',
    text: '#282828', text2: '#555555', text3: '#8f8f8f',
    success: '#1f9d55', warning: '#c8922f', error: '#e60012', info: '#2f6fd0'
  },
  celadon: {
    key: 'celadon', name: '青瓷', dark: false,
    bg: '#f1f6f4', glow: 'rgba(60,140,120,0.08)', sidebar: '#f8fbfa',
    surface: '#ffffff', surface2: '#f4f9f7', surface3: '#e8f1ed',
    border: '#d9e7e1', borderStrong: '#c3d8cf',
    text: '#15261f', text2: '#4a5f56', text3: '#83968d',
    success: '#1f9d6b', warning: '#c08414', error: '#cf4b3d', info: '#2f7cb8'
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
  },
  // v1.7.1：纯白的深色版（参考 ChatGPT 深色），中性灰、无光晕
  ink: {
    key: 'ink', name: '纯黑', dark: true,
    bg: '#121212', glow: 'rgba(0,0,0,0)', sidebar: '#171717',
    surface: '#1e1e1e', surface2: '#262626', surface3: '#303030',
    border: '#2e2e2e', borderStrong: '#3d3d3d',
    text: '#ececec', text2: '#b4b4b4', text3: '#7d7d7d',
    success: '#3ecf8e', warning: '#f0b429', error: '#f26b6b', info: '#6ea8ff'
  }
}

export const ACCENTS: Record<AccentKey, { name: string; color: string }> = {
  /** OpenAI 首页按钮的近黑色；深色底色上自动换成近白色 */
  ink: { name: '墨黑', color: '#0d0d0d' },
  /** 海底捞的品牌红 */
  crimson: { name: '中国红', color: '#e60012' },
  /** 海底捞官网的点缀金 */
  gold: { name: '金色', color: '#c9973f' },
  indigo: { name: '靛蓝', color: '#5b7cfa' },
  teal: { name: '青碧', color: '#2f9aa8' },
  violet: { name: '雾紫', color: '#8b6cf0' },
  emerald: { name: '翠绿', color: '#22a06b' },
  rose: { name: '玫红', color: '#e05a7a' },
  amber: { name: '琥珀', color: '#e0951b' }
}

/** 推荐搭配：底色 + 强调色，一键切换（v1.7.1） */
export interface Preset {
  key: string
  name: string
  desc: string
  mode: BaseKey
  accent: AccentKey
}

export const PRESETS: Preset[] = [
  { key: 'minimal', name: '极简白', desc: '参考 OpenAI，黑白为主', mode: 'paper', accent: 'ink' },
  { key: 'minimal-dark', name: '极简黑', desc: '极简白的深色版', mode: 'ink', accent: 'ink' },
  { key: 'hotpot', name: '红白', desc: '参考海底捞，中国红', mode: 'hotpot', accent: 'crimson' },
  { key: 'gold', name: '黑金', desc: '深色底配金色', mode: 'graphite', accent: 'gold' },
  { key: 'celadon', name: '青瓷', desc: '浅青底配青碧', mode: 'celadon', accent: 'teal' },
  { key: 'midnight', name: '午夜蓝', desc: '原来的默认主题', mode: 'midnight', accent: 'indigo' }
]

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

/** 两个颜色的对比度（WCAG），1–21 */
function contrast(a: string, b: string) {
  const x = luminance(a), y = luminance(b)
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}

export function accentOf(color: string, base: BaseTheme): Accent {
  // 近黑的强调色（墨黑）放在深色底色上看不见，换成近白色
  const c = base.dark && luminance(color) < 0.02 ? '#ececec' : color
  const dim = luminance(c) < 0.02
  return {
    base: c,
    // 近黑色按钮悬停时变浅一点，其他颜色按原来的规则
    hover: dim ? '#2b2b2b' : base.dark ? mix(c, '#ffffff', 0.12) : mix(c, '#000000', 0.08),
    pressed: dim ? '#000000' : base.dark ? mix(c, '#000000', 0.12) : mix(c, '#000000', 0.18),
    soft: alpha(c, dim ? 0.07 : base.dark ? 0.18 : 0.12),
    softer: alpha(c, dim ? 0.04 : base.dark ? 0.10 : 0.06),
    ring: alpha(c, dim ? 0.2 : 0.35),
    // 白字和底色的对比度不到 3 时（琥珀、金色、近白），改用深色字
    on: contrast(c, '#ffffff') >= 3 ? '#ffffff' : '#1a1406'
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
    // v1.7.1：参考 OpenAI，层次主要靠细边框和留白，投影收得很淡；只有浮层（弹窗、下拉）保留明显一点的投影
    '--highlight': 'rgba(255,255,255,0)',
    '--shadow-1': `0 1px 2px rgba(${shadowColor},${b.dark ? 0.25 : 0.04})`,
    '--shadow-2': `0 2px 8px -2px rgba(${shadowColor},${b.dark ? 0.35 : 0.08})`,
    '--shadow-3': `0 4px 10px rgba(${shadowColor},${b.dark ? 0.3 : 0.05}), 0 16px 36px -12px rgba(${shadowColor},${b.dark ? 0.55 : 0.18})`,
    '--shadow-accent': 'none',
    '--radius-card': '16px',
    '--radius-pill': '999px'
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
      borderRadius: '10px',
      borderRadiusSmall: '8px'
    },
    Card: { borderRadius: '16px', borderColor: b.border, color: b.surface, colorEmbedded: b.surface2 },
    DataTable: {
      thColor: b.surface2, tdColor: b.surface, tdColorHover: b.surface2, borderColor: b.border,
      thTextColor: b.text2, thFontWeight: '500', borderRadius: '10px'
    },
    Menu: {
      itemColorActive: a.soft, itemColorActiveHover: a.soft, itemColorHover: b.dark ? 'rgba(255,255,255,0.05)' : 'rgba(20,32,56,0.04)',
      itemTextColorActive: a.base, itemTextColorActiveHover: a.base, itemIconColorActive: a.base, itemIconColorActiveHover: a.base,
      itemTextColor: b.text2, itemIconColor: b.text3, borderRadius: '8px', itemHeight: '40px'
    },
    // v1.7.1：胶囊形按钮（参考 OpenAI 首页的“试用 ChatGPT”“登录”）
    Button: {
      borderRadiusTiny: '999px', borderRadiusSmall: '999px', borderRadiusMedium: '999px', borderRadiusLarge: '999px',
      fontWeight: '500', paddingMedium: '0 16px', paddingSmall: '0 12px',
      textColorPrimary: a.on, textColorHoverPrimary: a.on, textColorPressedPrimary: a.on, textColorFocusPrimary: a.on
    },
    Input: { borderRadius: '10px', color: b.dark ? b.surface2 : b.surface, colorFocus: b.dark ? b.surface2 : b.surface, border: `1px solid ${b.border}`, borderHover: `1px solid ${b.borderStrong}` },
    Tabs: { tabBorderRadius: '7px', colorSegment: b.surface3, tabColorSegment: b.surface },
    Tag: { borderRadius: '999px' },
    Layout: { color: 'transparent', siderColor: b.sidebar, headerColor: 'transparent' },
    Drawer: { color: b.surface },
    Dialog: { color: b.surface, borderRadius: '16px' },
    Alert: { borderRadius: '12px' }
  }
}