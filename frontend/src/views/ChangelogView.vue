<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NEmpty } from 'naive-ui'
import AppIcon from '../components/AppIcon.vue'
import PageHeader from '../components/PageHeader.vue'
import { useReleaseStore } from '../stores/release'
import type { Release } from '../api/system'

const rel = useReleaseStore()

const TYPE_LABEL = { new: '新增', improve: '优化', fix: '修复' } as const
const ORDER = ['new', 'improve', 'fix'] as const

const latest = computed(() => rel.releases[0])

/**
 * 时间线默认收起（v1.9.1）：只显示版本号、标题、新增/优化/修复的条数和日期，点标题展开内容。
 * 最新版本默认展开；展开状态只在本页有效，离开页面后恢复默认。
 */
const open = ref(new Set<string>())
const allOpen = computed(() => rel.releases.length > 0 && rel.releases.every((r) => open.value.has(r.version)))
function toggle(v: string) {
  const s = new Set(open.value)
  if (s.has(v)) s.delete(v)
  else s.add(v)
  open.value = s
}
function toggleAll() {
  open.value = allOpen.value ? new Set() : new Set(rel.releases.map((r) => r.version))
}

function counts(r: Release) {
  return ORDER.map((t) => ({ type: t, label: TYPE_LABEL[t], n: r.changes.filter((c) => c.type === t).length })).filter((c) => c.n)
}

function groups(r: Release) {
  return ORDER.map((t) => ({ type: t, label: TYPE_LABEL[t], items: r.changes.filter((c) => c.type === t) })).filter((g) => g.items.length)
}

onMounted(async () => {
  await rel.load()
  rel.markPageSeen()
  if (latest.value) open.value = new Set([latest.value.version])
})
</script>

<template>
  <div class="page">
    <page-header eyebrow="Changelog" title="版本更新" desc="每个版本更新了什么。程序版本号和《技术难点与解决方案》文档的版本号保持一致，可以对照查看对应章节。">
      <n-button v-if="latest" secondary @click="rel.openNotice()">
        <template #icon><app-icon name="megaphone" :size="16" /></template>查看更新公告
      </n-button>
    </page-header>

    <n-empty v-if="!rel.releases.length" description="没有读取到更新记录" style="margin-top: 60px" />

    <template v-else>
      <section v-if="latest" class="hero rise">
        <i class="glow" />
        <div class="hero-head">
          <span class="badge">当前版本</span>
          <span class="hero-ver">v{{ latest.version }}</span>
          <span class="muted small">{{ latest.date }}</span>
        </div>
        <h3>{{ latest.title }}</h3>
        <p class="hero-sum">{{ latest.summary }}</p>
        <div class="hero-hl">
          <div v-for="(h, i) in latest.highlights" :key="i" class="hl lift">
            <span class="hl-icon"><app-icon :name="h.icon" :size="18" /></span>
            <b>{{ h.title }}</b>
            <span>{{ h.desc }}</span>
          </div>
        </div>
      </section>

      <div class="tl-bar">
        <span class="muted small">共 {{ rel.releases.length }} 个版本，点标题展开详细内容</span>
        <n-button size="small" quaternary @click="toggleAll">{{ allOpen ? '全部收起' : '全部展开' }}</n-button>
      </div>
      <ol class="timeline">
        <li v-for="(r, i) in rel.releases" :key="r.version" class="item rise" :style="{ animationDelay: `${0.03 * Math.min(i, 10)}s` }">
          <span class="node" :class="{ cur: i === 0 }" />
          <div class="card surface" :class="{ open: open.has(r.version) }">
            <button class="card-head" :aria-expanded="open.has(r.version)" @click="toggle(r.version)">
              <span class="arrow" aria-hidden="true">›</span>
              <span class="ver">v{{ r.version }}</span>
              <b class="title">{{ r.title }}</b>
              <span class="chips">
                <span v-for="c in counts(r)" :key="c.type" class="chip" :class="c.type">{{ c.label }} {{ c.n }}</span>
              </span>
              <span class="date">{{ r.date }}</span>
            </button>
            <div class="fold">
              <div class="fold-inner">
                <p v-if="r.summary" class="sum">{{ r.summary }}</p>
                <p v-if="r.note" class="note">{{ r.note }}</p>
                <div v-for="g in groups(r)" :key="g.type" class="group">
                  <div class="group-label" :class="g.type">{{ g.label }}</div>
                  <ul>
                    <li v-for="(c, j) in g.items" :key="j">{{ c.text }}</li>
                  </ul>
                </div>
              </div>
            </div>
          </div>
        </li>
      </ol>
    </template>
  </div>
</template>

<style scoped>
.hero {
  position: relative; overflow: hidden; margin: 8px 0 28px; padding: 22px 24px; border-radius: 16px;
  background: linear-gradient(135deg, var(--accent-softer), var(--surface) 60%); border: 1px solid var(--accent-soft);
  box-shadow: none;
}
.glow { position: absolute; width: 320px; height: 320px; right: -120px; top: -160px; border-radius: 50%; background: var(--accent-soft); filter: blur(40px); pointer-events: none; }
.hero-head { position: relative; display: flex; align-items: center; gap: 10px; }
.badge { font-size: 11px; font-weight: 600; padding: 2px 10px; border-radius: 999px; color: var(--on-accent); background: var(--accent);  }
.hero-ver { font-size: 24px; font-weight: 800; letter-spacing: .5px; }
.hero h3 { position: relative; margin: 8px 0 4px; font-size: 18px; }
.hero-sum { position: relative; margin: 0 0 16px; color: var(--text-2); }
.hero-hl { position: relative; display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.hl {
  display: flex; flex-direction: column; gap: 4px; padding: 14px; border-radius: 12px; background: var(--surface);
  border: 1px solid var(--border); box-shadow: none; transition: transform .25s, box-shadow .25s;
}
.hl-icon { width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; color: var(--accent); background: var(--accent-soft); margin-bottom: 4px; }
.hl b { font-size: 14px; }
.hl span:last-child { font-size: 12.5px; color: var(--text-2); line-height: 1.6; }

.timeline { list-style: none; margin: 0; padding: 0 0 0 26px; position: relative; }
.timeline::before { content: ''; position: absolute; left: 7px; top: 8px; bottom: 8px; width: 2px; border-radius: 2px; background: var(--border); }
.item { position: relative; margin-bottom: 16px; }
.node { position: absolute; left: -26px; top: 16px; width: 16px; height: 16px; border-radius: 50%; background: var(--surface); border: 3px solid var(--border-strong); }
.node.cur { border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-ring); }
.tl-bar { display: flex; align-items: center; justify-content: space-between; margin: 0 0 10px 26px; }
.card { padding: 0; overflow: hidden; }
.card-head {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap; width: 100%; padding: 12px 18px; border: none; background: none;
  font: inherit; color: inherit; text-align: left; cursor: pointer; border-radius: inherit; transition: background-color .15s;
}
.card-head:hover { background: var(--accent-softer); }
.card-head:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.arrow { flex: none; width: 14px; font-size: 18px; line-height: 1; color: var(--text-3); transition: transform .2s ease; }
.card.open .arrow { transform: rotate(90deg); color: var(--accent); }
/* 展开动画：grid 行高从 0fr 过渡到 1fr，不用写死高度 */
.fold { display: grid; grid-template-rows: 0fr; transition: grid-template-rows .25s ease; }
.card.open .fold { grid-template-rows: 1fr; }
.fold-inner { overflow: hidden; padding: 0 18px 0 42px; }
.card.open .fold-inner { padding-bottom: 14px; }
.sum { margin: 0 0 4px; font-size: 13px; color: var(--text-2); line-height: 1.7; }
@media (prefers-reduced-motion: reduce) { .fold, .arrow { transition: none; } }
.ver { font-size: 12px; font-weight: 700; padding: 1px 9px; border-radius: 999px; color: var(--accent); background: var(--accent-soft); }
.title { font-size: 15px; }
.chips { display: inline-flex; gap: 6px; }
.chip { font-size: 11px; padding: 1px 8px; border-radius: 999px; }
.chip.new, .group-label.new { color: var(--success); background: var(--success-soft); }
.chip.improve, .group-label.improve { color: var(--info); background: var(--info-soft); }
.chip.fix, .group-label.fix { color: var(--warning); background: var(--warning-soft); }
.date { margin-left: auto; font-size: 12px; color: var(--text-3); }
.note { margin: 8px 0 0; font-size: 12px; color: var(--text-3); }
.group { display: flex; gap: 12px; margin-top: 10px; }
.group-label { flex: none; height: 22px; line-height: 22px; font-size: 12px; padding: 0 8px; border-radius: 6px; }
.group ul { margin: 0; padding-left: 16px; flex: 1; }
.group li { margin: 2px 0; line-height: 1.7; color: var(--text-2); }
</style>
