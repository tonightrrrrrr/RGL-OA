<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NModal } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import { useUpdateStore } from '../stores/update'
import { renderMarkdown } from '../utils/markdown'

/** 发现新版本：显示 GitHub 发布说明，提供下载按钮。下载在浏览器里进行，程序不会自动替换自己 */
const up = useUpdateStore()
const info = computed(() => up.info)
const notes = computed(() => renderMarkdown(info.value?.notes || '（这个版本没有填写发布说明）'))
const size = computed(() => {
  const b = info.value?.downloadSize
  return b ? `${(b / 1024 / 1024).toFixed(0)} MB` : ''
})
const date = computed(() => (info.value?.publishedAt || '').slice(0, 10))
const isZip = computed(() => (info.value?.downloadUrl || '').toLowerCase().endsWith('.zip'))
const guide = computed(() => `https://github.com/${info.value?.repo}/blob/main/docs/安装与更新教程.md`)

function open(url?: string | null) {
  if (url) window.open(url, '_blank', 'noopener')
}
</script>

<template>
  <n-modal :show="up.showDialog && up.available" :auto-focus="false" @update:show="(v: boolean) => { if (!v) up.showDialog = false }">
    <div v-if="info" class="dlg" role="dialog" aria-labelledby="up-title">
      <div class="banner">
        <i class="bubble b1" /><i class="bubble b2" />
        <button class="x ripple" aria-label="关闭" @click="up.showDialog = false"><app-icon name="close" :size="16" /></button>
        <span class="icon"><app-icon name="download" :size="26" /></span>
        <div>
          <div class="eyebrow">NEW VERSION · GitHub</div>
          <h2 id="up-title">发现新版本 v{{ info.latest }}</h2>
          <p>当前 v{{ info.current }}<template v-if="date"> · 发布于 {{ date }}</template><template v-if="size"> · 安装包 {{ size }}</template></p>
        </div>
      </div>
      <div class="body">
        <div class="cap">{{ info.title }}</div>
        <div class="notes md" v-html="notes" />
        <div class="tip">
          <app-icon name="folder" :size="14" />
          <span>下载后先在托盘里退出程序，再把新压缩包解压到原来的文件夹覆盖即可，<b>data</b> 文件夹里的数据会保留。
            <a class="link" @click="open(guide)">查看安装与更新教程</a></span>
        </div>
      </div>
      <div class="foot">
        <span class="remind">升级前每次打开都会提醒</span>
        <n-button quaternary @click="up.showDialog = false">以后再说</n-button>
        <n-button secondary @click="open(info.releaseUrl)">打开发布页</n-button>
        <n-button type="primary" @click="open(info.downloadUrl)">
          <template #icon><app-icon name="download" :size="16" /></template>{{ isZip ? '下载新版本' : '前往下载' }}
        </n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.dlg {
  width: min(600px, calc(100vw - 32px)); border-radius: 18px; overflow: hidden; background: var(--surface);
  border: 1px solid var(--border); box-shadow: 0 30px 80px -20px rgba(0, 0, 0, .55), var(--shadow-3);
}
.banner {
  position: relative; overflow: hidden; display: flex; gap: 16px; align-items: center; padding: 24px 26px 20px; color: #fff;
  background: linear-gradient(135deg, var(--success) 0%, color-mix(in srgb, var(--success) 55%, var(--accent)) 100%);
}
.bubble { position: absolute; border-radius: 50%; background: rgba(255, 255, 255, .13); pointer-events: none; }
.b1 { width: 170px; height: 170px; right: -40px; top: -70px; }
.b2 { width: 70px; height: 70px; right: 110px; bottom: -36px; }
.x {
  position: absolute; right: 12px; top: 12px; width: 30px; height: 30px; border-radius: 50%; border: none; cursor: pointer;
  display: grid; place-items: center; color: inherit; background: rgba(255, 255, 255, .18);
}
.icon { width: 56px; height: 56px; border-radius: 16px; display: grid; place-items: center; flex: none; background: rgba(255, 255, 255, .2); }
.eyebrow { font-size: 11px; letter-spacing: 1.4px; font-weight: 600; opacity: .85; }
.banner h2 { margin: 4px 0; font-size: 21px; }
.banner p { margin: 0; font-size: 13px; opacity: .9; }
.body { padding: 16px 22px 4px; }
.cap { font-weight: 600; margin-bottom: 8px; }
.notes { max-height: 300px; overflow-y: auto; padding: 10px 14px; border-radius: 12px; background: var(--surface-2); border: 1px solid var(--border); font-size: 13.5px; line-height: 1.7; }
.tip { display: flex; gap: 8px; align-items: flex-start; margin-top: 12px; font-size: 12.5px; color: var(--text-2); line-height: 1.6; }
.tip :deep(.app-icon) { margin-top: 3px; color: var(--accent); }
.link { color: var(--accent); cursor: pointer; text-decoration: underline; }
.foot { display: flex; justify-content: flex-end; align-items: center; gap: 8px; padding: 14px 22px 18px; }
.remind { margin-right: auto; font-size: 12px; color: var(--text-3); white-space: nowrap; }
.md :deep(p) { margin: 0 0 6px; }
.md :deep(h3), .md :deep(h4), .md :deep(h5) { margin: 8px 0 4px; font-size: 14px; }
.md :deep(ul) { margin: 2px 0 6px; padding-left: 20px; }
.md :deep(code) { font-family: Consolas, monospace; font-size: 12px; padding: 1px 5px; border-radius: 4px; background: var(--surface-3); }
.md :deep(a) { color: var(--accent); }
</style>
