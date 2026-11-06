#!/usr/bin/env python3
"""常亮图标预览：黑底红色转速表，与 res/drawable/ic_launcher_foreground.xml 同构。
minSdk 26 只用自适应矢量图标，这里只产出预览图 assets-preview/icon-512.png。"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
S = 1024
k = S / 108.0  # 108dp 视口 → 像素


def P(x, y):
    return (x * k, y * k)


def arc(d, cx, cy, r, a0, a1, color, width):
    R = r + width / 2  # Pillow 的线宽向内画，补回半个线宽让中心线落在 r
    box = [(cx - R) * k, (cy - R) * k, (cx + R) * k, (cy + R) * k]
    d.arc(box, a0, a1, fill=color, width=int(width * k))
    for a in (a0, a1):  # 圆头
        x = cx + r * math.cos(math.radians(a))
        y = cy + r * math.sin(math.radians(a))
        rr = width / 2
        d.ellipse([(x - rr) * k, (y - rr) * k, (x + rr) * k, (y + rr) * k], fill=color)


img = Image.new("RGBA", (S, S), (8, 9, 11, 255))
glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
arc(gd, 54, 54, 24, 135, 345, (225, 6, 0, 150), 10)
glow = glow.filter(ImageFilter.GaussianBlur(28))
img = Image.alpha_composite(img, glow)

d = ImageDraw.Draw(img)
arc(d, 54, 54, 24, 135, 405, (34, 38, 46, 255), 5)
arc(d, 54, 54, 24, 135, 345, (225, 6, 0, 255), 5)
arc(d, 54, 54, 24, 345, 405, (255, 59, 48, 255), 5)
d.line([P(58.6, 52.8), P(73.3, 48.8)], fill=(255, 59, 48, 255), width=int(3.6 * k))
d.ellipse([P(50.5, 50.5), P(57.5, 57.5)], fill=(244, 241, 234, 255))

mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=230, fill=255)
out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
out.paste(img, (0, 0), mask)
(ROOT / "assets-preview").mkdir(exist_ok=True)
out.resize((512, 512), Image.LANCZOS).save(ROOT / "assets-preview" / "icon-512.png")
print("assets-preview/icon-512.png")
