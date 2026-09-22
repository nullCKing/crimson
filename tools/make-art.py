#!/usr/bin/env python3
"""
Generates the app's launcher art from the guide's own palette, so the icon and the TV banner
match what the guide actually looks like.

Produces:
  app/src/main/res/drawable-xhdpi/banner.png   320x180, required for a Leanback launcher entry
  app/src/main/res/mipmap-*/ic_launcher.png    square launcher icons

Run: python3 tools/make-art.py
Requires Pillow. Regenerate after changing the theme colours in GuideTheme.kt.
"""

import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")

# Kept in step with GuideTheme.kt.
NAVY_DEEP = (8, 20, 56)
NAVY = (14, 33, 84)
CELL_BLUE = (30, 72, 160)
CELL_MAGENTA = (150, 32, 146)
HIGHLIGHT = (247, 200, 24)
WHITE = (255, 255, 255)
OUTLINE = (4, 10, 30)

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def outlined_text(draw, xy, text, font, fill, outline=OUTLINE, width=2):
    """Bold text with a dark outline, the way the reference guides draw cell titles."""
    x, y = xy
    for dx in range(-width, width + 1):
        for dy in range(-width, width + 1):
            if dx or dy:
                draw.text((x + dx, y + dy), text, font=font, fill=outline)
    draw.text((x, y), text, font=font, fill=fill)


def vertical_gradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return img.resize((w, h))


def make_banner(path):
    w, h = 320, 180
    img = vertical_gradient((w, h), NAVY, NAVY_DEEP)
    draw = ImageDraw.Draw(img)

    # A strip of programme cells along the bottom, one of them highlighted, as a miniature of
    # the guide grid itself.
    cell_h = 30
    top = h - cell_h - 16
    cells = [(10, 78, CELL_BLUE), (92, 66, CELL_MAGENTA), (162, 58, HIGHLIGHT), (224, 86, CELL_BLUE)]
    for x, cw, colour in cells:
        draw.rounded_rectangle([x, top, x + cw, top + cell_h], radius=5, fill=colour)

    # Time-header tabs above the cells.
    tab_y = top - 16
    for i in range(4):
        x = 10 + i * 76
        draw.rounded_rectangle([x, tab_y, x + 68, tab_y + 12], radius=4, fill=(176, 208, 240))

    sub = load_font(15)

    # Size the wordmark to the banner rather than hard-coding positions, so the two words never
    # collide if the font or the text changes.
    margin = 16
    available = w - 2 * margin
    size = 44
    while size > 12:
        title = load_font(size)
        first = draw.textlength("RETRO", font=title)
        second = draw.textlength("GUIDE", font=title)
        if first + second <= available:
            break
        size -= 2
    title = load_font(size)
    first = draw.textlength("RETRO", font=title)

    outlined_text(draw, (margin, 24), "RETRO", title, WHITE)
    outlined_text(draw, (margin + first, 24), "GUIDE", title, HIGHLIGHT)
    outlined_text(draw, (margin + 2, 78), "PROGRAM GUIDE", sub, (176, 208, 240), width=1)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", os.path.relpath(path, os.path.join(HERE, "..")))


def make_icon(path, size):
    img = vertical_gradient((size, size), NAVY, NAVY_DEEP)
    draw = ImageDraw.Draw(img)

    unit = size / 12.0
    # Three stacked guide rows, the middle one highlighted.
    rows = [
        (2.0, [(3.0, 3.4, CELL_BLUE), (6.6, 2.6, CELL_MAGENTA)]),
        (5.0, [(3.0, 2.4, HIGHLIGHT), (5.6, 3.6, CELL_BLUE)]),
        (8.0, [(3.0, 2.8, CELL_MAGENTA), (6.0, 3.2, CELL_BLUE)]),
    ]
    for row_y, cells in rows:
        # Channel-number column.
        draw.rounded_rectangle(
            [unit * 1.0, unit * row_y, unit * 2.7, unit * (row_y + 2.0)],
            radius=unit * 0.25, fill=(176, 208, 240),
        )
        for cx, cw, colour in cells:
            draw.rounded_rectangle(
                [unit * cx, unit * row_y, unit * (cx + cw), unit * (row_y + 2.0)],
                radius=unit * 0.25, fill=colour,
            )

    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, "PNG")
    print("wrote", os.path.relpath(path, os.path.join(HERE, "..")))


def main():
    make_banner(os.path.join(RES, "drawable-xhdpi", "banner.png"))
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                          ("xxhdpi", 144), ("xxxhdpi", 192)):
        make_icon(os.path.join(RES, "mipmap-" + density, "ic_launcher.png"), size)


if __name__ == "__main__":
    main()
