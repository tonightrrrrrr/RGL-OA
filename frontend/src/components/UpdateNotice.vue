<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NModal } from 'naive-ui'
import AppIcon from './AppIcon.vue'
import { useReleaseStore } from '../stores/release'

/** 更新公告：升级后第一次打开时弹出；跨多个版本升级时，最新版本展示亮点，其余版本列出标题 */
const rel = useReleaseStore()
const router = useRouter()

const latest = computed(() => rel.noticeReleases[0])
const older = computed(() => rel.noticeReleases.slice(1))
const heading = computed(() => {
  if (!latest.value) return ''
  if (rel.manual) return `v${latest.value.version} 更新内容`
  if (rel.firstRun) return `欢迎使用 RGL v${latest.value.version}`
  return `RGL 已更新到 v${latest.value.version}`
})
const sub = computed(() => {
  if (!latest.value) return ''
  if (!rel.manual && !rel.firstRun && rel.noticeSeen) {
    return `从 v${rel.noticeSeen} 升级${older.value.length ? `，共 ${rel.noticeReleases.length} 个版本的更新` : ''}`
  }
  return latest.value.summary
})

function close() {
  rel.closeNotice()
}

function viewAll() {
  rel.closeNotice()
  router.push('/changelog')
}
</script>

<template>
  <n-modal :show="rel.showNotice && !!latest" :mask-closable="true" :auto-focus="false" transform-origin="center"
           @update:show="(v: boolean) => { if (!v) close() }">
    <div v-if="latest" class="notice" role="dialog" aria-labelledby="notice-title">
      <div class="banner">
        <i class="bubble b1" /><i class="bubble b2" /><i class="bubble b3" />
        <button class="x ripple" aria-label="关闭" @click="close"><app-icon name="close" :size="16" /></button>
        <img src="/logo.png" class="logo" alt="" />
        <div class="banner-text">
          <div class="eyebrow">{{ rel.firstRun && !rel.manual ? 'WELCOME' : "WHAT'S NEW" }} · {{ latest.date }}</div>
          <h2 id="notice-title">{{ heading }}</h2>
          <p>{{ sub }}</p>
        </div>
      </div>

      <div class="body">
        <div class="ver-title"><span class="ver">v{{ latest.version }}</span>{{ latest.title }}</div>
        <div class="hl-grid">
          <div v-for="(h, i) in latest.highlights" :key="i" class="hl" :style="{ animationDelay: `${0.08 + i * 0.07}s` }">
            <span class="hl-icon"><app-icon :name="h.icon" :size="18" /></span>
            <div>
              <b>{{ h.title }}</b>
              <span>{{ h.desc }}</span>
            </div>
          </div>
        </div>

        <div v-if="older.length" class="older">
          <div class="older-cap">之前的版本也有更新</div>
          <div v-for="r in older" :key="r.version" class="older-row">
            <span class="ver small">v{{ r.version }}</span>
            <span class="older-title">{{ r.title }}</span>
            <span class="muted small">{{ r.changes.length }} 项</span>
          </div>
        </div>
      </div>

      <div class="foot">
        <n-button quaternary @click="viewAll">查看完整更新记录</n-button>
        <n-button type="primary" @click="close">{{ rel.firstRun && !rel.manual ? '开始使用' : '知道了' }}</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.notice {
  width: min(560px, calc(100vw - 32px)); border-radius: 18px; overflow: hidden; background: var(--surface);
  border: 1px solid var(--border); box-shadow: 0 30px 80px -20px rgba(0, 0, 0, .55), var(--shadow-3);
}
.banner {
  position: relative; overflow: hidden; display: flex; gap: 16px; align-items: center; padding: 26px 26px 22px;
  background: linear-gradient(135deg, var(--accent) 0%, var(--accent-pressed) 100%); color: var(--on-accent);
}
.bubble { position: absolute; border-radius: 50%; background: rgba(255, 255, 255, .12); pointer-events: none; }
.b1 { width: 180px; height: 180px; right: -40px; top: -70px; }
.b2 { width: 90px; height: 90px; right: 90px; bottom: -50px; background: rgba(255, 255, 255, .08); }
.b3 { width: 40px; height: 40px; left: 46%; top: 14px; background: rgba(255, 255, 255, .1); }
.x {
  position: absolute; right: 12px; top: 12px; width: 30px; height: 30px; border-radius: 50%; border: none; cursor: pointer;
  display: grid; place-items: center; color: inherit; background: rgba(255, 255, 255, .16); transition: background .15s, transform .12s;
}
.x:hover { background: rgba(255, 255, 255, .28); }
.x:active { transform: scale(.92); }
.logo { width: 68px; height: 68px; border-radius: 18px; flex: none; box-shadow: 0 8px 20px rgba(0, 0, 0, .25); animation: pop .5s cubic-bezier(.2, .8, .3, 1.3) both; }
.banner-text { position: relative; min-width: 0; }
.eyebrow { font-size: 11px; letter-spacing: 1.4px; font-weight: 600; opacity: .8; }
.banner h2 { margin: 4px 0 4px; font-size: 21px; line-height: 1.3; }
.banner p { margin: 0; font-size: 13px; opacity: .88; line-height: 1.6; }

.body { padding: 18px 22px 6px; }
.ver-title { display: flex; align-items: center; gap: 8px; font-weight: 600; margin-bottom: 12px; }
.ver { font-size: 12px; font-weight: 700; padding: 1px 8px; border-radius: 999px; color: var(--accent); background: var(--accent-soft); }
.ver.small { font-size: 11px; }
.hl-grid { display: flex; flex-direction: column; gap: 8px; }
.hl {
  display: flex; gap: 12px; align-items: flex-start; padding: 10px 12px; border-radius: 12px; background: var(--surface-2);
  border: 1px solid var(--border); animation: rise .4s cubic-bezier(.2, .7, .3, 1) both;
}
.hl-icon { width: 34px; height: 34px; border-radius: 10px; flex: none; display: grid; place-items: center; color: var(--accent); background: var(--accent-soft); }
.hl b { display: block; font-size: 14px; }
.hl span { font-size: 12.5px; color: var(--text-2); line-height: 1.6; }
.older { margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--border); }
.older-cap { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.older-row { display: flex; align-items: center; gap: 8px; padding: 3px 0; font-size: 13px; }
.older-title { flex: 1; color: var(--text-2); }
.foot { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 22px 18px; }
@keyframes pop { from { opacity: 0; transform: scale(.6) rotate(-8deg); } }
</style>
