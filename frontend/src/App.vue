<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter, RouterView } from 'vue-router'
import {
  NConfigProvider, NLayout, NLayoutSider, NLayoutContent, NMenu, NMessageProvider, NDialogProvider, NBadge, NTooltip,
  zhCN, dateZhCN, type MenuOption
} from 'naive-ui'
import { useThemeStore } from './stores/theme'
import { getStatus } from './api/system'
import { reviewQueue } from './api/archive'
import AppIcon from './components/AppIcon.vue'
import ThemeSwitcher from './components/ThemeSwitcher.vue'
import UpdateNotice from './components/UpdateNotice.vue'
import UpdateDialog from './components/UpdateDialog.vue'
import RollbackDialog from './components/RollbackDialog.vue'
import { useReleaseStore } from './stores/release'
import { useUpdateStore } from './stores/update'
import { useRollbackStore } from './stores/rollback'

const route = useRoute()
const router = useRouter()
const theme = useThemeStore()
const release = useReleaseStore()
const update = useUpdateStore()
const rb = useRollbackStore()
/** 回档后让当前页面重新加载数据 */
const reloadKey = ref(0)

const collapsed = ref(false)
const reviewCount = ref(0)
const ocr = ref<boolean | null>(null)
const version = ref('')

const NAV = [
  { key: '/import', label: '导入识别', icon: 'import', group: '归档' },
  { key: '/review', label: '待确认', icon: 'review', group: '归档' },
  { key: '/ledger', label: '文件台账', icon: 'ledger', group: '归档' },
  { key: '/ai', label: 'AI 助手', icon: 'sparkle', group: '智能' },
  { key: '/template', label: '模板设置', icon: 'template', group: '配置' },
  { key: '/system', label: '系统设置', icon: 'system', group: '配置' },
  { key: '/changelog', label: '版本更新', icon: 'history', group: '配置' }
]

const menuOptions = computed<MenuOption[]>(() => {
  const groups = [...new Set(NAV.map((n) => n.group))]
  return groups.map((g) => ({
    type: 'group',
    key: 'g-' + g,
    label: g,
    children: NAV.filter((n) => n.group === g).map((n) => ({
      key: n.key,
      label: () => h('span', { class: 'nav-label' }, [
        n.label,
        n.key === '/review' && reviewCount.value > 0
          ? h(NBadge, { value: reviewCount.value, max: 99, type: 'warning', class: 'nav-badge' })
          : null,
        n.key === '/changelog' && release.hasNew ? h('span', { class: 'nav-new' }, 'NEW') : null
      ]),
      icon: () => h(AppIcon, { name: n.icon, size: 18 })
    }))
  }))
})

const active = computed(() => route.path)
const pageTitle = computed(() => NAV.find((n) => n.key === route.path)?.label || '')

async function refreshHeader() {
  try {
    const [st, rq] = await Promise.all([getStatus(), reviewQueue()])
    ocr.value = st.ocrAvailable
    version.value = st.version
    reviewCount.value = rq.length
  } catch {
    ocr.value = null
  }
  rb.refresh()
}

function afterRollback() {
  reloadKey.value++
  refreshHeader()
}

onMounted(async () => {
  refreshHeader()
  await release.load() // 升级后第一次打开时弹出更新公告
  // 联网检查更新：没联网时什么都不显示；发现新版本时，等本机的更新公告关掉后再提示一次
  update.watchStartup(() => {
    const show = () => { update.showDialog = true }
    if (!release.showNotice) return show()
    const stop = watch(() => release.showNotice, (v) => { if (!v) { stop(); setTimeout(show, 400) } })
  })
})
watch(() => route.path, refreshHeader)
</script>
<template>
  <n-config-provider :theme="theme.naiveTheme" :theme-overrides="theme.overrides" :locale="zhCN" :date-locale="dateZhCN"
                     class="app-bg" style="height: 100%">
    <n-message-provider>
      <n-dialog-provider>
        <n-layout has-sider style="height: 100%; background: transparent">
          <n-layout-sider bordered collapse-mode="width" :collapsed-width="68" :width="232" :collapsed="collapsed"
                          :native-scrollbar="false" class="sider">
            <div class="sider-inner">
              <div class="brand" @click="router.push('/import')">
                <img src="/logo.png" class="brand-logo" alt="RGL" />
                <div v-show="!collapsed" class="brand-text">
                  <div class="brand-name">RGL</div>
                  <div class="brand-sub">OA 智能归档助手</div>
                </div>
              </div>
              <n-menu :value="active" :options="menuOptions" :collapsed="collapsed" :collapsed-width="68"
                      :collapsed-icon-size="20" :indent="16" class="nav" @update:value="(k: string) => router.push(k)" />
              <div class="sider-foot">
                <button class="foot-btn ripple" :title="theme.base.dark ? '切换到浅色' : '切换到深色'" @click="(e) => theme.toggleDark(e)">
                  <app-icon :name="theme.base.dark ? 'sun' : 'moon'" />
                  <span v-show="!collapsed">{{ theme.base.dark ? '浅色模式' : '深色模式' }}</span>
                </button>
                <button class="foot-btn ripple" :title="collapsed ? '展开' : '收起'" @click="collapsed = !collapsed">
                  <app-icon :name="collapsed ? 'expand' : 'collapse'" />
                  <span v-show="!collapsed">收起侧栏</span>
                </button>
              </div>
            </div>
          </n-layout-sider>
          <n-layout-content :native-scrollbar="false" style="background: transparent" content-style="min-height: 100vh">
            <header class="topbar">
              <div class="crumb"><span class="crumb-root">RGL</span><span class="crumb-sep">/</span><b>{{ pageTitle }}</b></div>
              <div class="top-actions">
                <span class="pill" :class="ocr === false ? 'bad' : ocr ? 'ok' : ''">
                  <i class="dot" />OCR {{ ocr === null ? '检测中' : ocr ? '可用' : '不可用' }}
                </span>
                <button v-if="reviewCount" class="pill pill-btn ripple" @click="router.push('/review')">
                  <app-icon name="review" :size="14" />{{ reviewCount }} 个待确认
                </button>
                <n-tooltip placement="bottom" :style="{ maxWidth: '300px' }">
                  <template #trigger>
                    <button class="pill rb-pill ripple" :class="{ armed: rb.entry?.canRollback }" @click="rb.showDialog = true">
                      <app-icon name="rollback" :size="14" />回档<app-icon name="info" :size="12" class="rb-info" /><i v-if="rb.entry?.canRollback" class="rb-dot" />
                    </button>
                  </template>
                  <div class="rb-tip">
                    <b>{{ rb.entry ? '可以回档：' + rb.entry.title : '目前没有可以回档的操作' }}</b>
                    <div>误操作时可以撤回到操作之前。只保留最近一次操作，有新的操作会自动覆盖；每条记录只能回档一次。</div>
                  </div>
                </n-tooltip>
                <theme-switcher />
                <button v-if="update.available" class="pill new-pill ripple" title="GitHub 上有新版本" @click="update.showDialog = true">
                  <app-icon name="download" :size="14" />新版本 v{{ update.info?.latest }}
                </button>
                <button v-if="version" class="pill ver-pill ripple" title="查看版本更新" @click="router.push('/changelog')">
                  v{{ version }}<i v-if="release.hasNew" class="ver-dot" />
                </button>
              </div>
            </header>
            <router-view v-slot="{ Component }">
              <transition name="page" mode="out-in">
                <component :is="Component" :key="route.path + '#' + reloadKey" />
              </transition>
            </router-view>
            <update-notice />
            <update-dialog />
            <rollback-dialog @done="afterRollback" />
          </n-layout-content>
        </n-layout>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<style>
.sider { box-shadow: 1px 0 0 var(--border), 6px 0 24px -18px rgba(0, 0, 0, 0.5); z-index: 2; }
.sider-inner { display: flex; flex-direction: column; min-height: 100vh; }
.brand { display: flex; align-items: center; gap: 10px; padding: 18px 16px 14px; cursor: pointer; }
.brand-logo { width: 36px; height: 36px; flex: none; filter: drop-shadow(0 4px 8px var(--accent-ring)); transition: transform .25s ease; }
.brand:hover .brand-logo { transform: rotate(-6deg) scale(1.05); }
.brand-name { font-weight: 800; font-size: 18px; letter-spacing: 1px; line-height: 1.1;
  background: linear-gradient(90deg, var(--text), var(--accent)); -webkit-background-clip: text; background-clip: text; color: transparent; }
.brand-sub { font-size: 11px; color: var(--text-3); letter-spacing: .5px; margin-top: 2px; white-space: nowrap; }
.nav { padding: 0 8px; }
.nav .n-menu-item-group-title { font-size: 11px !important; letter-spacing: 1px; color: var(--text-3) !important; padding-left: 18px !important; }
.nav .n-menu-item-content { border-radius: 8px; transition: transform .15s ease; }
.nav .n-menu-item-content:active { transform: scale(.98); }
.nav .n-menu-item-content--selected::after {
  content: ''; position: absolute; left: 0; top: 9px; bottom: 9px; width: 3px; border-radius: 3px; background: var(--accent);
  box-shadow: 0 0 8px var(--accent-ring);
}
.nav-label { display: inline-flex; align-items: center; gap: 8px; }
.nav-badge .n-badge-sup { font-size: 11px; }
.sider-foot { margin-top: auto; padding: 10px 8px 14px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 4px; }
.foot-btn {
  display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 14px; border: none; background: none; border-radius: 8px;
  color: var(--text-2); font: inherit; font-size: 13px; cursor: pointer; white-space: nowrap; overflow: hidden;
  transition: background-color .15s, color .15s, transform .12s;
}
.foot-btn:hover { background: var(--accent-softer); color: var(--text); }
.foot-btn:active { transform: scale(.97); }
.topbar {
  position: sticky; top: 0; z-index: 5; height: 56px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 32px; backdrop-filter: blur(12px); background: color-mix(in srgb, var(--bg) 78%, transparent);
  border-bottom: 1px solid var(--border);
}
.crumb { font-size: 13px; color: var(--text-2); display: flex; gap: 8px; align-items: center; }
.crumb-root { color: var(--accent); font-weight: 600; letter-spacing: .5px; }
.crumb-sep { color: var(--text-3); }
.crumb b { color: var(--text); font-weight: 600; }
.top-actions { display: flex; align-items: center; gap: 8px; }
.pill {
  display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 12px; border-radius: 999px; font-size: 12px;
  border: 1px solid var(--border); background: var(--surface); color: var(--text-2); box-shadow: inset 0 1px 0 var(--highlight), var(--shadow-1);
}
.pill .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-3); }
.pill.ok .dot { background: var(--success); box-shadow: 0 0 0 3px var(--success-soft); }
.pill.bad .dot { background: var(--error); box-shadow: 0 0 0 3px var(--error-soft); }
.pill-btn { cursor: pointer; font: inherit; font-size: 12px; color: var(--warning); border-color: var(--warning-soft); background: var(--warning-soft);
  transition: transform .15s ease; }
.pill-btn:hover { transform: translateY(-1px); }
.new-pill {
  cursor: pointer; font: inherit; font-size: 12px; font-weight: 600; color: #fff; border-color: transparent;
  background: linear-gradient(135deg, var(--success), color-mix(in srgb, var(--success) 55%, var(--accent)));
  box-shadow: 0 4px 14px -4px var(--success); animation: breathe 2.4s ease-in-out infinite; transition: transform .15s;
}
.new-pill:hover { transform: translateY(-1px); }
@keyframes breathe { 50% { box-shadow: 0 4px 18px -2px var(--success); } }
.ver-pill { position: relative; cursor: pointer; font: inherit; font-size: 12px; transition: transform .15s ease, color .15s, border-color .15s; }
.ver-pill:hover { color: var(--accent); border-color: var(--accent-soft); transform: translateY(-1px); }
.ver-pill:active { transform: translateY(1px) scale(.98); }
.ver-dot { position: absolute; top: 3px; right: 3px; width: 7px; height: 7px; border-radius: 50%; background: var(--error); box-shadow: 0 0 0 2px var(--surface); }
.nav-new {
  font-size: 10px; font-weight: 700; letter-spacing: .5px; line-height: 16px; padding: 0 6px; border-radius: 999px;
  color: #fff; background: linear-gradient(135deg, var(--error), var(--warning)); box-shadow: 0 2px 6px var(--error-soft);
}
.pill-btn:active { transform: translateY(1px) scale(.98); }
.rb-pill { position: relative; cursor: pointer; font: inherit; font-size: 12px; transition: transform .15s ease, color .15s, border-color .15s; }
.rb-pill:hover { color: var(--warning); border-color: var(--warning-soft); transform: translateY(-1px); }
.rb-pill.armed { color: var(--text); }
.rb-dot { position: absolute; top: 3px; right: 3px; width: 7px; height: 7px; border-radius: 50%; background: var(--warning); box-shadow: 0 0 0 2px var(--surface); }
.rb-info { opacity: .55; margin-left: -2px; }
.rb-tip { font-size: 12px; line-height: 1.6; }
.rb-tip b { display: block; margin-bottom: 2px; }
</style>