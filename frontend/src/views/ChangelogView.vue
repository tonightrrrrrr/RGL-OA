<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { NButton, NEmpty } from 'naive-ui'
import AppIcon from '../components/AppIcon.vue'
import PageHeader from '../components/PageHeader.vue'
import { useReleaseStore } from '../stores/release'
import type { Release } from '../api/system'

const rel = useReleaseStore()

const TYPE_LABEL = { new: '新增', improve: '优化', fix: '修复' } as const
const ORDER = ['new', 'improve', 'fix'] as const

const latest = computed(() => rel.releases[0])

function counts(r: Release) {
  return ORDER.map((t) => ({ type: t, label: TYPE_LABEL[t], n: r.changes.filter((c) => c.type === t).length })).filter((c) => c.n)
}

function groups(r: Release) {
  return ORDER.map((t) => ({ type: t, label: TYPE_LABEL[t], items: r.changes.filter((c) => c.type === t) })).filter((g) => g.items.length)
}

onMounted(async () => {
  await rel.load()
  rel.markPageSeen()
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

      <ol class="timeline">
        <li v-for="(r, i) in rel.releases" :key="r.version" class="item rise" :style="{ animationDelay: `${0.05 * i}s` }">
          <span class="node" :class="{ cur: i === 0 }" />
          <div class="card surface">
            <div class="card-head">
              <span class="ver">v{{ r.version }}</span>
              <b class="title">{{ r.title }}</b>
              <span class="chips">
                <span v-for="c in counts(r)" :key="c.type" class="chip" :class="c.type">{{ c.label }} {{ c.n }}</span>
              </span>
              <span class="date">{{ r.date }}</span>
            </div>
            <p v-if="r.note" class="note">{{ r.note }}</p>
            <div v-for="g in groups(r)" :key="g.type" class="group">
              <div class="group-label" :class="g.type">{{ g.label }}</div>
              <ul>
                <li v-for="(c, j) in g.items" :key="j">{{ c.text }}</li>
              </ul>
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
.node { position: absolute; left: -26px; top: 20px; width: 16px; height: 16px; border-radius: 50%; background: var(--surface); border: 3px solid var(--border-strong); }
.node.cur { border-color: var(--accent); box-shadow: 0 0 0 4px var(--accent-ring); }
.card { padding: 14px 18px; }
.card-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
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
