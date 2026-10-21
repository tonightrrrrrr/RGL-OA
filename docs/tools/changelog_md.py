"""由 src/main/resources/changelog.json 生成仓库根目录的 CHANGELOG.md，以及某个版本的 GitHub 发布说明。
用法：python docs/tools/changelog_md.py                 生成 CHANGELOG.md
      python docs/tools/changelog_md.py --release 1.5   输出 v1.5 的发布说明（Markdown）
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TYPES = [('new', '新增'), ('improve', '优化'), ('fix', '修复')]


def load():
    return json.loads((ROOT / 'src/main/resources/changelog.json').read_text(encoding='utf-8'))


def section(r, level='##'):
    out = [f"{level} v{r['version']} · {r['title']}（{r['date']}）", '', r['summary'], '']
    if r.get('note'):
        out += [f"> {r['note']}", '']
    for t, label in TYPES:
        items = [c['text'] for c in r['changes'] if c['type'] == t]
        if items:
            out += [f'**{label}**', ''] + [f'- {i}' for i in items] + ['']
    return out


def changelog():
    lines = ['# 更新记录', '',
             '程序版本和 [docs/技术难点与解决方案.md](docs/技术难点与解决方案.md) 的文档版本保持一致。',
             '本文件由 `src/main/resources/changelog.json` 生成（`python docs/tools/changelog_md.py`），程序里的“版本更新”页面用的也是同一份数据。',
             '']
    for r in load():
        lines += section(r)
    (ROOT / 'CHANGELOG.md').write_text('\n'.join(lines).rstrip() + '\n', encoding='utf-8')
    print('已生成 CHANGELOG.md')


def release(version):
    r = next(x for x in load() if x['version'] == version)
    lines = section(r, '##')[2:]  # 标题由 GitHub 发布页显示，这里从摘要开始
    lines += ['**下载**：下面的 `OA-Archive-Agent-v%s-win64.zip`，解压后双击 `OA-Archive-Agent.exe`。' % version
              + '首次安装和升级步骤见 [安装与更新教程](docs/安装与更新教程.md)，完整记录见 [CHANGELOG.md](CHANGELOG.md)。']
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines).rstrip())


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--release':
        release(sys.argv[2])
    else:
        changelog()
