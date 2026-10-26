<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  NAlert, NAutoComplete, NButton, NDrawer, NDrawerContent, NForm, NFormItem, NInput, NInputNumber, NSpace, NSwitch,
  useMessage
} from 'naive-ui'
import AppIcon from './AppIcon.vue'
import {
  getAiSettings, listAiModels, saveAiSettings, testAi, type AiSettingsUpdate, type AiSettingsView, type AiTestResult, type ReasoningLevel
} from '../api/ai'
import { errorMessage } from '../api/http'
import { getScope, saveScope, type ScopeView } from '../api/rollback'
import { chooseFolder } from '../api/archive'

const show = defineModel<boolean>('show', { required: true })
const emit = defineEmits<{ saved: [v: AiSettingsView] }>()
const message = useMessage()

/** 常见服务的接口地址；都兼容 OpenAI 格式，中转站一般也填到 /v1 */
const PRESETS = [
  { label: 'OpenAI', url: 'https://api.openai.com/v1' },
  { label: 'DeepSeek', url: 'https://api.deepseek.com/v1' },
  { label: '通义千问', url: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  { label: '智谱 GLM', url: 'https://open.bigmodel.cn/api/paas/v4' },
  { label: 'Kimi', url: 'https://api.moonshot.cn/v1' },
  { label: 'Ollama 本地', url: 'http://127.0.0.1:11434/v1' }
]

const view = ref<AiSettingsView | null>(null)
const form = reactive({ enabled: false, baseUrl: '', apiKey: '', model: '', allowContent: false, proxy: '', timeoutSeconds: 90,
  reasoning: 'high' as ReasoningLevel })
/** 思考强度：实测中转站不设置时几乎不思考 */
const REASONING = [
  { value: 'low', label: '低', desc: '最快' },
  { value: 'medium', label: '中', desc: '平衡' },
  { value: 'high', label: '高', desc: '想得更多，回复慢一些' },
  { value: 'off', label: '不设置', desc: '交给接口默认' }
] as const
const models = ref<string[]>([])
const testing = ref(false)
const saving = ref(false)
const fetching = ref(false)
const result = ref<AiTestResult | null>(null)

const modelOptions = computed(() => {
  const q = form.model.trim().toLowerCase()
  return models.value.filter((m) => !q || m.toLowerCase().includes(q)).slice(0, 50).map((m) => ({ label: m, value: m }))
})

async function load() {
  try {
    const v = await getAiSettings()
    view.value = v
    Object.assign(form, { enabled: v.enabled, baseUrl: v.baseUrl, apiKey: '', model: v.model, allowContent: v.allowContent,
      proxy: v.proxy, timeoutSeconds: v.timeoutSeconds, reasoning: v.reasoning || 'high' })
    result.value = null
  } catch (e) {
    message.error(errorMessage(e))
  }
}

watch(show, (s) => { if (s) { load(); loadScope() } })

/** 小R 文件操作的允许范围：开关和手动添加的文件夹改了马上保存 */
const scope = ref<ScopeView | null>(null)
const scopeBusy = ref(false)
const SOURCE_LABEL: Record<string, string> = { ARCHIVE: '归档目录', IMPORT: '导入过', EXTRA: '手动添加', AI: '对话授权' }

async function loadScope() {
  try {
    scope.value = await getScope()
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function updateScope(u: Parameters<typeof saveScope>[0]) {
  scopeBusy.value = true
  try {
    scope.value = await saveScope(u)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    scopeBusy.value = false
  }
}

async function addRoot() {
  try {
    const r = await chooseFolder('选择允许小R操作的文件夹')
    if (!r.path || !scope.value) return
    await updateScope({ extraRoots: [...scope.value.extraRoots, r.path] })
  } catch (e) {
    message.error(errorMessage(e))
  }
}

function removeRoot(p: string) {
  if (!scope.value) return
  updateScope({ extraRoots: scope.value.extraRoots.filter((x) => x !== p) })
}

function removeGrant(p: string) {
  if (!scope.value) return
  updateScope({ aiRoots: scope.value.aiRoots.filter((x) => x !== p) })
}

function payload(): AiSettingsUpdate {
  return { enabled: form.enabled, baseUrl: form.baseUrl, apiKey: form.apiKey || undefined, model: form.model,
    allowContent: form.allowContent, proxy: form.proxy, timeoutSeconds: form.timeoutSeconds, reasoning: form.reasoning }
}

async function fetchModels() {
  fetching.value = true
  try {
    models.value = await listAiModels(payload())
    message.success(`获取到 ${models.value.length} 个模型，在“模型”里输入可以筛选`)
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    fetching.value = false
  }
}

async function test() {
  testing.value = true
  result.value = null
  try {
    result.value = await testAi(payload())
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    testing.value = false
  }
}

async function save() {
  saving.value = true
  try {
    // 开启即视为已看过数据发送提示（提示就在开关下方）
    const v = await saveAiSettings({ ...payload(), privacyAcknowledged: form.enabled || undefined })
    view.value = v
    form.apiKey = ''
    emit('saved', v)
    message.success(v.ready ? '已保存，可以和小R对话了' : '已保存')
    if (v.ready) show.value = false
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <n-drawer v-model:show="show" :width="480" placement="right">
    <n-drawer-content title="连接设置" closable :native-scrollbar="false">
      <n-form label-placement="top" :show-feedback="false" class="form">
        <n-form-item label="启用 AI 助手">
          <n-space align="center">
            <n-switch v-model:value="form.enabled" />
            <span class="muted small">关闭后，程序的其他功能照常离线使用</span>
          </n-space>
        </n-form-item>
        <n-alert v-if="form.enabled" type="warning" :show-icon="false" class="privacy">
          开启后，你的问题以及小R查到的台账信息（文件名、标题、部门、类型、日期、档号等）会发送到下面的接口地址。
          使用中转站时，中转站也能看到这些内容。涉密文件请不要开启，或者使用本地部署的模型。
        </n-alert>
        <n-form-item label="接口地址">
          <n-input v-model:value="form.baseUrl" placeholder="https://api.openai.com/v1，或中转站地址" clearable />
        </n-form-item>
        <div class="presets">
          <button v-for="p in PRESETS" :key="p.label" class="preset ripple" :class="{ on: form.baseUrl === p.url }"
                  @click="form.baseUrl = p.url">{{ p.label }}</button>
        </div>
        <n-form-item label="API Key">
          <n-input v-model:value="form.apiKey" type="password" show-password-on="click"
                   :placeholder="view?.hasKey ? `已保存 ${view.keyHint}，不改请留空` : 'sk-...（Ollama 本地可以随便填）'" />
        </n-form-item>
        <n-form-item label="模型">
          <div class="model-row">
            <n-auto-complete v-model:value="form.model" :options="modelOptions" placeholder="如 gpt-4o、deepseek-chat、qwen-plus" clearable />
            <n-button secondary :loading="fetching" @click="fetchModels">获取列表</n-button>
          </div>
        </n-form-item>
        <n-form-item label="思考强度">
          <div class="levels">
            <button v-for="r in REASONING" :key="r.value" class="level ripple" :class="{ on: form.reasoning === r.value }"
                    @click="form.reasoning = r.value">
              <b>{{ r.label }}</b><span>{{ r.desc }}</span>
            </button>
          </div>
        </n-form-item>
        <n-form-item label="允许发送文件正文摘要">
          <n-space align="center">
            <n-switch v-model:value="form.allowContent" />
            <span class="muted small">开启后小R能读正文前 1500 字，判断更准；默认只发识别出的字段</span>
          </n-space>
        </n-form-item>
        <div class="two">
          <n-form-item label="代理（可选）">
            <n-input v-model:value="form.proxy" placeholder="127.0.0.1:7890" clearable />
          </n-form-item>
          <n-form-item label="超时（秒）">
            <n-input-number v-model:value="form.timeoutSeconds" :min="15" :max="600" :step="15" />
          </n-form-item>
        </div>
      </n-form>

      <div v-if="scope" class="scope">
        <div class="scope-head">
          <b>文件操作范围</b>
          <span class="muted small">小R只能在这些文件夹里新建、改名、移动、删除；改动马上生效</span>
        </div>
        <div class="scope-row">
          <n-switch :value="scope.archiveRoots" size="small" :loading="scopeBusy"
                    @update:value="(v: boolean) => updateScope({ archiveRoots: v })" />
          <span>归档目录</span>
        </div>
        <div class="scope-row">
          <n-switch :value="scope.importFolders" size="small" :loading="scopeBusy"
                    @update:value="(v: boolean) => updateScope({ importFolders: v })" />
          <span>导入过的文件夹</span>
        </div>
        <div v-for="p in scope.extraRoots" :key="p" class="scope-extra">
          <app-icon name="folder" :size="14" /><span class="mono path">{{ p }}</span>
          <button class="rm ripple" title="移出范围" @click="removeRoot(p)"><app-icon name="close" :size="13" /></button>
        </div>
        <div v-for="p in scope.aiRoots" :key="p" class="scope-extra">
          <span class="tag">对话授权</span><span class="mono path">{{ p }}</span>
          <button class="rm ripple" title="撤销授权" @click="removeGrant(p)"><app-icon name="close" :size="13" /></button>
        </div>
        <n-button size="small" dashed :loading="scopeBusy" @click="addRoot">
          <template #icon><app-icon name="folderPlus" :size="15" /></template>添加文件夹
        </n-button>
        <details class="eff">
          <summary class="muted small">当前生效的 {{ scope.effective.length }} 个文件夹</summary>
          <div v-for="r in scope.effective" :key="r.path" class="eff-row">
            <span class="tag">{{ SOURCE_LABEL[r.source] }}</span><span class="mono path">{{ r.path }}</span>
          </div>
          <div v-if="!scope.effective.length" class="muted small">（空）小R现在不能操作任何文件夹</div>
        </details>
        <div class="muted small note">
          也可以在对话里让小R申请某个文件夹的权限，你确认后才会加入，标为“对话授权”。
          系统目录、程序数据目录、整个磁盘永远不能操作，手动添加和对话授权都不行；已归档、记在台账里的文件要先撤销归档。
          删除的文件放进系统回收站，每次操作后都可以在顶栏“回档”撤回。
        </div>
      </div>

      <div v-if="result" class="result" :class="result.ok ? (result.toolCalls ? 'good' : 'meh') : 'bad'">
        <div class="result-head">
          <app-icon :name="result.ok ? 'check' : 'close'" :size="16" />
          <b>{{ result.ok ? '连接成功' : '连接失败' }}</b>
          <span v-if="result.latencyMs" class="muted small">{{ (result.latencyMs / 1000).toFixed(1) }} 秒</span>
        </div>
        <div class="small">{{ result.message }}</div>
        <div v-if="result.modelCount != null" class="muted small">
          接口提供 {{ result.modelCount }} 个模型{{ result.modelListed === false ? '，列表里没有你填的模型名，请检查拼写' : '' }}
        </div>
      </div>

      <template #footer>
        <n-space justify="end">
          <n-button :loading="testing" @click="test">测试连接</n-button>
          <n-button type="primary" :loading="saving" @click="save">保存</n-button>
        </n-space>
      </template>
    </n-drawer-content>
  </n-drawer>
</template>

<style scoped>
.form { display: flex; flex-direction: column; gap: 14px; }
.privacy { font-size: 12px; line-height: 1.6; }
.presets { display: flex; flex-wrap: wrap; gap: 6px; margin-top: -6px; }
.preset {
  font: inherit; font-size: 12px; padding: 3px 10px; border-radius: 999px; cursor: pointer; color: var(--text-2);
  border: 1px solid var(--border); background: var(--surface-2); transition: border-color .15s, color .15s, transform .12s;
}
.preset:hover { border-color: var(--accent); color: var(--accent); }
.preset:active { transform: scale(.96); }
.preset.on { border-color: var(--accent); color: var(--accent); background: var(--accent-softer); }
.model-row { display: flex; gap: 8px; width: 100%; }
.levels { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; width: 100%; }
.level { display: flex; flex-direction: column; gap: 2px; align-items: flex-start; padding: 7px 10px; border-radius: 10px; cursor: pointer;
  font: inherit; text-align: left; border: 1px solid var(--border); background: var(--surface-2); color: var(--text-2);
  transition: border-color .15s, color .15s, background-color .15s; }
.level b { font-size: 13px; font-weight: 600; }
.level span { font-size: 11px; color: var(--text-3); }
.level:hover { border-color: var(--accent); }
.level.on { border-color: var(--accent); background: var(--accent-softer); color: var(--accent); }
.model-row > :first-child { flex: 1; }
.two { display: grid; grid-template-columns: 1fr 140px; gap: 12px; }
.scope { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 8px; }
.scope-head { display: flex; flex-direction: column; gap: 2px; }
.scope-row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.scope-extra { display: flex; align-items: center; gap: 6px; font-size: 12px; padding: 4px 8px; border-radius: 8px; background: var(--surface-2); }
.path { flex: 1; min-width: 0; word-break: break-all; font-size: 12px; }
.rm { border: none; background: none; color: var(--text-3); cursor: pointer; padding: 3px; border-radius: 6px; display: grid; place-items: center; }
.rm:hover { color: var(--error); background: var(--error-soft); }
.eff summary { cursor: pointer; }
.eff-row { display: flex; gap: 6px; align-items: baseline; margin-top: 4px; }
.tag { flex: none; font-size: 11px; padding: 0 6px; border-radius: 999px; background: var(--accent-softer); color: var(--accent); }
.note { line-height: 1.6; }
.result { margin-top: 16px; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--border); display: flex; flex-direction: column; gap: 4px; }
.result-head { display: flex; align-items: center; gap: 6px; }
.result.good { background: var(--success-soft); border-color: transparent; }
.result.good .result-head { color: var(--success); }
.result.meh { background: var(--warning-soft); border-color: transparent; }
.result.meh .result-head { color: var(--warning); }
.result.bad { background: var(--error-soft); border-color: transparent; }
.result.bad .result-head { color: var(--error); }
</style>
