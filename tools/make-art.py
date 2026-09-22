#!/usr/bin/env python3
"""
Generates the app's launcher art in Crimson's own palette, so the icon and the TV banner match
what the app looks like: near-black, a red bloom, and the red wordmark.

Produces:
  app/src/main/res/drawable-xhdpi/banner.png   320x180, required for a Leanback launcher entry
  app/src/main/res/mipmap-*/ic_launcher.png    square launcher icons

Run: python tools/make-art.py
Requires Pillow. Uses a heavy condensed system font when one is available (Impact on Windows,
Bebas/Anton/DejaVu Condensed Bold elsewhere) and falls back to Pillow's default otherwise.
"""

import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# Kept in step with ui/theme/Crimson.kt.
BACKGROUND = (10, 10, 12)
RED = (229, 9, 20)
RED_DEEP = (122, 7, 16)

FONT_CANDIDATES = [
    "C:/Windows/Fonts/impact.ttf",
    "/usr/share/fonts/truetype/bebas/BebasNeue-Regular.ttf",
    "/usr/share/fonts/truetype/anton/Anton-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def background(size, glow_center, glow_radius):
    """Near-black with a soft red bloom, drawn at 2x and scaled down for smooth falloff."""
    w, h = size
    scale = 2
    img = Image.new("RGB", (w * scale, h * scale), BACKGROUND)
    px = img.load()
    cx, cy = glow_center[0] * scale, glow_center[1] * scale
    r = glow_radius * scale
    for y in range(h * scale):
        for x in range(w * scale):
            d = math.hypot(x - cx, y - cy) / r
            t = max(0.0, 1.0 - d) ** 2 * 0.55
            px[x, y] = tuple(int(BACKGROUND[i] + (RED_DEEP[i] - BACKGROUND[i]) * t) for i in range(3))
    return img.resize((w, h), Image.LANCZOS)


def glowing_text(img, xy, text, font):
    """Red text with a blurred red glow behind it, like the in-app wordmark."""
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).text(xy, text, font=font, fill=RED + (200,))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=max(2, font.size // 10)))
    img.paste(glow, (0, 0), glow)
    ImageDraw.Draw(img).text(xy, text, font=font, fill=RED)


def make_banner(path):
    w, h = 320, 180
    img = background((w, h), (60, 20), 260).convert("RGBA")
    draw = ImageDraw.Draw(img)
    margin = 24
    size = 90
    while size > 12:
        font = load_font(size)
        box = draw.textbbox((0, 0), "CRIMSON", font=font)
        if box[2] - box[0] <= w - 2 * margin:
            break
        size -= 2
    font = load_font(size)
    box = draw.textbbox((0, 0), "CRIMSON", font=font)
    x = (w - (box[2] - box[0])) // 2 - box[0]
    y = (h - (box[3] - box[1])) // 2 - box[1] - 8
    glowing_text(img, (x, y), "CRIMSON", font)
    sub = load_font(14)
    tag = "MOVIES  \u00b7  SHOWS  \u00b7  LIVE TV"
    tb = draw.textbbox((0, 0), tag, font=sub)
    ImageDraw.Draw(img).text(((w - (tb[2] - tb[0])) // 2, y + (box[3] - box[1]) + 22), tag, font=sub, fill=(188, 188, 196))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert("RGB").save(path, "PNG")
    print("wrote", os.path.relpath(path, os.path.join(HERE, "..")))


def make_icon(path, size):
    img = background((size, size), (size * 0.3, size * 0.25), size * 1.1).convert("RGBA")
    font = load_font(int(size * 0.82))
    draw = ImageDraw.Draw(img)
    box = draw.textbbox((0, 0), "C", font=font)
    x = (size - (box[2] - box[0])) // 2 - box[0]
    y = (size - (box[3] - box[1])) // 2 - box[1]
    glowing_text(img, (x, y), "C", font)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.convert("RGB").save(path, "PNG")
    print("wrote", os.path.relpath(path, os.path.join(HERE, "..")))


def main():
    make_banner(os.path.join(RES, "drawable-xhdpi", "banner.png"))
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)):
        make_icon(os.path.join(RES, "mipmap-" + density, "ic_launcher.png"), size)


if __name__ == "__main__":
    main()
