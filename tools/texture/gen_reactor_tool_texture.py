# -*- coding: utf-8 -*-
"""生成 IC2 反应堆布局工具的物品贴图（16×16「蓝图卡片」图标，stdlib 实现 PNG 编码）。

用法：py tools/texture/gen_reactor_tool_texture.py
输出：src/main/resources/assets/futa_gtnh/textures/items/reactor_layout_tool.png

画面设计（与工具的功能对应）：
  - 外圈钢框：左上高光 / 右下阴影的斜面质感（反应堆是钢结构）；
  - 主体：深钢灰；
  - 内嵌深蓝「蓝图」面板：布局档案的隐喻；
  - 面板里 2×2 的迷你网格：IC2 反应堆的 6×N 网格缩影，
    绿色 = 散热组件，左上一格黄色 = 燃料棒。
"""
import os
import struct
import zlib

SIZE = 16

C_STEEL_LIGHT = (176, 180, 196, 255)
C_STEEL_MID = (120, 124, 140, 255)
C_STEEL_DARK = (82, 86, 100, 255)
C_EDGE = (36, 38, 48, 255)
C_BODY = (58, 60, 72, 255)
C_PANEL = (30, 46, 78, 255)
C_PANEL_EDGE = (14, 22, 40, 255)
C_CELL_GREEN = (78, 198, 96, 255)
C_CELL_GREEN_DARK = (46, 140, 62, 255)
C_CELL_YELLOW = (232, 214, 92, 255)
C_CELL_YELLOW_DARK = (172, 154, 44, 255)


def classify(x, y):
    """@return 该像素的颜色；蓝图外透明。坐标为像素左上角。"""
    if x < 0 or y < 0 or x >= SIZE or y >= SIZE:
        return None

    # 外圈钢框（带左上高光/右下阴影的斜面）
    if x == 0 or y == 0:
        return C_STEEL_LIGHT
    if x == SIZE - 1 or y == SIZE - 1:
        return C_STEEL_DARK
    if x == 1 or y == 1:
        return C_STEEL_MID
    if x == SIZE - 2 or y == SIZE - 2:
        return C_STEEL_MID

    # 蓝图面板：(2,2)-(13,13)
    if 2 <= x <= 13 and 2 <= y <= 13:
        if x == 2 or y == 2 or x == 13 or y == 13:
            return C_PANEL_EDGE
        # 迷你网格：2×2 的 3×3 格，格间距 1px，区域 (4,4)-(11,11)
        cell_size = 3
        gap = 1
        cells = [(4, 4), (8, 4), (4, 8), (8, 8)]
        for index, (cx, cy) in enumerate(cells):
            in_x = cx <= x < cx + cell_size
            in_y = cy <= y < cy + cell_size
            if not (in_x and in_y):
                continue
            # 右下 1px 做格子阴影，其余是格子本色；左上那格是「燃料棒」（黄）
            shaded = x == cx + cell_size - 1 or y == cy + cell_size - 1
            if index == 0:
                return C_CELL_YELLOW_DARK if shaded else C_CELL_YELLOW
            return C_CELL_GREEN_DARK if shaded else C_CELL_GREEN
        return C_PANEL
    return C_BODY


def render():
    pixels = bytearray()
    for y in range(SIZE):
        for x in range(SIZE):
            color = classify(x, y)
            if color is None:
                pixels += b"\x00\x00\x00\x00"
            else:
                pixels += bytes(color)
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
    root = os.path.join(os.path.dirname(__file__), "..", "..")
    target = os.path.join(
        root,
        "src",
        "main",
        "resources",
        "assets",
        "futa_gtnh",
        "textures",
        "items",
        "reactor_layout_tool.png")
    write_png(os.path.abspath(target), render())
    print("written:", os.path.abspath(target))


if __name__ == "__main__":
    main()
