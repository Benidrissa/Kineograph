#!/usr/bin/env python3
"""Prepares the fonts Chromium uses for the reference pictures, so that text can be compared letter by letter.

Under Robolectric, Android's generic names mean the fonts in its nativeruntime-dist-compat jar. Those files have odd internal family
names, such as "Roboto21382017", and the weights of one family do not even share a name. Copies are renamed here so that fontconfig
sees one family for each generic name, with all of its weights. Needs fontTools.

    prepare_fonts.py <jar or folder of fonts> <output folder>
"""
import io
import sys
import zipfile
from pathlib import Path

from fontTools.ttLib import TTFont

FAMILIES = {
    "KG Sans": [f"Roboto-{style}.ttf" for style in (
        "Thin", "ThinItalic", "Light", "LightItalic", "Regular", "Italic", "Medium", "MediumItalic",
        "Bold", "BoldItalic", "Black", "BlackItalic",
    )],
    "KG Serif": [f"NotoSerif-{style}.ttf" for style in ("Regular", "Italic", "Bold", "BoldItalic")],
    "KG Mono": ["DroidSansMono.ttf"],
    "KG Cursive": ["DancingScript-Regular.ttf", "DancingScript-Bold.ttf"],
}


def read(source: Path, name: str) -> bytes:
    if source.is_dir():
        return (source / name).read_bytes()
    with zipfile.ZipFile(source) as archive:
        return archive.read(f"fonts/{name}")


def main() -> None:
    source, output = Path(sys.argv[1]), Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    for family, files in FAMILIES.items():
        for name in files:
            font = TTFont(io.BytesIO(read(source, name)))
            table = font["name"]
            style = name.split("-")[-1].removesuffix(".ttf")
            if style == "DroidSansMono":
                style = "Regular"
            for record in list(table.names):
                if record.nameID in (1, 16):
                    table.setName(family, record.nameID, record.platformID, record.platEncID, record.langID)
                elif record.nameID in (2, 17):
                    table.setName(style, record.nameID, record.platformID, record.platEncID, record.langID)
                elif record.nameID in (4, 6):
                    full = f"{family} {style}"
                    table.setName(full.replace(" ", "-") if record.nameID == 6 else full, record.nameID, record.platformID, record.platEncID, record.langID)
            font.save(output / name)
    print(f"{sum(len(files) for files in FAMILIES.values())} fonts written to {output}")


if __name__ == "__main__":
    main()
