<script setup lang="ts">
import { computed } from 'vue'

/** 线条图标（24×24，描边随文字颜色），不依赖图标库 */
const PATHS: Record<string, string> = {
  import: 'M12 3v11 M7.5 9.5L12 14l4.5-4.5 M4 16v2.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V16',
  review: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z M8.2 12.4l2.6 2.6 5-5.4',
  ledger: 'M5.5 3h13A1.5 1.5 0 0 1 20 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19.5v-15A1.5 1.5 0 0 1 5.5 3z M4 8.5h16 M4 14h16 M9.5 8.5V21',
  template: 'M12 3l8.5 4.3L12 11.6 3.5 7.3z M3.5 12l8.5 4.3 8.5-4.3 M3.5 16.5l8.5 4.3 8.5-4.3',
  system: 'M4 6.5h9 M17 6.5h3 M15 4.5v4 M4 12h3 M11 12h9 M9 10v4 M4 17.5h11 M19 17.5h1 M17 15.5v4',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 2.5v2 M12 19.5v2 M5.3 5.3l1.4 1.4 M17.3 17.3l1.4 1.4 M2.5 12h2 M19.5 12h2 M5.3 18.7l1.4-1.4 M17.3 6.7l1.4-1.4',
  moon: 'M20 14.6A8.3 8.3 0 1 1 9.4 4a6.6 6.6 0 0 0 10.6 10.6z',
  palette: 'M12 3a9 9 0 1 0 0 18c1 0 1.6-.8 1.6-1.7 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-.9.7-1.6 1.6-1.6H16a5 5 0 0 0 5-5c0-4-4-7.3-9-7.3z M7.5 11h.01 M9.8 7.4h.01 M14.2 7.4h.01 M16.6 11h.01',
  collapse: 'M14.5 6l-6 6 6 6 M19.5 6v12',
  expand: 'M9.5 6l6 6-6 6 M4.5 6v12',
  scan: 'M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8 M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8 M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16 M8 20H5.5A1.5 1.5 0 0 1 4 18.5V16 M7.5 12h9',
  folder: 'M3.5 7.5A1.5 1.5 0 0 1 5 6h4.2l2 2.2H19a1.5 1.5 0 0 1 1.5 1.5v8.3A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18z',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  monitor: 'M4.5 4.5h15A1.5 1.5 0 0 1 21 6v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 15V6a1.5 1.5 0 0 1 1.5-1.5z M9 20.5h6 M12 16.5v4',
  sparkle: 'M12 3.5l1.9 5.1 5.1 1.9-5.1 1.9L12 17.5l-1.9-5.1L5 10.5l5.1-1.9z M18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z',
  send: 'M4 12l16-8-6 16-2.6-6.4z M11.4 13.6L20 4',
  stop: 'M7.5 6h9A1.5 1.5 0 0 1 18 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 6 16.5v-9A1.5 1.5 0 0 1 7.5 6z',
  trash: 'M4.5 7h15 M9.5 7V4.5h5V7 M6.5 7l.8 12a1.5 1.5 0 0 0 1.5 1.5h6.4a1.5 1.5 0 0 0 1.5-1.5l.8-12 M10 11v6 M14 11v6',
  download: 'M12 4v11 M7.5 10.5L12 15l4.5-4.5 M5 19.5h14',
  loader: 'M12 3.5a8.5 8.5 0 1 0 8.5 8.5',
  close: 'M6.5 6.5l11 11 M17.5 6.5l-11 11',
  megaphone: 'M4 10v4a1 1 0 0 0 1 1h2.5l6.5 4V5L7.5 9H5a1 1 0 0 0-1 1z M17.5 9a4 4 0 0 1 0 6 M8 15l1 4.5',
  history: 'M4.5 12a7.5 7.5 0 1 0 2.2-5.3 M4.5 4.5v3h3 M12 8v4.2l2.8 1.8',
  rollback: 'M9 14.5L4.5 10 9 5.5 M4.5 10h10a5 5 0 0 1 0 10H11',
  info: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z M12 11v5.5 M12 7.6h.01',
  folderPlus: 'M3.5 7.5A1.5 1.5 0 0 1 5 6h4.2l2 2.2H19a1.5 1.5 0 0 1 1.5 1.5v8.3A1.5 1.5 0 0 1 19 19.5H5A1.5 1.5 0 0 1 3.5 18z M12 11v5 M9.5 13.5h5',
  warning: 'M12 4l9 15.5H3z M12 10v4.5 M12 17.2h.01'
}

const props = withDefaults(defineProps<{ name: string; size?: number; stroke?: number }>(), { size: 18, stroke: 1.8 })
const d = computed(() => PATHS[props.name] || '')
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" :stroke-width="stroke"
       stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="app-icon">
    <path :d="d" />
  </svg>
</template>

<style scoped>
.app-icon { display: inline-block; flex: none; vertical-align: middle; }
</style>
