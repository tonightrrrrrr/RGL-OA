/** 模板规则的常用写法：编辑时在字段下方展示它们套用到真实文件后的效果，点击即可套用 */
export const PRESETS = {
  naming: [
    '{部门}_{类型}_{日期}_{标题}',
    '{部门}_{标题}_{日期}',
    '{日期}_{部门}_{标题}',
    '{档号}_{标题}',
    '{年}{月}_{类型}_{标题}',
    '{类型}_{日期}'
  ],
  path: ['{年}/{部门}/{类型}', '{部门}/{年}/{类型}', '{年}/{类型}', '{部门}/{类型}', '{年}/{月}/{部门}', '{年}'],
  archiveNo: ['{年}-{部门代码}-{类型代码}-{序号4}', '{部门代码}{年}{序号4}', '{年}{月}-{类型代码}-{序号4}', '{年}-{部门代码}'],
  dateFormat: ['yyyy-MM-dd', 'yyyyMMdd', 'yyyy年MM月dd日', 'yyyy.MM.dd']
}

export const PLACEHOLDERS = ['部门', '部门代码', '类型', '类型代码', '标题', '日期', '年', '月', '序号4', '原文件名']

/** 预览数据来源：内置示例（原文件名 / 乱命名 / 反面案例）或某个已导入批次 */
export type PreviewSourceKey = 'S' | 'R' | 'N' | `b:${number}`

export interface PreviewSourceArgs { batchId: number | null; renamedSamples: boolean; negativeSamples: boolean }

export function sourceArgs(key: PreviewSourceKey): PreviewSourceArgs {
  if (key === 'S') return { batchId: null, renamedSamples: false, negativeSamples: false }
  if (key === 'R') return { batchId: null, renamedSamples: true, negativeSamples: false }
  if (key === 'N') return { batchId: null, renamedSamples: false, negativeSamples: true }
  return { batchId: Number(key.slice(2)), renamedSamples: false, negativeSamples: false }
}

/** 找出两个字符串中间不同的一段，用于高亮文件名的变化 */
export function diffParts(a: string, b: string): [string, string, string] {
  let i = 0
  while (i < a.length && i < b.length && a[i] === b[i]) i++
  let j = 0
  while (j < a.length - i && j < b.length - i && a[a.length - 1 - j] === b[b.length - 1 - j]) j++
  return [b.slice(0, i), b.slice(i, b.length - j), b.slice(b.length - j)]
}
