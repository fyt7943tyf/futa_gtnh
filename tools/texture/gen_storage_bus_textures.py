# -*- coding: utf-8 -*-
"""生成共享存储总线方块的两张 16x16 贴图（side / front）。

用法：python tools/texture/gen_storage_bus_textures.py
输出：src/main/resources/assets/futa_gtnh/textures/blocks/storage_bus_side.png
      src/main/resources/assets/futa_gtnh/textures/blocks/storage_bus_front.png

像素字符含义：
  .  透明   #  外壳描边   D  深灰金属   L  浅灰金属
  C  面板暗色   W  青色高光   A  青色   a  深青
  B  接口底板   G  绿色指示灯   g  指示灯高光
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "futa_gtnh")

# fmt: off
SIDE = [
    "................",
    "..############..",
    ".#DDDDDDDDDDDD#.",
    ".#DLLLLLLLLLLD#.",
    ".#DLCCCCCCCCLBD#",
    ".#DLCWWWWWWCCBD#",
    ".#DLCWAAAAAWCCBD",
    ".#DLCWAaaaAWCCBD",
    ".#DLCWAaaaAWCCBD",
    ".#DLCWAAAAAWCCBD",
    ".#DLCWWWWWWCCBD#",
    ".#DLCCCCCCCCLBD#",
    ".#DLLLLLLLLLLD#.",
    ".#DDDDDDDDDDDD#.",
    "..############..",
    "................",
]

FRONT = [
    "................",
    "..############..",
    ".#DDDDDDDDDDDD#.",
    ".#DLBBBBBBBBLBD#",
    ".#DLBGGGGGGGBBD#",
    ".#DLBGBBBBBGBBD#",
    ".#DLBGBCCCBGBBD#",
    ".#DLBGBCCCBGBBD#",
    ".#DLBGBCCCBGBBD#",
    ".#DLBGBCCCBGBBD#",
    ".#DLBGBBBBBGBBD#",
    ".#DLBGGGGGGGBBD#",
    ".#DLBBBBBBBBLBD#",
    ".#DLLLLLLLLLLD#.",
    "..############..",
    "................",
]
# fmt: on

PALETTE = {
    ".": (0, 0, 0, 0),
    "#": (40, 40, 46, 255),
    "D": (82, 82, 92, 255),
    "L": (128, 128, 140, 255),
    "C": (64, 68, 78, 255),
    "W": (120, 220, 230, 255),
    "A": (60, 170, 190, 255),
    "a": (35, 100, 120, 255),
    "B": (26, 26, 32, 255),
    "G": (80, 200, 110, 255),
    "g": (180, 255, 190, 255),
}


def write_png(path, art):
    width, height = len(art[0]), len(art)
    raw = b""
    for row in art:
        raw += b"\x00" + b"".join(bytes(PALETTE[ch]) for ch in row)

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as out:
        out.write(png)


def main():
    blocks = os.path.join(ROOT, "textures", "blocks")
    os.makedirs(blocks, exist_ok=True)
    write_png(os.path.join(blocks, "storage_bus_side.png"), SIDE)
    write_png(os.path.join(blocks, "storage_bus_front.png"), FRONT)
    print("wrote storage_bus_side.png / storage_bus_front.png")


if __name__ == "__main__":
    main()
