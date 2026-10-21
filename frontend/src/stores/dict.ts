import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listTemplates, getTemplate, type TemplateSummary } from '../api/ledger'
import { getStatus, type SystemStatus } from '../api/system'

/** 模板列表、默认模板的部门和类型词典、系统状态，多个页面共用 */
export const useDictStore = defineStore('dict', () => {
  const templates = ref<TemplateSummary[]>([])
  const departments = ref<string[]>([])
  const docTypes = ref<string[]>([])
  const status = ref<SystemStatus | null>(null)
  let loaded = false

  async function load(force = false) {
    if (loaded && !force) return
    const [list, st] = await Promise.all([listTemplates(), getStatus()])
    templates.value = list
    status.value = st
    const def = list.find((t) => t.builtin && t.name === '默认模板') || list[0]
    if (def) {
      const t = await getTemplate(def.id)
      departments.value = t.config.departments.map((d) => d.name)
      docTypes.value = t.config.docTypes.map((d) => d.name)
    }
    loaded = true
  }

  /** 某个模板的部门和类型（批次用的模板可能和默认模板不同） */
  async function dictOf(templateId: number) {
    const t = await getTemplate(templateId)
    return { departments: t.config.departments.map((d) => d.name), docTypes: t.config.docTypes.map((d) => d.name) }
  }

  return { templates, departments, docTypes, status, load, dictOf }
})
