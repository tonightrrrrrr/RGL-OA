"""由 src/main/resources/changelog.json 生成仓库根目录的 CHANGELOG.md，以及某个版本的 GitHub 发布说明。
用法：python docs/tools/changelog_md.py                 生成 CHANGELOG.md
      python docs/tools/changelog_md.py --release 1.5 [--no-asset]   输出 v1.5 的发布说明（Markdown）；--no-asset 表示这个版本不附安装包
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
    if version == '1.4':  # 仓库在 v1.4 第一次提交，之前的版本附在这里
        lines += ['---', '', '首次提交包含了 v1.0 到 v1.3 的全部内容：', '']
        for old in load():
            if old['version'] in ('1.3', '1.2', '1.1', '1.0'):
                lines.append(f"- **v{old['version']} · {old['title']}**（{old['date']}）：{old['summary']}")
        lines.append('')
    base = 'https://github.com/tonightrrrrrr/RGL-OA/blob/main/'
    guide = f'首次安装和升级步骤见 [安装与更新教程]({base}docs/安装与更新教程.md)，完整记录见 [CHANGELOG.md]({base}CHANGELOG.md)。'
    if '--no-asset' in sys.argv:
        lines += ['**下载**：这个版本只有源码（页面下方的 Source code）。安装包请下载 [最新版本](https://github.com/tonightrrrrrr/RGL-OA/releases/latest)。' + guide]
    else:
        lines += ['**下载**：下面的 `OA-Archive-Agent-v%s-win64.zip`，解压后双击 `OA-Archive-Agent.exe`。' % version + guide]
    sys.stdout.reconfigure(encoding='utf-8')
    print('\n'.join(lines).rstrip())


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == '--release':
        release(sys.argv[2])
    else:
        changelog()
