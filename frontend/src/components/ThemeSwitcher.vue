<script setup lang="ts">
import { NPopover } from 'naive-ui'
import { useThemeStore } from '../stores/theme'
import { ACCENTS, BASES, type AccentKey, type Mode } from '../theme/themes'
import AppIcon from './AppIcon.vue'

/** 主题切换：底色（含跟随系统）+ 强调色；每个选项是一张小预览卡 */
const theme = useThemeStore()
const modes: { key: Mode; name: string }[] = [
  { key: 'light', name: BASES.light.name },
  { key: 'warm', name: BASES.warm.name },
  { key: 'midnight', name: BASES.midnight.name },
  { key: 'graphite', name: BASES.graphite.name },
  { key: 'auto', name: '跟随系统' }
]
const accents = Object.entries(ACCENTS) as [AccentKey, { name: string; color: string }][]

/** 预览卡用这套底色自己的颜色绘制 */
function preview(key: Mode) {
  const b = key === 'auto' ? null : BASES[key]
  if (!b) return { background: 'linear-gradient(135deg, #f3f5f9 50%, #0b1220 50%)' }
  return { background: b.bg, '--pv-surface': b.surface, '--pv-side': b.sidebar, '--pv-line': b.border, '--pv-text': b.text3 }
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
      <div class="ts-title">底色</div>
      <div class="ts-modes">
        <button v-for="m in modes" :key="m.key" class="ts-mode ripple" :class="{ active: theme.mode === m.key }"
                @click="(e) => theme.setMode(m.key, e)">
          <span class="pv" :style="preview(m.key)">
            <template v-if="m.key !== 'auto'">
              <i class="pv-side" /><i class="pv-card" /><i class="pv-bar" :style="{ background: ACCENTS[theme.accent].color }" />
            </template>
            <app-icon v-else name="monitor" :size="18" class="pv-auto" />
          </span>
          <span class="ts-name">{{ m.name }}</span>
        </button>
      </div>
      <div class="ts-title" style="margin-top: 14px">强调色</div>
      <div class="ts-accents">
        <button v-for="[k, a] in accents" :key="k" class="ts-accent" :class="{ active: theme.accent === k }"
                :style="{ '--c': a.color }" :title="a.name" @click="(e) => theme.setAccent(k, e)">
          <app-icon v-if="theme.accent === k" name="check" :size="13" :stroke="2.6" />
        </button>
      </div>
      <p class="ts-tip">设置保存在本机浏览器里，下次打开自动恢复。</p>
    </div>
  </n-popover>
</template>

<style scoped>
.ts-trigger {
  display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 12px; border-radius: 8px; cursor: pointer;
  border: 1px solid var(--border); background: var(--surface); color: var(--text-2); font: inherit; font-size: 13px;
  box-shadow: inset 0 1px 0 var(--highlight), var(--shadow-1); transition: transform .15s ease, color .2s, border-color .2s;
}
.ts-trigger:hover { color: var(--text); border-color: var(--border-strong); transform: translateY(-1px); }
.ts-trigger:active { transform: translateY(1px) scale(.98); }
.ts-panel {
  width: 340px; padding: 14px; border-radius: 14px; background: var(--surface); border: 1px solid var(--border);
  box-shadow: inset 0 1px 0 var(--highlight), var(--shadow-3); animation: pop .18s cubic-bezier(.2,.7,.3,1);
}
@keyframes pop { from { opacity: 0; transform: translateY(-6px) scale(.98); } }
.ts-title { font-size: 12px; font-weight: 600; color: var(--text-2); margin-bottom: 8px; }
.ts-modes { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
.ts-mode { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 0; border: none; background: none; cursor: pointer; color: var(--text-2); font: inherit; border-radius: 10px; }
.ts-mode .pv {
  position: relative; width: 56px; height: 42px; border-radius: 8px; overflow: hidden; display: block;
  border: 1px solid var(--border); box-shadow: var(--shadow-1); transition: transform .18s ease, box-shadow .18s ease, border-color .18s;
}
.ts-mode:hover .pv { transform: translateY(-2px); box-shadow: var(--shadow-2); }
.ts-mode.active .pv { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-ring), var(--shadow-2); }
.ts-mode.active .ts-name { color: var(--accent); font-weight: 600; }
.pv-side { position: absolute; left: 0; top: 0; bottom: 0; width: 14px; background: var(--pv-side); border-right: 1px solid var(--pv-line); }
.pv-card { position: absolute; left: 20px; right: 6px; top: 8px; bottom: 8px; border-radius: 4px; background: var(--pv-surface); border: 1px solid var(--pv-line); }
.pv-bar { position: absolute; left: 25px; top: 13px; width: 16px; height: 4px; border-radius: 2px; }
.pv-auto { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); color: #8792a2; }
.ts-name { font-size: 12px; }
.ts-accents { display: flex; gap: 10px; }
.ts-accent {
  width: 28px; height: 28px; border-radius: 50%; border: none; cursor: pointer; color: #fff; display: grid; place-items: center;
  background: linear-gradient(180deg, color-mix(in srgb, var(--c) 82%, #fff), var(--c));
  box-shadow: inset 0 1px 0 rgba(255,255,255,.35), 0 4px 10px -3px var(--c); transition: transform .15s ease, box-shadow .15s ease;
}
.ts-accent:hover { transform: translateY(-2px) scale(1.06); }
.ts-accent:active { transform: scale(.94); }
.ts-accent.active { box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px var(--c), 0 6px 14px -4px var(--c); }
.ts-tip { margin: 12px 0 0; font-size: 11px; color: var(--text-3); }
</style>
