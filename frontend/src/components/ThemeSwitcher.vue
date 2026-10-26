<script setup lang="ts">
import { NPopover } from 'naive-ui'
import { useThemeStore } from '../stores/theme'
import { ACCENTS, BASES, PRESETS, accentOf, type AccentKey, type BaseKey, type Mode, type Preset } from '../theme/themes'
import AppIcon from './AppIcon.vue'

/** 外观：推荐搭配（一键切换）、底色（含跟随系统）、强调色；每个选项是一张用自己的颜色画的小预览 */
const theme = useThemeStore()
const BASE_ORDER: BaseKey[] = ['paper', 'light', 'warm', 'hotpot', 'celadon', 'midnight', 'graphite', 'ink']
const modes: { key: Mode; name: string }[] = [
  ...BASE_ORDER.map((k) => ({ key: k as Mode, name: BASES[k].name })),
  { key: 'auto', name: '跟随系统' }
]
const accents = Object.entries(ACCENTS) as [AccentKey, { name: string; color: string }][]

/** 预览卡用这套底色自己的颜色绘制；强调色按这套底色换算（墨黑在深色底上显示为近白） */
function preview(key: Mode, accent: AccentKey = theme.accent) {
  const b = key === 'auto' ? null : BASES[key]
  if (!b) return { background: 'linear-gradient(135deg, #ffffff 50%, #121212 50%)' }
  return {
    background: b.bg, '--pv-surface': b.surface, '--pv-side': b.sidebar, '--pv-line': b.border, '--pv-text': b.text3,
    '--pv-accent': accentOf(ACCENTS[accent].color, b).base
  }
}

/** 强调色圆点：墨黑在深色底色下显示成实际会用的近白色 */
function dot(k: AccentKey) {
  return accentOf(ACCENTS[k].color, theme.base).base
}

function presetStyle(p: Preset) {
  return preview(p.mode, p.accent)
}
</script>

<template>
  <n-popover trigger="click" placement="bottom-end" :show-arrow="false" raw>
    <template #trigger>
      <button class="ts-trigger ripple" title="外观">
        <app-icon name="palette" :size="17" />
        <span>外观</span>
      </button>
    </template>
    <div class="ts-panel">
      <div class="ts-title">推荐搭配</div>
      <div class="ts-presets">
        <button v-for="p in PRESETS" :key="p.key" class="ts-preset ripple" :class="{ active: theme.isPreset(p) }"
                @click="(e) => theme.applyPreset(p, e)">
          <span class="pv" :style="presetStyle(p)">
            <i class="pv-side" /><i class="pv-card" /><i class="pv-bar" /><i class="pv-pill" />
          </span>
          <span class="ts-pname">{{ p.name }}</span>
          <span class="ts-pdesc">{{ p.desc }}</span>
        </button>
      </div>

      <div class="ts-title">底色</div>
      <div class="ts-modes">
        <button v-for="m in modes" :key="m.key" class="ts-mode ripple" :class="{ active: theme.mode === m.key }"
                @click="(e) => theme.setMode(m.key, e)">
          <span class="pv sm" :style="preview(m.key)">
            <template v-if="m.key !== 'auto'">
              <i class="pv-side" /><i class="pv-card" /><i class="pv-bar" />
            </template>
            <app-icon v-else name="monitor" :size="16" class="pv-auto" />
          </span>
          <span class="ts-name">{{ m.name }}</span>
        </button>
      </div>

      <div class="ts-title">强调色</div>
      <div class="ts-accents">
        <button v-for="[k, a] in accents" :key="k" class="ts-accent" :class="{ active: theme.accent === k }"
                :style="{ '--c': dot(k) }" :title="a.name" :aria-label="a.name" @click="(e) => theme.setAccent(k, e)">
          <app-icon v-if="theme.accent === k" name="check" :size="13" :stroke="2.6" class="ts-check" />
        </button>
      </div>
      <p class="ts-tip">设置保存在本机浏览器里，下次打开自动恢复。</p>
    </div>
  </n-popover>
</template>

<style scoped>
.ts-trigger {
  display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 14px; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--border); background: var(--surface); color: var(--text-2); font: inherit; font-size: 13px;
  transition: background-color .15s, color .15s, border-color .15s;
}
.ts-trigger:hover { color: var(--text); background: var(--surface-2); }
.ts-trigger:active { transform: scale(.97); }
.ts-panel {
  width: 392px; padding: 16px; border-radius: 18px; background: var(--surface); border: 1px solid var(--border);
  box-shadow: var(--shadow-3); animation: pop .18s cubic-bezier(.2,.7,.3,1);
}
@keyframes pop { from { opacity: 0; transform: translateY(-6px) scale(.98); } }
.ts-title { font-size: 12px; font-weight: 600; color: var(--text-2); margin: 14px 0 8px; }
.ts-title:first-child { margin-top: 0; }

.ts-presets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.ts-preset {
  display: flex; flex-direction: column; align-items: flex-start; gap: 3px; padding: 6px 6px 8px; border-radius: 14px; cursor: pointer;
  border: 1px solid var(--border); background: var(--surface); color: var(--text-2); font: inherit; text-align: left;
  transition: border-color .15s, background-color .15s;
}
.ts-preset:hover { background: var(--surface-2); }
.ts-preset.active { border-color: var(--accent); box-shadow: 0 0 0 1px var(--accent); }
.ts-preset .pv { width: 100%; height: 46px; margin-bottom: 4px; }
.ts-pname { font-size: 12.5px; font-weight: 600; color: var(--text); padding: 0 2px; }
.ts-pdesc { font-size: 11px; color: var(--text-3); padding: 0 2px; line-height: 1.3; }

.ts-modes { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px 6px; }
.ts-mode { display: flex; flex-direction: column; align-items: center; gap: 5px; padding: 0; border: none; background: none; cursor: pointer; color: var(--text-2); font: inherit; border-radius: 10px; }
.pv {
  position: relative; border-radius: 10px; overflow: hidden; display: block;
  border: 1px solid var(--border); transition: transform .18s ease, border-color .18s;
}
.pv.sm { width: 60px; height: 40px; }
.ts-mode:hover .pv { transform: translateY(-2px); }
.ts-mode.active .pv { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-ring); }
.ts-mode.active .ts-name { color: var(--accent); font-weight: 600; }
.pv-side { position: absolute; left: 0; top: 0; bottom: 0; width: 14px; background: var(--pv-side); border-right: 1px solid var(--pv-line); }
.pv-card { position: absolute; left: 20px; right: 6px; top: 7px; bottom: 7px; border-radius: 5px; background: var(--pv-surface); border: 1px solid var(--pv-line); }
.pv-bar { position: absolute; left: 25px; top: 12px; width: 16px; height: 4px; border-radius: 2px; background: var(--pv-accent); }
.pv-pill { position: absolute; right: 11px; bottom: 11px; width: 22px; height: 8px; border-radius: 999px; background: var(--pv-accent); }
.pv-auto { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); color: #8f8f8f; }
.ts-name { font-size: 11.5px; white-space: nowrap; }

.ts-accents { display: flex; flex-wrap: wrap; gap: 10px; }
.ts-accent {
  width: 28px; height: 28px; border-radius: 50%; cursor: pointer; display: grid; place-items: center; background: var(--c);
  border: 1px solid color-mix(in srgb, var(--c) 70%, var(--text)); transition: transform .15s ease, box-shadow .15s ease;
}
.ts-accent:hover { transform: scale(1.08); }
.ts-accent:active { transform: scale(.94); }
.ts-accent.active { box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px var(--c); }
/* 勾的颜色和按钮文字用同一条规则：浅色强调色上用深色勾 */
.ts-check { color: var(--on-accent); mix-blend-mode: normal; }
.ts-accent:not(.active) .ts-check { display: none; }
.ts-tip { margin: 14px 0 0; font-size: 11px; color: var(--text-3); }
</style>
