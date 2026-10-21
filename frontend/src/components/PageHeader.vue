<script setup lang="ts">
/** 页面标题区：一行小字眉标、标题、说明，右侧放操作按钮 */
defineProps<{ eyebrow?: string; title: string; desc?: string }>()
</script>

<template>
  <header class="ph rise">
    <div class="ph-text">
      <div v-if="eyebrow" class="ph-eyebrow">{{ eyebrow }}</div>
      <h2 class="page-title">{{ title }}</h2>
      <p v-if="desc" class="page-desc">{{ desc }}</p>
    </div>
    <div v-if="$slots.default" class="ph-actions"><slot /></div>
  </header>
</template>

<style scoped>
.ph { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 4px; }
.ph-text { min-width: 0; }
.ph-eyebrow {
  display: inline-block; font-size: 11px; font-weight: 600; letter-spacing: 1.2px; text-transform: uppercase;
  color: var(--accent); background: var(--accent-softer); border: 1px solid var(--accent-soft);
  padding: 2px 8px; border-radius: 999px; margin-bottom: 10px;
}
.ph-actions { display: flex; gap: 8px; align-items: center; padding-bottom: 20px; }
</style>
