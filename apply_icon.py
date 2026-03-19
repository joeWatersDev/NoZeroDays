"""
apply_icon.py
Generates all Android launcher icon assets from a source PNG.

Usage: py apply_icon.py
Source: art/iconv3.png  (relative to repo root)
"""

from PIL import Image
import os

SRC = "art/iconv3.png"
RES = "app/src/main/res"

# (folder, size) for ic_launcher.png and ic_launcher_round.png
MIPMAP_SIZES = [
    ("mipmap-mdpi",    48),
    ("mipmap-hdpi",    72),
    ("mipmap-xhdpi",   96),
    ("mipmap-xxhdpi",  144),
    ("mipmap-xxxhdpi", 192),
]

# ic_launcher_foreground.png in drawable
FOREGROUND_SIZE = 432

src = Image.open(SRC).convert("RGBA")
print(f"Loaded source: {src.size}")

for folder, size in MIPMAP_SIZES:
    resized = src.resize((size, size), Image.LANCZOS)
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        out_path = os.path.join(RES, folder, name)
        resized.save(out_path, "PNG")
        print(f"Written: {out_path}")

fg = src.resize((FOREGROUND_SIZE, FOREGROUND_SIZE), Image.LANCZOS)
fg_path = os.path.join(RES, "drawable", "ic_launcher_foreground.png")
fg.save(fg_path, "PNG")
print(f"Written: {fg_path}")

print("Done.")
