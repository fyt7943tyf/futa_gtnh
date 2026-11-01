# -*- coding: utf-8 -*-
"""生成纸箱 + ME 封装胶带的贴图（16×16，stdlib 实现 PNG 编码）。

用法：py tools/texture/gen_packing_textures.py
输出（src/main/resources/assets/futa_gtnh/textures/ 下）：
  blocks/cardboard_box_side.png     纸箱侧面（牛皮纸 + 盖线）
  blocks/cardboard_box_top.png      纸箱顶/底（牛皮纸 + 十字封盖线）
  blocks/cardboard_box_storage.png  已装箱侧面（多两条胶带，meta=1 用）
  items/me_packing_tape.png         胶带卷（牛皮纸环 + 中孔）
  items/me_package.png              封装物品（小纸箱 + 胶带十字）

设计：牛皮纸底色 + 斜面高光/阴影表现纸板的层叠感；
已装箱/封装物品用亮一点的胶带色（黄褐）做区分。
"""
import os
import struct
import zlib

SIZE = 16

# 牛皮纸色系
C_KRAFT = (196, 148, 92, 255)
C_KRAFT_LIGHT = (219, 175, 120, 255)
C_KRAFT_DARK = (154, 110, 62, 255)
C_EDGE = (112, 78, 42, 255)
C_FLAP = (176, 128, 74, 255)
# 胶带（已装箱标识）
C_TAPE = (222, 190, 96, 255)
C_TAPE_DARK = (178, 148, 58, 255)


def box_side(x, y, with_tape):
    """纸箱侧面：外圈纸板边缘，中间两道横向盖线；with_tape 时加两条竖胶带。"""
    if x == 0 or y == 0:
        return C_KRAFT_LIGHT
    if x == SIZE - 1 or y == SIZE - 1:
        return C_KRAFT_DARK
    # 盖线：上盖边 + 下盖边
    if y == 5 or y == 10:
        return C_EDGE
    if y == 6 or y == 9:
        return C_FLAP
    body = C_KRAFT if (x + y) % 7 else C_KRAFT_DARK  # 轻微纹理
    if with_tape and (x in (3, 4) or x in (11, 12)):
        # 竖向胶带条，边缘压暗一列
        return C_TAPE_DARK if x in (3, 11) else C_TAPE
    return body


def box_top(x, y):
    """纸箱顶/底：外圈边缘 + 十字封盖线 + 中心交叉胶带。"""
    if x == 0 or y == 0:
        return C_KRAFT_LIGHT
    if x == SIZE - 1 or y == SIZE - 1:
        return C_KRAFT_DARK
    if x == 7 or x == 8 or y == 7 or y == 8:
        return C_FLAP
    # 中心 4x4 胶带交叉
    if 6 <= x <= 9 and 6 <= y <= 9:
        return C_TAPE if (x in (7, 8) or y in (7, 8)) else C_TAPE_DARK
    return C_KRAFT if (x + y) % 7 else C_KRAFT_DARK


def tape_roll(x, y):
    """胶带卷物品图标：环形卷 + 中孔 + 一段拉出的胶带。"""
    cx, cy = 7.5, 8.5
    d2 = (x - cx) ** 2 + (y - cy) ** 2
    if d2 <= 4:
        return None  # 中孔
    if d2 <= 36:
        # 卷体：左上高光、右下阴影
        if x + y < 13:
            return C_KRAFT_LIGHT
        if x + y > 20:
            return C_KRAFT_DARK
        return C_KRAFT
    # 拉出的一段胶带（右下角）
    if 11 <= x <= 15 and y == 11:
        return C_TAPE_DARK
    if 11 <= x <= 15 and y in (12, 13):
        return C_TAPE
    if 11 <= x <= 15 and y == 14:
        return C_TAPE_DARK
    return None


def package_item(x, y):
    """封装物品：小纸箱 + 满幅胶带十字。"""
    if x < 2 or y < 3 or x > 13 or y > 14:
        return None
    if x == 2 or y == 3:
        return C_KRAFT_LIGHT
    if x == 13 or y == 14:
        return C_KRAFT_DARK
    # 胶带十字（竖 3 宽 + 横 3 宽）
    if 6 <= x <= 8 or 7 <= y <= 9:
        return C_TAPE if (x == 7 or y == 8) else C_TAPE_DARK
    if y == 5:
        return C_EDGE
    return C_KRAFT


def render(classify):
    pixels = bytearray()
    for y in range(SIZE):
        for x in range(SIZE):
            color = classify(x, y)
            pixels += b"\x00\x00\x00\x00" if color is None else bytes(color)
    return bytes(pixels)


def write_png(path, raw):
    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)  # 8bit RGBA
    rows = b"".join(b"\x00" + raw[y * SIZE * 4:(y + 1) * SIZE * 4] for y in range(SIZE))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", header)
           + chunk(b"IDAT", zlib.compress(rows, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(png)


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    base = os.path.join(root, "src", "main", "resources", "assets", "futa_gtnh", "textures")

    outputs = [
        (os.path.join(base, "blocks", "cardboard_box_side.png"), lambda x, y: box_side(x, y, False)),
        (os.path.join(base, "blocks", "cardboard_box_top.png"), box_top),
        (os.path.join(base, "blocks", "cardboard_box_storage.png"), lambda x, y: box_side(x, y, True)),
        (os.path.join(base, "items", "me_packing_tape.png"), tape_roll),
        (os.path.join(base, "items", "me_package.png"), package_item),
    ]
    for path, classify in outputs:
        write_png(path, render(classify))
        print("written:", path)


if __name__ == "__main__":
    main()
