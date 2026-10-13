# -*- coding: utf-8 -*-
"""生成 AE 无线控制的两张 16x16 贴图（stdlib 实现 PNG 编码）。

用法：py tools/texture/gen_ae_wireless_textures.py
输出：src/main/resources/assets/futa_gtnh/textures/blocks/ae_signal_transmitter.png
      src/main/resources/assets/futa_gtnh/textures/items/ae_signal_receiver_cover.png

像素字符含义：
  .  透明      #  外壳描边   D  深灰金属   L  浅灰金属   W  高光
  B  内阴影    C  面板暗色   A  天线亮青   a  天线暗青
  G  绿灯      R  红灯      g  灯芯高光
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "assets", "futa_gtnh")

# fmt: off
TRANSMITTER = [
    "................",
    "..############..",
    ".#DDDDDDDDDDDD#.",
    ".#DLLLLLLLLLDD#.",
    ".#DLWCCCCCCCBD#.",
    ".#DWCC..a..CBD#.",
    ".#DWC..AA..CBD#.",
    ".#DWC.Aa.A.CBD#.",
    ".#DWC.A..A.CBD#.",
    ".#DWCC..a..CBD#.",
    ".#DLWCCCCCCCBD#.",
    ".#DLLLLLLLLLDD#.",
    ".#DDDDDDDDDDDD#.",
    ".#DDDDDDDDDDDD#.",
    "..############..",
    "................",
]

RECEIVER = [
    "................",
    "................",
    "..############..",
    ".#DDDDDDDDDDDD#.",
    ".#DLWWWWWWWWBD#.",
    ".#DWCCCCCCCCBD#.",
    ".#DWCCGggRCCBD#.",
    ".#DWCCGggRCCBD#.",
    ".#DWCC....CCBD#.",
    ".#DWCC.aa.CCBD#.",
    ".#DLWCC..CCCBD#.",
    ".#DLLLLLLLLLDD#.",
    ".#DDDDDDDDDDDD#.",
    "..############..",
    "................",
    "................",
]
# fmt: on

PALETTE = {
    ".": (0, 0, 0, 0),
    "#": (58, 58, 66, 255),
    "D": (96, 96, 108, 255),
    "L": (138, 138, 152, 255),
    "W": (188, 190, 204, 255),
    "B": (42, 42, 48, 255),
    "C": (70, 74, 84, 255),
    "A": (110, 226, 240, 255),
    "a": (52, 140, 168, 255),
    "G": (46, 148, 84, 255),
    "R": (176, 58, 58, 255),
    "g": (168, 240, 190, 255),
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
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)
    print("wrote", os.path.normpath(path))


def main():
    write_png(os.path.join(ROOT, "textures", "blocks", "ae_signal_transmitter.png"), TRANSMITTER)
    write_png(os.path.join(ROOT, "textures", "items", "ae_signal_receiver_cover.png"), RECEIVER)


if __name__ == "__main__":
    main()
