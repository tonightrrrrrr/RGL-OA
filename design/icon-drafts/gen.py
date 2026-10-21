"""生成方案 B（戴文件夹帽的 Q 版小人）的图标草图和对比预览页。
用法：python gen.py   输出到本目录：c1/c2/c3.svg、c*-small.svg、preview.html
第二轮：脸改成二次元画风（大眼睛、睫毛、长发、发夹），参考用户提供的三张原画
"""
import math
from pathlib import Path

OUT = Path(__file__).parent
INK = '#2b2b33'          # 描边：深灰，不用纯黑，更柔和
SKIN = '#fffdf8'         # 脸和头发：暖白
PINK = '#f49ab0'         # 腮红
AMBER = '#f7c948'        # 星星


def star(cx, cy, r, rot=0.0, fill=AMBER, sw=5):
    pts = []
    for i in range(10):
        a = math.radians(rot - 90 + i * 36)
        rr = r if i % 2 == 0 else r * 0.48
        pts.append(f'{cx + rr * math.cos(a):.1f},{cy + rr * math.sin(a):.1f}')
    return (f'<polygon points="{" ".join(pts)}" fill="{fill}" stroke="{INK}" stroke-width="{sw}" '
            f'stroke-linejoin="round"/>')


def folder_hat(main, light, tilt, label=True):
    """文件夹形状的帽子：后片带标签，前片稍浅，中间露出一角白纸，前片上写 RGL"""
    text = ('<text x="128" y="101" text-anchor="middle" font-family="Arial Rounded MT Bold, Arial, sans-serif" '
            'font-size="36" fill="#ffffff" letter-spacing="1">RGL</text>') if label else ''
    return f'''
  <g transform="rotate({tilt} 128 112) translate(128 112) scale(0.88) translate(-128 -112)">
    <path d="M166 48 C160 26 180 12 194 22 C202 28 196 38 188 34" fill="none" stroke="{INK}" stroke-width="6"
          stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M60 106 L60 40 Q60 28 72 28 L100 28 Q108 28 112 35 L118 44 L184 44 Q196 44 196 56 L196 106 Z"
          fill="{main}" stroke="{INK}" stroke-width="7" stroke-linejoin="round"/>
    <path d="M78 62 L80 40 Q80 35 85 35 L174 39 Q179 39 179 44 L177 64 Z" fill="#ffffff" stroke="{INK}" stroke-width="4"
          stroke-linejoin="round"/>
    <path d="M88 46 L150 48 M88 54 L166 56" stroke="{light}" stroke-width="3.5" stroke-linecap="round"/>
    <path d="M46 112 Q42 64 62 62 L194 60 Q214 60 210 112 Z" fill="{light}" stroke="{INK}" stroke-width="7"
          stroke-linejoin="round"/>
    <path d="M62 70 Q120 67 196 68" stroke="#ffffff" stroke-opacity="0.5" stroke-width="4" fill="none" stroke-linecap="round"/>
    {text}
    <path d="M30 116 Q128 100 226 116" stroke="{INK}" stroke-width="8" fill="none" stroke-linecap="round"/>
  </g>'''


# ---------------------------------------------------------------- 二次元头部（第二轮）
# 参考原画：外轮廓粗线、内部细线；长发垂到两侧并收成尖角，尖角刘海，脸型是下巴略尖的鹅蛋脸

HAIR_BACK = f'''
  <path d="M46 104 C30 140 30 196 38 226 L54 216 L58 236 L74 222 L78 238 L92 214
           Q128 204 164 214 L178 238 L182 222 L198 236 L202 216 L218 226 C226 196 226 140 210 104 Z"
        fill="{SKIN}" stroke="{INK}" stroke-width="7" stroke-linejoin="round"/>'''

FACE_SHAPE = f'''
  <path d="M70 128 C66 172 82 210 106 224 Q128 236 150 224 C174 210 190 172 186 128 Z"
        fill="{SKIN}" stroke="{INK}" stroke-width="2.6" stroke-linejoin="round"/>'''

# 两侧垂下的发束：从帽檐一直垂到肩部，盖住脸颊外侧
SIDE_LOCKS = f'''
  <path d="M60 116 C54 156 56 196 64 226 L72 212 L80 224 C78 190 78 156 82 124 Z" fill="{SKIN}" stroke="{INK}"
        stroke-width="3" stroke-linejoin="round"/>
  <path d="M196 116 C202 156 200 196 192 226 L184 212 L176 224 C178 190 178 156 174 124 Z" fill="{SKIN}" stroke="{INK}"
        stroke-width="3" stroke-linejoin="round"/>'''

# 尖角刘海：几簇长短不一的发尖，中间留缝露出额头，发尖落在眼睛上方
BANGS = f'''
  <path d="M62 112 C66 130 72 144 80 152 C82 140 86 132 92 126 C96 140 100 150 108 158 C110 144 114 134 120 128
           C124 146 126 158 128 166 C132 152 136 140 142 130 C146 142 152 150 160 156 C160 144 164 134 170 128
           C174 138 180 146 188 150 C190 136 194 124 196 112 Z" fill="{SKIN}" stroke="{INK}" stroke-width="3"
        stroke-linejoin="round"/>
  <path d="M96 118 Q100 134 106 148 M128 118 Q127 138 128 156 M156 118 Q154 132 158 146" stroke="{INK}" stroke-width="1.6"
        fill="none" stroke-linecap="round"/>'''

HEAD = HAIR_BACK + FACE_SHAPE + SIDE_LOCKS + BANGS
AHOGE = ''  # 呆毛画在帽子组里，从帽顶钻出来


def anime_eye(cx, cy, iris, deep, wink=False):
    """二次元大眼：上眼线加粗并在外眼角挑起，彩色虹膜 + 深色瞳孔 + 两个高光"""
    side = -1 if cx < 128 else 1  # 外眼角朝哪一侧
    if wink:  # 笑眯眼：一道下弯的粗睫毛线，外眼角挑一笔
        return (f'<path d="M{cx - 15 * side} {cy - 2} Q{cx} {cy + 10} {cx + 16 * side} {cy + 2} l{4 * side} 3" stroke="{INK}" '
                f'stroke-width="5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>')
    return f'''
  <ellipse cx="{cx}" cy="{cy + 4}" rx="13" ry="17" fill="{iris}"/>
  <ellipse cx="{cx}" cy="{cy + 9}" rx="7.5" ry="10" fill="{deep}"/>
  <ellipse cx="{cx}" cy="{cy + 14}" rx="9" ry="5" fill="#ffffff" opacity="0.28"/>
  <circle cx="{cx - 4 * side}" cy="{cy - 1}" r="4.6" fill="#ffffff"/>
  <circle cx="{cx + 5 * side}" cy="{cy + 12}" r="2" fill="#ffffff"/>
  <path d="M{cx - 13 * side} {cy - 9} Q{cx + 1 * side} {cy - 21} {cx + 15 * side} {cy - 5} l{4 * side} 3" stroke="{INK}" stroke-width="6"
        fill="none" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M{cx - 9} {cy + 21} Q{cx} {cy + 24} {cx + 9} {cy + 21} M{cx + 13 * side} {cy + 12} l{3 * side} 3" stroke="{INK}"
        stroke-width="1.8" fill="none" stroke-linecap="round"/>'''


def soft_blush(cx, cy):
    return (f'<ellipse cx="{cx}" cy="{cy}" rx="11" ry="6" fill="{PINK}" opacity="0.5"/>'
            f'<path d="M{cx - 6} {cy + 3} l3 -7 M{cx} {cy + 3} l3 -7 M{cx + 6} {cy + 3} l3 -7" '
            f'stroke="#e9768f" stroke-width="2" stroke-linecap="round"/>')


def heart(cx, cy, s, fill='#f48aa6'):
    return (f'<path transform="translate({cx} {cy}) rotate(-14) scale({s})" d="M0 6 C-10 -2 -10 -10 -4 -10 '
            f'C-1 -10 0 -7 0 -6 C0 -7 1 -10 4 -10 C10 -10 10 -2 0 6 Z" fill="{fill}" stroke="{INK}" '
            f'stroke-width="{2.4 / s:.2f}" stroke-linejoin="round"/>')


MOUTH_SMALL = (f'<path d="M123 210 Q128 217 133 210 Z" fill="#c9566e" stroke="{INK}" stroke-width="2.4" '
               f'stroke-linejoin="round"/>')
MOUTH_OPEN = (f'<path d="M118 206 Q128 223 138 206 Z" fill="#c9566e" stroke="{INK}" stroke-width="2.6" '
              f'stroke-linejoin="round"/><path d="M122 214 Q128 218 134 214" stroke="#f3a3b5" stroke-width="2.6" '
              f'fill="none" stroke-linecap="round"/>')
MOUTH_CAT = (f'<path d="M120 210 Q124 215 128 210 Q132 215 136 210" stroke="{INK}" stroke-width="2.6" fill="none" '
             f'stroke-linecap="round" stroke-linejoin="round"/>')


def anime_face(iris, deep, mouth, wink=False):
    return (anime_eye(102, 176, iris, deep, wink) + anime_eye(154, 176, iris, deep, wink)
            + soft_blush(86, 204) + soft_blush(170, 204) + mouth)


def tile(bg, inner):
    return f'''
  <rect x="8" y="8" width="240" height="240" rx="56" fill="{bg}"/>
  <rect x="8" y="8" width="240" height="240" rx="56" fill="none" stroke="#000" stroke-opacity="0.06" stroke-width="2"/>
  <g transform="translate(128 130) scale(0.84) translate(-128 -128)">{inner}</g>'''


def svg(body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">{body}\n</svg>\n'


DRAFTS = {
    # 粉色系：粉色眼睛 + 爱心发夹，参考第三张原画
    'c1': ('浅粉底板 · 靛蓝帽 · 粉瞳 · 爱心发夹', svg(tile('#fdeef2',
        heart(42, 70, 1.5) + heart(220, 196, 1.1) + HEAD + anime_face('#f48aa6', '#b83e62', MOUTH_SMALL)
        + heart(70, 146, 1.1) + folder_hat('#4a5fd8', '#6f86ff', -6)))),
    # 黄色系：琥珀色眼睛 + 星星，张嘴笑，参考第二张原画
    'c2': ('奶油底板 · 靛蓝帽 · 金瞳 · 星星发夹', svg(tile('#fff4dc',
        star(44, 66, 17, 12) + star(216, 194, 12, -14) + HEAD + anime_face('#f7c948', '#c27c10', MOUTH_OPEN)
        + star(188, 148, 9, 8, sw=2.6) + folder_hat('#4a5fd8', '#6f86ff', -6)))),
    # 青色系：笑眯眼，参考第一张原画
    'c3': ('浅青底板 · 青色帽 · 笑眯眼', svg(tile('#e3f6f7',
        star(46, 66, 15, 10, fill='#8fdde3') + star(216, 196, 12, -10, fill='#8fdde3') + HEAD
        + anime_face('#5fc4cc', '#1f7d86', MOUTH_CAT, wink=True)
        + star(70, 148, 8, 6, fill='#8fdde3', sw=2.4) + folder_hat('#249aa6', '#4cc3cc', -6)))),
}


def small(main, light, bg, iris, wink=False):
    """32px / 16px 简化版：去掉字、刘海细节和星星，保留长发轮廓、帽子、彩色大眼和腮红"""
    if wink:
        eyes = (f'<path d="M9.4 19.6 Q11.5 21.4 13.6 19.6 M18.4 19.6 Q20.5 21.4 22.6 19.6" stroke="{INK}" '
                f'stroke-width="1.5" fill="none" stroke-linecap="round"/>')
    else:
        eyes = (f'<ellipse cx="11.5" cy="20.3" rx="1.9" ry="2.5" fill="{iris}"/>'
                f'<ellipse cx="20.5" cy="20.3" rx="1.9" ry="2.5" fill="{iris}"/>'
                f'<path d="M9.3 18.2 Q11.5 16.8 13.8 18.3 M18.2 18.3 Q20.5 16.8 22.7 18.2" stroke="{INK}" '
                f'stroke-width="1.3" fill="none" stroke-linecap="round"/>'
                f'<circle cx="11" cy="19.4" r="0.75" fill="#fff"/><circle cx="20" cy="19.4" r="0.75" fill="#fff"/>')
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <rect x="1" y="1" width="30" height="30" rx="8" fill="{bg}"/>
  <path d="M5.5 13 C3.8 18 4 25 5 29 L7.5 27.5 L8.5 29.5 L23.5 29.5 L24.5 27.5 L27 29 C28 25 28.2 18 26.5 13 Z"
        fill="{SKIN}" stroke="{INK}" stroke-width="1.5" stroke-linejoin="round"/>
  <path d="M8 15 C7.5 21 10 26 16 27 C22 26 24.5 21 24 15 Z" fill="{SKIN}" stroke="{INK}" stroke-width="0.7"/>
  {eyes}
  <ellipse cx="9.6" cy="23.4" rx="1.6" ry="0.9" fill="{PINK}" opacity="0.85"/>
  <ellipse cx="22.4" cy="23.4" rx="1.6" ry="0.9" fill="{PINK}" opacity="0.85"/>
  <g transform="rotate(-6 16 10)">
    <path d="M7 13.5 L7 5.5 Q7 4 8.5 4 L12.5 4 L14 6 L23.5 6 Q25 6 25 7.5 L25 13.5 Z" fill="{main}" stroke="{INK}"
          stroke-width="1.4" stroke-linejoin="round"/>
    <path d="M5 14.5 L27 14.5 L26 9 L6 9 Z" fill="{light}" stroke="{INK}" stroke-width="1.4" stroke-linejoin="round"/>
  </g>
</svg>
'''


SMALL = {'c1': small('#4a5fd8', '#6f86ff', '#fdeef2', '#e8708f'),
         'c2': small('#4a5fd8', '#6f86ff', '#fff4dc', '#e8b52c'),
         'c3': small('#249aa6', '#4cc3cc', '#e3f6f7', '#3fb2bb', wink=True)}

PAGE_HEAD = '''<!doctype html><meta charset="utf-8"><title>RGL 图标草图 · 方案 B</title>
<style>
body{margin:0;padding:28px 32px;font:14px "Microsoft YaHei",sans-serif;background:#eceef2;color:#222}
h1{font-size:20px;margin:0 0 4px} .tip{color:#666;margin:0 0 20px}
.card{background:#fff;border-radius:14px;padding:18px 20px;margin-bottom:18px;box-shadow:0 2px 10px rgba(0,0,0,.06)}
.card h2{font-size:15px;margin:0 0 12px} .row{display:flex;gap:16px;align-items:center}
.col{flex:1;display:flex;flex-direction:column;gap:10px}
.pane{flex:1;border-radius:10px;padding:14px 16px;display:flex;align-items:flex-end;gap:22px}
.light{background:#f7f7f9} .dark{background:#1f2430;color:#c9cfdb}
.cell{text-align:center;font-size:11px;color:#8a8f99} .cell img,.cell canvas{display:block;margin:0 auto 6px}
.px{image-rendering:pixelated} .desk{width:76px;text-align:center;font-size:12px;opacity:1}
.bar{display:flex;align-items:center;gap:8px;background:rgba(255,255,255,.08);border-radius:6px;padding:6px 10px;font-size:12px}
.dark .bar{background:#2b3140}
</style>
<h1>RGL 图标草图 · 方案 B 第二轮（二次元画风）</h1>
<p class="tip">每版都在浅色和深色背景上显示 256 / 64 / 48 / 32 / 16 px。32 px 和 16 px 用简化版（去掉字和刘海），最右边是 16 px 放大 4 倍后的像素效果。</p>
'''


def pane(key, cls):
    cells = ''.join(f'<div class="cell"><img src="{key}.svg" width="{s}" height="{s}">{s}px</div>' for s in (128, 64, 48))
    cells += ''.join(f'<div class="cell"><img src="{key}-small.svg" width="{s}" height="{s}">{s}px</div>' for s in (32, 16))
    cells += f'<div class="cell"><canvas class="px" data-src="{key}-small.svg" width="16" height="16" style="width:64px;height:64px"></canvas>16px ×4</div>'
    cells += f'<div class="cell desk"><img src="{key}.svg" width="48" height="48">RGL</div>'
    cells += f'<div class="bar"><img src="{key}-small.svg" width="16" height="16">RGL-OA智能归档助手</div>'
    return f'<div class="pane {cls}">{cells}</div>'


def main():
    page = PAGE_HEAD
    for key, (title, body) in DRAFTS.items():
        (OUT / f'{key}.svg').write_text(body, encoding='utf-8')
        (OUT / f'{key}-small.svg').write_text(SMALL[key], encoding='utf-8')
        page += (f'<div class="card"><h2>{key.upper()}　{title}</h2><div class="row">'
                 f'<div class="cell" style="align-self:center"><img src="{key}.svg" width="200" height="200"></div>'
                 f'<div class="col">{pane(key, "light")}{pane(key, "dark")}</div></div></div>\n')
    page += '''<script>
document.querySelectorAll('canvas[data-src]').forEach(c => {
  const img = new Image(); img.onload = () => c.getContext('2d').drawImage(img, 0, 0, 16, 16); img.src = c.dataset.src
})
</script>'''
    (OUT / 'preview.html').write_text(page, encoding='utf-8')
    print('ok', [p.name for p in OUT.iterdir()])


if __name__ == '__main__':
    main()
