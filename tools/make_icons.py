# -*- coding: utf-8 -*-
"""由 iOS 同款 icon.svg 生成 Android 全套图标：

  mipmap-*/ic_launcher.png            旧式图标（圆角方形）
  mipmap-*/ic_launcher_round.png      旧式圆形图标
  mipmap-anydpi-v26/ic_launcher.xml   自适应图标（背景层 + 前景层）
  drawable-nodpi/ic_launcher_fg.png   自适应前景：只有盾牌与车身（透明底）
  drawable-nodpi/ic_launcher_bg.png   自适应背景：只有深蓝天光
  drawable-nodpi/splash_icon.png      启动页居中的圆角图标

用法： python tools/make_icons.py
"""
import io
import os
import subprocess

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
RES = os.path.join(ROOT, "app", "src", "main", "res")
SVG = os.path.join(ROOT, "tools", "icon.svg")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(svg_text, out_png, size=1024, transparent=True):
    html = ("<!DOCTYPE html><html><head><meta charset='utf-8'>"
            "<style>html,body{margin:0;padding:0;width:%dpx;height:%dpx;overflow:hidden;"
            "background:%s}svg{display:block}</style></head><body>%s</body></html>"
            % (size, size, "transparent" if transparent else "#000", svg_text))
    html_path = os.path.join(ROOT, "tools", "_render.html")
    io.open(html_path, "w", encoding="utf-8").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    "--default-background-color=00000000",
                    "--force-device-scale-factor=1",
                    "--window-size=%d,%d" % (size, size),
                    "--screenshot=" + out_png,
                    "file:///" + html_path.replace("\\", "/")],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not os.path.exists(out_png):
        raise SystemExit("render failed: " + out_png)


def split_layers(svg_text):
    """把 SVG 拆成「背景层」与「前景层」（前者只有渐变底，后者只要盾牌）"""
    marker = "<!-- 背景 -->"
    i = svg_text.index(marker)
    j = svg_text.index("<!-- 盾徽投影 -->")
    head = svg_text[:i]
    bg_block = svg_text[i:j]
    body = svg_text[j:]
    bg_svg = head + bg_block + "\n</svg>"
    fg_svg = head + "<!-- 前景：盾牌 + 车身（透明底） -->\n" + body
    return bg_svg, fg_svg


def rounded(img, radius_ratio):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1],
                        radius=int(size * 4 * radius_ratio), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.copy()
    out.putalpha(mask)
    return out


def circle(img):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.copy()
    out.putalpha(mask)
    return out


def save(img, path):
    d = os.path.dirname(path)
    if not os.path.isdir(d):
        os.makedirs(d)
    img.save(path, "PNG", optimize=True)


def main():
    svg_text = io.open(SVG, encoding="utf-8").read()
    bg_svg, fg_svg = split_layers(svg_text)

    full_png = os.path.join(ROOT, "tools", "_full.png")
    bg_png = os.path.join(ROOT, "tools", "_bg.png")
    fg_png = os.path.join(ROOT, "tools", "_fg.png")
    render(svg_text, full_png)
    render(bg_svg, bg_png)
    render(fg_svg, fg_png)

    full = Image.open(full_png).convert("RGBA")
    bg = Image.open(bg_png).convert("RGBA")
    fg = Image.open(fg_png).convert("RGBA")

    # 自适应图标两层（108dp 画布；432px = 108dp @xxxhdpi）
    save(bg.resize((432, 432), Image.LANCZOS), os.path.join(RES, "drawable-nodpi", "ic_launcher_bg.png"))
    save(fg.resize((432, 432), Image.LANCZOS), os.path.join(RES, "drawable-nodpi", "ic_launcher_fg.png"))

    # 旧式图标：圆角方形 + 圆形
    sq = rounded(full, 0.225)
    rd = circle(full)
    for dpi, px in DENSITIES.items():
        base = os.path.join(RES, "mipmap-" + dpi)
        save(sq.resize((px, px), Image.LANCZOS), os.path.join(base, "ic_launcher.png"))
        save(rd.resize((px, px), Image.LANCZOS), os.path.join(base, "ic_launcher_round.png"))

    # 启动页图标
    save(sq.resize((384, 384), Image.LANCZOS), os.path.join(RES, "drawable-nodpi", "splash_icon.png"))

    # 自适应图标描述
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    if not os.path.isdir(anydpi):
        os.makedirs(anydpi)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_bg" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
           '</adaptive-icon>\n')
    io.open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8").write(xml)
    io.open(os.path.join(anydpi, "ic_launcher_round.xml"), "w", encoding="utf-8").write(xml)
    print("icons ok")


if __name__ == "__main__":
    main()
