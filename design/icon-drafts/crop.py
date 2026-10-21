"""把用户提供的三张原画裁成方形图标，在空白处加上手写 RGL，导出各个尺寸。
用法：<装有 Pillow 11 的 python> crop.py              生成三版对比：crop/p*-<尺寸>.png 和 preview-crop.html
      <装有 Pillow 11 的 python> crop.py --final p1   把选定版本导出到程序资源（当前使用 p1），之后重新生成 app.ico
输入：source/ref-*.png（原画截图）
处理：裁切 → 去鼠标指针 → 背景提白、线条加深 →（小尺寸加粗线条）→ 写手写 RGL（48px 及以上）→ 圆角白底 → 缩放
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).parent
OUT = HERE / 'crop'
SIZES = (256, 128, 64, 48, 32, 16)
INK = (52, 48, 54)
HAND_FONT = 'C:/Windows/Fonts/Inkfree.ttf'  # 手写马克笔风格；导出的是 PNG，运行时不依赖这个字体

# 每张图两个裁切框（原图像素，左、上、边长，可以超出原图，超出部分补白）：
#   main 用于 48px 及以上，裁得比头部宽，留出写 RGL 的空白；tight 只框脸，用于 32px、16px
# label：RGL 手写字在 1024 画布上的中心 x、y，字高，旋转角度（逆时针为正），颜色取自原画的点缀色
ITEMS = {
    'p1': dict(src='ref-cyan.png', name='青色 · 打哈欠，RGL 代替“zzz”', main=(430, -83, 780), tight=(580, 160, 380),
               label=(330, 150, 165, 9), color=(118, 212, 222)),
    'p2': dict(src='ref-star.png', name='黄色 · 魔法帽，RGL 写在帽子上', main=(300, -40, 785), tight=(510, 330, 400),
               label=(655, 318, 116, -7), color=(247, 208, 84)),
    'p3': dict(src='ref-heart.png', name='粉色 · 爱心，RGL 写在头顶', main=(232, -14, 806), tight=(330, 330, 480),
               label=(560, 152, 158, 3), color=(244, 134, 160)),
}
# 原画截图里的鼠标指针（只在 ref-star 里），用周围像素填掉
CURSOR = {'ref-star.png': (496, 326, 548, 384)}


def remove_cursor(im, box):
    """把框内的浅灰色像素（指针）标记出来，反复用相邻的非指针像素填充"""
    px = im.load()
    x0, y0, x1, y1 = box

    def is_cursor(c):
        r, g, b = c[:3]
        return max(r, g, b) - min(r, g, b) < 18 and 135 < (r + g + b) / 3 < 236

    hole = {(x, y) for x in range(x0, x1) for y in range(y0, y1) if is_cursor(px[x, y])}
    while hole:
        filled = {}
        for x, y in hole:
            near = [px[x + dx, y + dy] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
                    if (x + dx, y + dy) not in hole]
            if near:
                filled[(x, y)] = tuple(sum(c[i] for c in near) // len(near) for i in range(3))
        if not filled:
            break
        for (x, y), c in filled.items():
            px[x, y] = c
        hole -= filled.keys()
    return im


def levels(im, lo=34, hi=238):
    """背景提白、线条加深：低于 lo 的压成黑，高于 hi 的提成白，中间线性拉伸"""
    lut = [max(0, min(255, round((v - lo) * 255 / (hi - lo)))) for v in range(256)]
    return im.point(lut * 3)


def square(im, box, size):
    """裁出正方形；裁切框超出原图的部分补白"""
    x, y, s = box
    canvas = Image.new('RGB', (s, s), (255, 255, 255))
    canvas.paste(im, (-x, -y))
    return canvas.resize((size, size), Image.LANCZOS)


def hand_label(art, cx, cy, h, rot, color, text='RGL'):
    """在画上写手绘风 RGL：深色描边居中，彩色填充稍微错位（和原画爱心、星星的上色方式一样），全部平涂，不加阴影"""
    size = round(h / 0.72)
    font = ImageFont.truetype(HAND_FONT, size)
    pad = size // 2
    left, top, right, bottom = font.getbbox(text)
    w, hh = right - left + pad * 2, bottom - top + pad * 2
    glyph = Image.new('L', (w, hh), 0)
    ImageDraw.Draw(glyph).text((pad - left, pad - top), text, font=font, fill=255)
    odd = lambda v: v + 1 - v % 2
    core = glyph.filter(ImageFilter.MaxFilter(odd(round(size * 0.07))))       # 把细字体加粗成马克笔笔画
    outer = core.filter(ImageFilter.MaxFilter(odd(round(size * 0.075))))
    ring = ImageChops.subtract(outer, core)
    dx, dy = round(size * 0.035), round(size * 0.03)
    fill = Image.new('L', (w, hh), 0)
    fill.paste(core.filter(ImageFilter.MaxFilter(3)), (-dx, -dy))
    fill = fill.filter(ImageFilter.GaussianBlur(size * 0.008))
    ring = ring.filter(ImageFilter.GaussianBlur(size * 0.005))
    # 以字形实际轮廓的中心定位（字体上下留白不对称，按画布中心会偏）
    box = outer.getbbox()
    ox, oy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    # 只放在原画的空白处，所以不铺白底，原画线条一笔都不盖；先上彩色，再描边
    for mask, rgb in ((fill, color), (ring, INK)):
        m = Image.new('L', (w * 2, hh * 2), 0)
        m.paste(mask, (round(w - ox), round(hh - oy)))
        m = m.rotate(rot, Image.BICUBIC)
        art.paste(Image.new('RGB', m.size, rgb), (round(cx - w), round(cy - hh)), m)
    return art


def tile(art, size):
    """白底圆角方块，加一圈很淡的描边，深色任务栏和浅色桌面上都能看清边界"""
    scale = 4
    big = size * scale
    mask = Image.new('L', (big, big), 0)
    radius = round(big * 0.22)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(art.convert('RGBA'), (0, 0), mask)
    if size >= 32:
        ring = Image.new('L', (big, big), 0)
        w = max(scale, round(big / 128))
        ImageDraw.Draw(ring).rounded_rectangle((0, 0, big - 1, big - 1), radius, outline=255, width=w)
        ring = ImageChops.multiply(ring.resize((size, size), Image.LANCZOS), mask)
        edge = Image.new('RGBA', (size, size), (40, 40, 52, 255))
        edge.putalpha(ring.point(lambda v: v * 40 // 255))
        out = Image.alpha_composite(out, edge)
    return out


# 缩小前先加粗原画线条，否则细线在小图标里会消失；尺寸越小加得越多（128px 及以上不加）
GROW = {16: 17, 20: 15, 24: 13, 32: 11, 40: 9, 48: 7, 64: 5}


def load(item):
    src = Image.open(HERE / 'source' / item['src']).convert('RGB')
    if item['src'] in CURSOR:
        src = remove_cursor(src, CURSOR[item['src']])
    return levels(src)


def make(src, item, s, labeled, grow=None):
    """labeled=True 用完整裁切并写 RGL；False 只框脸（小尺寸放不下字）"""
    work = square(src, item['main'] if labeled else item['tight'], 1024)
    g = GROW.get(s) if grow is None else grow
    if g:
        work = work.filter(ImageFilter.MinFilter(g))
    if labeled:  # 字最后再写，不参与加粗，否则笔画会糊成一团；小尺寸字稍微放大
        cx, cy, h, rot = item['label']
        work = hand_label(work, cx, cy, h * {64: 1.08, 48: 1.14}.get(s, 1), rot, item['color'])
    return tile(work.resize((s, s), Image.LANCZOS), s)


def render(item):
    src = load(item)
    files = {}
    for s in SIZES:
        f = OUT / f'{item["key"]}-{s}.png'
        make(src, item, s, s > 32).save(f)
        files[s] = f.name
    return files


def export_final(key):
    """把选定的版本导出到程序里：
    src/main/resources/brand/ 给 exe 图标、托盘图标、托盘面板用（BrandIcon 按尺寸取）；
    frontend/public/ 给网页标签页图标和侧栏标志用"""
    root = HERE.parent.parent
    item = ITEMS[key]
    src = load(item)
    res = root / 'src/main/resources/brand'
    res.mkdir(parents=True, exist_ok=True)
    for s in (16, 20, 24, 32, 40):
        make(src, item, s, False).save(res / f'face-{s}.png')
    for s in (48, 64, 128, 256):
        make(src, item, s, True).save(res / f'full-{s}.png')
    pub = root / 'frontend/public'
    make(src, item, 64, False, grow=13).save(pub / 'favicon.png')   # 标签页里显示 16–32px
    make(src, item, 72, False, grow=11).save(pub / 'logo.png')      # 侧栏里显示 36px，按 2 倍屏准备
    print('已导出', key, '→', res, '和', pub)


PAGE = '''<!doctype html><meta charset="utf-8"><title>RGL 图标 · 原画裁切版</title>
<style>
body{margin:0;padding:28px 32px;font:14px "Microsoft YaHei",sans-serif;background:#eceef2;color:#222}
h1{font-size:20px;margin:0 0 4px} .tip{color:#666;margin:0 0 20px}
.card{background:#fff;border-radius:14px;padding:18px 20px;margin-bottom:18px;box-shadow:0 2px 10px rgba(0,0,0,.06)}
.card h2{font-size:15px;margin:0 0 12px} .row{display:flex;gap:16px;align-items:center}
.col{flex:1;display:flex;flex-direction:column;gap:10px}
.pane{border-radius:10px;padding:14px 16px;display:flex;align-items:flex-end;gap:22px}
.light{background:#f7f7f9} .dark{background:#1f2430;color:#c9cfdb}
.cell{text-align:center;font-size:11px;color:#8a8f99} .cell img{display:block;margin:0 auto 6px}
.px{image-rendering:pixelated}
.bar{display:flex;align-items:center;gap:8px;border-radius:6px;padding:6px 10px;font-size:12px;background:#fff}
.dark .bar{background:#2b3140}
</style>
<h1>RGL 图标 · 原画 + 手写 RGL</h1>
<p class="tip">在原画的空白处加上手写 RGL：深色描边、彩色平涂、填充稍微错位，和原画爱心、星星的画法一样，不加阴影和渐变，原画线条一笔不改。48 px 及以上带字；32 px、16 px 放不下字，只框脸。最右边是 16 px 放大 4 倍后的像素效果。</p>
'''


def pane(files, cls):
    cells = ''.join(f'<div class="cell"><img src="crop/{files[s]}" width="{s}" height="{s}">{s}px</div>'
                    for s in (128, 64, 48, 32, 16))
    cells += (f'<div class="cell"><img class="px" src="crop/{files[16]}" width="64" height="64">16px ×4</div>'
              f'<div class="cell"><img src="crop/{files[48]}" width="48" height="48">RGL</div>'
              f'<div class="bar"><img src="crop/{files[16]}" width="16" height="16">RGL-OA智能归档助手</div>')
    return f'<div class="pane {cls}">{cells}</div>'


def main():
    OUT.mkdir(exist_ok=True)
    page = PAGE
    for key, item in ITEMS.items():
        item['key'] = key
        files = render(item)
        page += (f'<div class="card"><h2>{key.upper()}　{item["name"]}</h2><div class="row">'
                 f'<div class="cell"><img src="crop/{files[256]}" width="220" height="220"></div>'
                 f'<div class="col">{pane(files, "light")}{pane(files, "dark")}</div></div></div>\n')
    (HERE / 'preview-crop.html').write_text(page, encoding='utf-8')
    print('ok', sorted(p.name for p in OUT.iterdir()))


if __name__ == '__main__':
    import sys
    if len(sys.argv) == 3 and sys.argv[1] == '--final':
        export_final(sys.argv[2])
    else:
        main()
