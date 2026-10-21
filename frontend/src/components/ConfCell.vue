<script setup lang="ts">
import { confColor, pct } from '../utils/format'

defineProps<{ value: string | null; conf: number | null }>()
</script>

<template>
  <span>
    {{ value || '—' }}
    <span v-if="conf !== null" class="conf" :style="{ color: confColor(conf) }">{{ pct(conf) }}</span>
  </span>
</template>

<style scoped>
.conf { font-size: 11px; margin-left: 4px; }
</style>
