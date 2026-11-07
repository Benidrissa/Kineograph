#!/usr/bin/env python3
"""Recompresses the reference pictures losslessly, since they live in the repository. Needs Pillow."""
import sys
from pathlib import Path

from PIL import Image

folder = Path(sys.argv[1] if len(sys.argv) > 1 else "core/media/src/test/resources/svg/reference")
before = after = 0
for path in sorted(folder.glob("*.png")):
    before += path.stat().st_size
    with Image.open(path) as image:
        image.load()
        pixels = image.convert("RGBA")
    # Colours under fully transparent pixels are invisible and compress badly.
    data = pixels.tobytes()
    if pixels.getextrema()[3][0] == 255:
        pixels = pixels.convert("RGB")
    pixels.save(path, "PNG", optimize=True)
    after += path.stat().st_size
print(f"{before / 1024:.0f} KiB -> {after / 1024:.0f} KiB")
