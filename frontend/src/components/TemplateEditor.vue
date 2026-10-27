<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  NForm, NFormItem, NInput, NInputNumber, NRadioGroup, NRadio, NSwitch, NDynamicTags, NTabs, NTabPane,
  NTable, NButton, NTag, NSelect, NAlert, useMessage
} from 'naive-ui'
import type { TemplateConfig, TemplateDiff, PreviewResult } from '../api/ledger'
import FieldPreview from './FieldPreview.vue'
import ThresholdAdvisor from './ThresholdAdvisor.vue'
import RecognitionImpact from './RecognitionImpact.vue'
import { PRESETS } from '../utils/templatePresets'

const cfg = defineModel<TemplateConfig>({ required: true })
const props = defineProps<{ readonly: boolean; diff: TemplateDiff | null; preview: PreviewResult | null; previewLoading: boolean }>()
const message = useMessage()

const tab = ref('naming')
const jsonText = ref('')
const jsonError = ref('')
/** 正在编辑的规则：它下方展开实时示例 */
type Field = 'namingPattern' | 'pathPattern' | 'archiveNoPattern' | 'dateFormat' | 'companyName'
const active = ref<Field>('namingPattern')

const changedFields = computed(() => new Set((props.diff?.settings || []).map((s) => s.field)))
const deptChange = computed(() => new Map((props.diff?.departments || []).map((d) => [d.name, d.change])))
const typeChange = computed(() => new Map((props.diff?.docTypes || []).map((d) => [d.name, d.change])))
const deptOptions = computed(() => cfg.value.departments.filter((d) => d.name).map((d) => ({ label: d.name, value: d.name })))
const keywordCount = computed(() => cfg.value.keywordRules.reduce((s, r) => s + r.keywords.length, 0))
const positionCount = computed(() => (cfg.value.positionRules || []).reduce((s, r) => s + r.keywords.length, 0))
const dateOptions = computed(() => PRESETS.dateFormat.map((d) => ({ label: d, value: d })))

function changeTag(change: string | undefined) {
  if (change === 'ADDED') return { type: 'success' as const, text: '新增' }
  if (change === 'CHANGED') return { type: 'warning' as const, text: '已改' }
  return null
}

/** 在光标位置插入占位符；拿不到光标位置时追加到末尾 */
async function insertToken(field: Field, token: string) {
  const el = document.querySelector<HTMLInputElement>(`[data-field="${field}"] input`)
  const value = (cfg.value[field] as string) || ''
  const start = el?.selectionStart ?? value.length
  const end = el?.selectionEnd ?? value.length
  cfg.value[field] = (value.slice(0, start) + token + value.slice(end)) as never
  await nextTick()
  el?.focus()
  el?.setSelectionRange(start + token.length, start + token.length)
}

function applyPreset(field: Field, pattern: string) {
  cfg.value[field] = pattern as never
  message.success('已套用：' + pattern)
}

watch(tab, (t) => {
  if (t === 'json') {
    jsonText.value = JSON.stringify(cfg.value, null, 2)
    jsonError.value = ''
  }
})

function applyJson() {
  try {
    const parsed = JSON.parse(jsonText.value) as TemplateConfig
    if (!Array.isArray(parsed.departments) || !Array.isArray(parsed.docTypes)) {
      throw new Error('缺少 departments 或 docTypes')
    }
    parsed.keywordRules = parsed.keywordRules || []
    parsed.positionRules = parsed.positionRules || []
    cfg.value = parsed
    jsonError.value = ''
    message.success('已应用 JSON，记得保存')
  } catch (e) {
    jsonError.value = 'JSON 格式不正确：' + (e instanceof Error ? e.message : String(e))
  }
}
</script>
<template>
  <n-tabs v-model:value="tab" type="line" animated>
    <n-tab-pane name="naming" tab="命名与归档">
      <p class="muted small" style="margin-top: 0">点击任意一条规则，下方会用真实文件实时显示修改后的效果，并给出常用写法的对比。</p>
      <n-form label-placement="left" label-width="104" :disabled="readonly" size="small">
        <n-form-item label="命名规则" :feedback="changedFields.has('namingPattern') ? '与基准模板不同' : ''">
          <div class="field-col" data-field="namingPattern">
            <n-input v-model:value="cfg.namingPattern" placeholder="新文件名（不含扩展名）" @focus="active = 'namingPattern'" />
            <field-preview v-if="active === 'namingPattern'" kind="naming" :preview="preview" :loading="previewLoading"
                           :current="cfg.namingPattern" :readonly="readonly"
                           @apply="(p) => applyPreset('namingPattern', p)" @insert="(t) => insertToken('namingPattern', t)" />
          </div>
        </n-form-item>
        <n-form-item label="归档路径" :feedback="changedFields.has('pathPattern') ? '与基准模板不同' : ''">
          <div class="field-col" data-field="pathPattern">
            <n-input v-model:value="cfg.pathPattern" placeholder="目录层级，用 / 分隔" @focus="active = 'pathPattern'" />
            <field-preview v-if="active === 'pathPattern'" kind="path" :preview="preview" :loading="previewLoading"
                           :current="cfg.pathPattern" :readonly="readonly"
                           @apply="(p) => applyPreset('pathPattern', p)" @insert="(t) => insertToken('pathPattern', t)" />
          </div>
        </n-form-item>
        <n-form-item label="档号规则" :feedback="changedFields.has('archiveNoPattern') ? '与基准模板不同' : ''">
          <div class="field-col" data-field="archiveNoPattern">
            <n-input v-model:value="cfg.archiveNoPattern" @focus="active = 'archiveNoPattern'" />
            <field-preview v-if="active === 'archiveNoPattern'" kind="archiveNo" :preview="preview" :loading="previewLoading"
                           :current="cfg.archiveNoPattern" :readonly="readonly"
                           @apply="(p) => applyPreset('archiveNoPattern', p)" @insert="(t) => insertToken('archiveNoPattern', t)" />
          </div>
        </n-form-item>
        <n-form-item label="日期格式" :feedback="changedFields.has('dateFormat') ? '与基准模板不同' : ''">
          <div class="field-col" data-field="dateFormat">
            <n-select v-model:value="cfg.dateFormat" filterable tag :options="dateOptions" @focus="active = 'dateFormat'" />
            <field-preview v-if="active === 'dateFormat'" kind="dateFormat" :preview="preview" :loading="previewLoading"
                           :current="cfg.dateFormat" :readonly="readonly" @apply="(p) => applyPreset('dateFormat', p)" />
          </div>
        </n-form-item>
        <n-form-item label="公司名称">
          <div class="field-col" data-field="companyName">
            <n-input v-model:value="cfg.companyName" placeholder="用于去掉标题开头的公司名" @focus="active = 'companyName'" />
            <field-preview v-if="active === 'companyName'" kind="company" :preview="preview" :loading="previewLoading"
                           :current="cfg.companyName" :readonly="readonly" />
          </div>
        </n-form-item>
        <n-form-item label="去掉标题公司名"><n-switch v-model:value="cfg.stripCompanyPrefix" @update:value="active = 'companyName'" /></n-form-item>
        <n-form-item label="置信度阈值" :feedback="changedFields.has('threshold') ? '与基准模板不同' : ''">
          <div class="field-col">
            <div>
              <n-input-number v-model:value="cfg.threshold" :min="0.3" :max="1" :step="0.05" style="width: 140px" />
              <span class="muted small" style="margin-left: 8px">低于该值的文件进入“待确认”</span>
            </div>
            <threshold-advisor :preview="preview" :current="cfg.threshold" :readonly="readonly" @set="(v) => (cfg.threshold = v)" />
          </div>
        </n-form-item>
        <n-form-item label="归档方式">
          <n-radio-group v-model:value="cfg.archiveMode">
            <n-radio value="COPY">复制（推荐，原文件不动）</n-radio>
            <n-radio value="MOVE">移动（归档后删除原文件）</n-radio>
          </n-radio-group>
        </n-form-item>
      </n-form>
    </n-tab-pane>
    <n-tab-pane name="dept" :tab="`部门词典（${cfg.departments.length}）`">
      <recognition-impact :preview="preview" :loading="previewLoading" field="department" />
      <n-table size="small" :single-line="false">
        <thead><tr><th style="width: 150px">部门</th><th style="width: 90px">代码</th><th>别名（文件里出现这些词也算这个部门）</th><th style="width: 46px"></th></tr></thead>
        <tbody>
          <tr v-for="(d, i) in cfg.departments" :key="i">
            <td>
              <n-input v-model:value="d.name" size="small" :disabled="readonly" placeholder="部门名称" />
              <n-tag v-if="changeTag(deptChange.get(d.name))" :type="changeTag(deptChange.get(d.name))!.type" size="tiny" :bordered="false" class="mark">
                {{ changeTag(deptChange.get(d.name))!.text }}
              </n-tag>
            </td>
            <td><n-input v-model:value="d.code" size="small" :disabled="readonly" placeholder="如 XZ" /></td>
            <td><n-dynamic-tags v-model:value="d.aliases" size="small" :disabled="readonly" /></td>
            <td><n-button v-if="!readonly" size="tiny" quaternary type="error" @click="cfg.departments.splice(i, 1)">删</n-button></td>
          </tr>
        </tbody>
      </n-table>
      <n-button v-if="!readonly" size="small" dashed block style="margin-top: 8px"
                @click="cfg.departments.push({ name: '', code: '', aliases: [] })">+ 添加部门</n-button>
    </n-tab-pane>

    <n-tab-pane name="type" :tab="`类型词典（${cfg.docTypes.length}）`">
      <recognition-impact :preview="preview" :loading="previewLoading" field="docType" />
      <n-table size="small" :single-line="false">
        <thead><tr><th style="width: 120px">类型</th><th style="width: 76px">代码</th><th>标题后缀</th><th>内容特征</th><th style="width: 46px"></th></tr></thead>
        <tbody>
          <tr v-for="(t, i) in cfg.docTypes" :key="i">
            <td>
              <n-input v-model:value="t.name" size="small" :disabled="readonly" placeholder="类型名称" />
              <n-tag v-if="changeTag(typeChange.get(t.name))" :type="changeTag(typeChange.get(t.name))!.type" size="tiny" :bordered="false" class="mark">
                {{ changeTag(typeChange.get(t.name))!.text }}
              </n-tag>
            </td>
            <td><n-input v-model:value="t.code" size="small" :disabled="readonly" /></td>
            <td><n-dynamic-tags v-model:value="t.suffixes" size="small" :disabled="readonly" /></td>
            <td><n-dynamic-tags v-model:value="t.features" size="small" :disabled="readonly" /></td>
            <td><n-button v-if="!readonly" size="tiny" quaternary type="error" @click="cfg.docTypes.splice(i, 1)">删</n-button></td>
          </tr>
        </tbody>
      </n-table>
      <p class="muted small">标题后缀：标题以这些词结尾时判为该类型，最可靠。内容特征：正文里出现这些词时作为辅助依据。</p>
      <n-button v-if="!readonly" size="small" dashed block
                @click="cfg.docTypes.push({ name: '', code: '', suffixes: [], features: [] })">+ 添加类型</n-button>
    </n-tab-pane>

    <n-tab-pane name="rule" :tab="`关键词规则（${keywordCount}）`">
      <p class="muted small" style="margin-top: 0">文件里出现这些关键词时，推断属于对应部门。适合文件名和正文都没写部门名的情况，比如“任免”→ 人力资源部。</p>
      <recognition-impact :preview="preview" :loading="previewLoading" field="department" />
      <n-table size="small" :single-line="false">
        <thead><tr><th style="width: 160px">部门</th><th>关键词</th><th style="width: 46px"></th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in cfg.keywordRules" :key="i">
            <td><n-select v-model:value="r.department" :options="deptOptions" size="small" filterable tag :disabled="readonly" placeholder="选择部门" /></td>
            <td><n-dynamic-tags v-model:value="r.keywords" size="small" :disabled="readonly" /></td>
            <td><n-button v-if="!readonly" size="tiny" quaternary type="error" @click="cfg.keywordRules.splice(i, 1)">删</n-button></td>
          </tr>
        </tbody>
      </n-table>
      <n-button v-if="!readonly" size="small" dashed block style="margin-top: 8px"
                @click="cfg.keywordRules.push({ department: '', keywords: [] })">+ 添加规则</n-button>
    </n-tab-pane>

    <n-tab-pane name="position" :tab="`岗位规则（${positionCount}）`">
      <p class="muted small" style="margin-top: 0">
        只用于简历：按“应聘岗位”推测这个人属于哪个部门，比如“软件工程师”→ 研发部。推测出来的部门都会进入待确认，请你确认此人是否属于这个部门。
        一个岗位命中几个部门时，取更长、更靠后的词（“销售会计”算财务部，并提示也可能是销售部）。英文缩写（HR、QC）要整词出现。
        都匹配不上时，如果开启了 AI 助手，会把岗位名称交给小R推测（不发简历内容）。
      </p>
      <n-table size="small" :single-line="false">
        <thead><tr><th style="width: 160px">部门</th><th>岗位关键词</th><th style="width: 46px"></th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in cfg.positionRules" :key="i">
            <td><n-select v-model:value="r.department" :options="deptOptions" size="small" filterable tag :disabled="readonly" placeholder="选择部门" /></td>
            <td><n-dynamic-tags v-model:value="r.keywords" size="small" :disabled="readonly" /></td>
            <td><n-button v-if="!readonly" size="tiny" quaternary type="error" @click="cfg.positionRules.splice(i, 1)">删</n-button></td>
          </tr>
        </tbody>
      </n-table>
      <n-button v-if="!readonly" size="small" dashed block style="margin-top: 8px"
                @click="(cfg.positionRules = cfg.positionRules || []).push({ department: '', keywords: [] })">+ 添加规则</n-button>
    </n-tab-pane>

    <n-tab-pane name="json" tab="JSON">
      <p class="muted small" style="margin-top: 0">可以直接编辑完整配置，改完点“应用”，再保存。</p>
      <n-input v-model:value="jsonText" type="textarea" :autosize="{ minRows: 18, maxRows: 34 }" class="mono" :disabled="readonly" />
      <n-alert v-if="jsonError" type="error" :show-icon="false" style="margin-top: 8px">{{ jsonError }}</n-alert>
      <n-button v-if="!readonly" size="small" type="primary" style="margin-top: 8px" @click="applyJson">应用 JSON</n-button>
    </n-tab-pane>
  </n-tabs>
</template>

<style scoped>
.small { font-size: 12px; }
.mark { margin-top: 4px; }
.field-col { display: flex; flex-direction: column; width: 100%; }
:deep(.n-form-item-feedback) { color: var(--warning); font-size: 12px; }
</style>