#!/usr/bin/env python3
"""Recompresses the reference pictures losslessly, since they live in the repository. Needs Pillow.

Most of them are flat shapes in a few colours, which a palette holds in a quarter of the space, without changing a single pixel.
"""
import sys
from pathlib import Path

from PIL import Image

PALETTE_SIZE = 256


def as_palette(image: Image.Image) -> Image.Image | None:
    colors = image.getcolors(PALETTE_SIZE)
    if colors is None:
        return None
    table = {color: index for index, (_, color) in enumerate(colors)}
    out = Image.new("P", image.size)
    out.putpalette([channel for _, color in colors for channel in color[:3]])
    data = image.tobytes()
    out.putdata([table[tuple(data[i : i + 4])] for i in range(0, len(data), 4)])
    out.info["transparency"] = bytes(color[3] for _, color in colors)
    return out


def main() -> None:
    folder = Path(sys.argv[1] if len(sys.argv) > 1 else "core/media/src/test/resources/svg/reference")
    before = after = 0
    for path in sorted(folder.glob("*.png")):
        before += path.stat().st_size
        with Image.open(path) as source:
            pixels = source.convert("RGBA")
        palette = as_palette(pixels)
        if palette is not None:
            palette.save(path, "PNG", optimize=True, transparency=palette.info["transparency"])
        else:
            pixels.save(path, "PNG", optimize=True)
        with Image.open(path) as check:
            if check.convert("RGBA").tobytes() != pixels.tobytes():
                raise SystemExit(f"{path} changed while being recompressed")
        after += path.stat().st_size
    print(f"{before / 1024:.0f} KiB -> {after / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
