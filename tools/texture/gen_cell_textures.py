# -*- coding: utf-8 -*-
"""生成 AE2 共享背包存储元件的两张 16x16 物品贴图（stdlib 实现 PNG 编码）。

用法：py tools/texture/gen_cell_textures.py
输出：src/main/resources/assets/futa_gtnh/textures/items/shared_cell_item.png
      src/main/resources/assets/futa_gtnh/textures/items/shared_cell_fluid.png

像素字符含义：
  .  透明      #  外壳描边   D  深灰金属   L  浅灰金属
  W  高光      B  内阴影     C  核心暗色   c  核心亮色
"""
import os
import struct
import zlib

# fmt: off
ART = [
    "................",
    "..############..",
    ".#DDDDDDDDDDDD#.",
    ".#DLLLLLLLLLDD#.",
    ".#DLWWWWWWWLDD#.",
    ".#DWCCCCCCCCBD#.",
    ".#DWccccccccbD#.",
    ".#DWccccccccbD#.",
    ".#DWCCCCCCCCBD#.",
    ".#DLWWWWWWWLDD#.",
    ".#DDDDDDDDDDDD#.",
    ".#DDDDDDDDDDDD#.",
    "..############..",
    "................",
    "................",
    "................",
]
# fmt: on

PALETTE_BASE = {
    ".": (0, 0, 0, 0),
    "#": (58, 58, 66, 255),
    "D": (96, 96, 108, 255),
    "L": (138, 138, 152, 255),
    "W": (188, 190, 204, 255),
    "B": (42, 42, 48, 255),
}
CORE_COLORS = {
    # 核心暗色 / 核心亮色：物品=绿色（AE 物品元件的传统色），流体=蓝青
    "item": ((36, 128, 78, 255), (108, 228, 158, 255)),
    "fluid": ((36, 100, 156, 255), (112, 190, 255, 255)),
}


def write_png(path, art, core_dark, core_bright):
    palette = dict(PALETTE_BASE)
    palette["C"] = core_dark
    palette["c"] = core_bright
    palette["b"] = core_bright

    width, height = len(art[0]), len(art)
    raw = bytearray()
    for row in art:
        raw.append(0)  # filter type 0 (None)
        for ch in row:
            raw.extend(palette[ch])

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8bit RGBA
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("written", path)


def main():
    base = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "futa_gtnh",
                        "textures", "items")
    for name in ("item", "fluid"):
        dark, bright = CORE_COLORS[name]
        write_png(os.path.join(base, "shared_cell_%s.png" % name), ART, dark, bright)


if __name__ == "__main__":
    main()
