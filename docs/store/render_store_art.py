# Usage: python docs/store/render_store_art.py docs/store   (needs: pip install pillow; uses DejaVu fonts,
# see STORE_ART_FONTS below)
"""Render Play Store art from the app's adaptive-icon vector (res/drawable/ic_launcher_foreground.xml)."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

out = sys.argv[1]
INK, PAPER, BRASS = (0x1F, 0x3D, 0x2E), (0xFB, 0xF7, 0xF0), (0xE8, 0xB0, 0x4B)
# Shapes in the vector's 108x108 viewport.
SHAPES = [
    (PAPER, [(36, 34), (46, 34), (46, 72), (36, 72)]),
    (BRASS, [(48, 40), (57, 40), (57, 72), (48, 72)]),
    (PAPER, [(59.5, 37.2), (68.2, 34.9), (77.8, 70.7), (69.1, 73.0)]),
    (BRASS, [(32, 72), (76, 72), (76, 75), (32, 75)]),
]


def draw_mark(draw, scale, ox, oy):
    for color, pts in SHAPES:
        draw.polygon([(ox + x * scale, oy + y * scale) for x, y in pts], fill=color)


SS = 4  # supersample, then downscale for smooth edges

# 512x512 icon: full-bleed square (Play rounds the corners itself). The mark is scaled up from the
# adaptive icon's safe zone so it reads well at small sizes.
size = 512 * SS
icon = Image.new("RGB", (size, size), INK)
scale = size / 72                     # show the middle 72 of the 108 viewport
draw_mark(ImageDraw.Draw(icon), scale, -18 * scale, -18 * scale)
icon.resize((512, 512), Image.LANCZOS).save(f"{out}/icon-512.png")

# 1024x500 feature graphic: mark on the left, name and line on the right, with safe margins
# (Play may crop the edges on some screens).
W, H = 1024 * SS, 500 * SS
fg = Image.new("RGB", (W, H), INK)
d = ImageDraw.Draw(fg)
s = 5.0 * SS
mark_left = 96 * SS
draw_mark(d, s, mark_left - 32 * s, H / 2 - 54 * s)   # mark spans 32..78 horizontally, 34..75 vertically
x = mark_left + 46 * s + 64 * SS
right_edge = W - 80 * SS


def fit(path, text, size):
    while True:
        font = ImageFont.truetype(path, size * SS)
        if d.textlength(text, font=font) <= right_edge - x or size <= 12:
            return font
        size -= 1


# DejaVu, as installed on Linux. Elsewhere, point STORE_ART_FONTS at a folder holding the same two files
# (matplotlib's wheel ships them under matplotlib/mpl-data/fonts/ttf).
FONTS = os.environ.get("STORE_ART_FONTS", "/usr/share/fonts/truetype/dejavu")
SERIF = os.path.join(FONTS, "DejaVuSerif-Bold.ttf")
SANS = os.path.join(FONTS, "DejaVuSans.ttf")
lines = ["Photograph your shelves.", "Get a catalog and what to read next."]
title = fit(SERIF, "Lecter", 88)
body = min((fit(SANS, t, 30) for t in lines), key=lambda f: f.size)
d.text((x, 150 * SS), "Lecter", font=title, fill=PAPER)
d.text((x, 272 * SS), lines[0], font=body, fill=BRASS)
d.text((x, 272 * SS + body.size * 1.35), lines[1], font=body, fill=PAPER)
fg.resize((1024, 500), Image.LANCZOS).save(f"{out}/feature-graphic-1024x500.png")
print("ok")
