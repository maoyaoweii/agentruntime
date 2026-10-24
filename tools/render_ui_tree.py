# -*- coding: utf-8 -*-
"""把 Robolectric 导出的视图树重绘成 PNG。

⚠️ 实测结论：Robolectric LEGACY 模式下的 `Paint.measureText` 是桩实现
（42px 的 "agent runtime" 只量出 13px 宽），导致 wrap_content 文字控件宽度失真。
用它渲染出来的图会把状态条压成 "a.. C.. O.."，**会误导人**。
保留脚本是为了将来在支持 NATIVE 图形的环境里直接可用；当前请用
docs/shots/v16/mockup_*.html 那种按布局文件 1:1 还原的预览。

**这不是截图。** 数据来源是 Android 自己的 measure/layout 结果
（每个控件的位置、尺寸、背景色、文字色、字号、文案），
文字由宿主字体渲染，圆角/图标/阴影是近似。

能用来判断：排版有没有崩、层级对不对、配色是否符合预期。
不能用来判断：真机字体渲染、GPU 合成、状态栏与刘海避让。

用法：
    python tools/render_ui_tree.py docs/shots/v16/tree_pixel5_portrait.json
"""
import io
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

MONO = r'C:\Windows\Fonts\consola.ttf'
CJK = r'C:\Windows\Fonts\msyh.ttc'

FOOTER_H = 56
FOOTER_TEXT = 'RENDERED FROM REAL LAYOUT MEASUREMENTS  ·  NOT A DEVICE SCREENSHOT'


def argb(hexstr):
    """#AARRGGBB -> (r,g,b,a)"""
    h = hexstr.lstrip('#')
    a = int(h[0:2], 16)
    r = int(h[2:4], 16)
    g = int(h[4:6], 16)
    b = int(h[6:8], 16)
    return (r, g, b, a)


def needs_cjk(s):
    return any(ord(ch) > 0x2000 for ch in s)


def font_for(text, size, bold=False):
    path = CJK if needs_cjk(text) else MONO
    if bold and os.path.exists(r'C:\Windows\Fonts\msyhbd.ttc'):
        path = r'C:\Windows\Fonts\msyhbd.ttc' if needs_cjk(text) else \
            r'C:\Windows\Fonts\consolab.ttf'
    try:
        return ImageFont.truetype(path, max(8, int(size)))
    except Exception:
        return ImageFont.load_default()


def text_width(font, s):
    try:
        return font.getbbox(s)[2] - font.getbbox(s)[0]
    except Exception:
        return len(s) * font.size // 2


def fit(font, s, max_w):
    """超宽就截断加省略号。真机上 TextView 也是这样处理的。"""
    if max_w <= 0:
        return ''
    if text_width(font, s) <= max_w:
        return s
    ell = '…'
    out = s
    while out and text_width(font, out + ell) > max_w:
        out = out[:-1]
    return out + ell if out else ''


def draw_text(draw, text, x, y, w, h, size, color, gravity, pad_l, pad_t):
    if not text:
        return
    fonts = font_for(text, size)
    ascent, descent = fonts.getmetrics()
    line_h = int((ascent + descent) * 1.18)
    max_w = max(0, w - pad_l - 4)

    lines = []
    for raw in text.split('\n'):
        lines.append(fit(fonts, raw, max_w))

    # gravity: CENTER_HORIZONTAL=1, RIGHT=5, END=0x800005, CENTER_VERTICAL=16
    align_right = (gravity & 0x800005) == 0x800005 or (gravity & 0x7) == 5
    align_center = bool(gravity & 1) and not align_right

    total_h = line_h * len(lines)
    ty = y + pad_t
    if h > total_h + pad_t:          # 垂直居中
        ty = y + (h - total_h) // 2
    for ln in lines:
        if not ln:
            ty += line_h
            continue
        tw = text_width(fonts, ln)
        if align_right:
            tx = x + w - tw - pad_l - 2
        elif align_center:
            tx = x + (w - tw) // 2
        else:
            tx = x + pad_l
        try:
            draw.text((tx, ty), ln, font=fonts, fill=color)
        except Exception:
            pass
        ty += line_h


def render(path, out_path=None, scale=1.0):
    d = json.load(io.open(path, encoding='utf-8'))
    w, h = int(d['width'] * scale), int(d['height'] * scale)

    img = Image.new('RGB', (w, h + FOOTER_H), argb(d['bg'])[:3])
    draw = ImageDraw.Draw(img)

    for n in d['nodes']:
        x = int(n['x'] * scale)
        y = int(n['y'] * scale)
        nw = int(n['w'] * scale)
        nh = int(n['h'] * scale)
        if nw <= 0 or nh <= 0:
            continue

        if 'bg' in n:
            col = argb(n['bg'])
            if col[3] > 0:
                radius = 0
                if nh > 12 and nw > 40:
                    radius = int(12 * scale)
                try:
                    draw.rounded_rectangle([x, y, x + nw, y + nh], radius=radius,
                                           fill=col[:3])
                except Exception:
                    draw.rectangle([x, y, x + nw, y + nh], fill=col[:3])
                # 用户发言/工具块：左侧强调竖线
                if col[:3] == (20, 23, 28):
                    draw.rectangle([x, y, x + max(1, int(2 * scale)), y + nh],
                                   fill=argb('#FF7AA2F7')[:3])

        if n.get('text'):
            draw_text(draw, n['text'],
                      x, y, nw, nh,
                      float(n.get('size', 14)) * scale,
                      argb(n.get('fg', '#FFE6E9EF'))[:3],
                      int(n.get('gravity', 0)),
                      int(n.get('padL', 0) * scale),
                      int(n.get('padT', 0) * scale))

    # 页脚：明确标注这是重绘图，不是截图
    fy = h + 14
    f = font_for(FOOTER_TEXT, 15)
    draw.text((16, fy), FOOTER_TEXT, font=f, fill=(120, 130, 145))

    out_path = out_path or path.replace('tree_', 'ui_').replace('.json', '.png')
    img.save(out_path)
    print('rendered', out_path, img.size)
    return out_path


def main():
    args = sys.argv[1:] or [r'docs/shots/v16/tree_pixel5_portrait.json']
    for p in args:
        render(p)


if __name__ == '__main__':
    main()
