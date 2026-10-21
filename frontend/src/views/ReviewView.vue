<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { NCard, NDataTable, NButton, NEmpty, NText, useMessage, type DataTableColumns } from 'naive-ui'
import { reviewQueue, getBatch, type FileRecord } from '../api/archive'
import { errorMessage } from '../api/http'
import { useDictStore } from '../stores/dict'
import ConfCell from '../components/ConfCell.vue'
import FileDetailDrawer from '../components/FileDetailDrawer.vue'
import PageHeader from '../components/PageHeader.vue'

const message = useMessage()
const dict = useDictStore()
const rows = ref<FileRecord[]>([])
const loading = ref(false)
const detailId = ref<number | null>(null)
const drawerDict = ref({ departments: [] as string[], docTypes: [] as string[] })

async function load() {
  loading.value = true
  try {
    await dict.load()
    rows.value = await reviewQueue()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}

async function open(r: FileRecord) {
  // 按文件所属批次的模板提供部门和类型选项
  try {
    const b = await getBatch(r.batchId)
    drawerDict.value = await dict.dictOf(b.templateId)
  } catch {
    drawerDict.value = { departments: dict.departments, docTypes: dict.docTypes }
  }
  detailId.value = r.id
}

const columns: DataTableColumns<FileRecord> = [
  { title: '批次', key: 'batchId', width: 70, render: (r) => '#' + r.batchId },
  { title: '原文件名', key: 'originalName', minWidth: 240, ellipsis: { tooltip: true } },
  { title: '部门', key: 'department', width: 130, render: (r) => h(ConfCell, { value: r.department, conf: r.deptConf }) },
  { title: '类型', key: 'docType', width: 110, render: (r) => h(ConfCell, { value: r.docType, conf: r.typeConf }) },
  { title: '日期', key: 'docDate', width: 140, render: (r) => h(ConfCell, { value: r.docDate, conf: r.dateConf }) },
  { title: '原因', key: 'reviewReasons', minWidth: 220, render: (r) => h(NText, { type: 'warning' }, { default: () => r.reviewReasons }) },
  { title: '', key: 'op', width: 90, render: (r) => h(NButton, { size: 'small', type: 'primary', onClick: () => open(r) }, { default: () => '去确认' }) }
]

onMounted(load)
</script>

<template>
  <div class="page">
    <page-header eyebrow="Review" title="待确认" desc="置信度低于阈值、或有两个接近候选的文件会出现在这里。对照原文确认或修正后，文件变为“待归档”。" />
    <n-card size="small">
      <n-data-table v-if="rows.length || loading" :columns="columns" :data="rows" :loading="loading" size="small"
                    :row-key="(r: FileRecord) => r.id" />
      <n-empty v-else description="没有需要确认的文件" />
    </n-card>
    <file-detail-drawer :file-id="detailId" :departments="drawerDict.departments" :doc-types="drawerDict.docTypes"
                        @close="detailId = null" @changed="load" />
  </div>
</template>
