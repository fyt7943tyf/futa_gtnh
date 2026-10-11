# -*- coding: utf-8 -*-
"""生成客户端功能设置界面的背包按钮贴图（16×16 齿轮图标，stdlib 实现 PNG 编码）。

用法：py tools/texture/gen_settings_button.py
输出：src/main/resources/assets/futa_gtnh/textures/gui/settings_button.png

用极坐标程序化画一个标准 8 齿齿轮（不是手绘像素画）：
  - 齿轮盘：半径 3.0~5.6
  - 齿：半径到 7.2，每 45° 一个，角宽 ±13°
  - 中孔：半径 <1.8
  - 描边：与透明相邻的像素压暗；左上 135° 扇区提亮做高光
"""
import math
import os
import struct
import zlib

CX = 7.5
CY = 7.5
R_BODY_INNER = 2.6
R_BODY = 5.4
R_TOOTH = 7.0
R_HOLE = 2.0
TOOTH_HALF_DEG = 22.0

# 主体三档灰 + 孔 + 描边
C_BODY = (128, 132, 148, 255)
C_LIGHT = (186, 192, 208, 255)
C_DARK = (96, 100, 116, 255)
C_HOLE = (26, 28, 36, 255)
C_EDGE = (48, 50, 60, 255)


def classify(x, y):
    """@return (颜色, 是否实心)；x/y 为像素中心坐标。"""
    dx = x - CX
    dy = y - CY
    r = math.sqrt(dx * dx + dy * dy)
    if r > R_TOOTH:
        return None, False

    angle = math.degrees(math.atan2(dy, dx)) % 360.0
    tooth = False
    for base in range(0, 360, 45):
        delta = min(abs(angle - base), 360 - abs(angle - base))
        if delta <= TOOTH_HALF_DEG:
            tooth = True
            break

    if r < R_HOLE:
        return C_HOLE, True
    # 盘从孔沿一直实心到外缘（之前 R_BODY_INNER 在中间留了个空环，齿轮变成雪花）
    solid = r <= R_BODY or (tooth and r <= R_TOOTH)

    if not solid:
        return None, False

    # 左上扇区（225°±60°）提亮，右下压暗，中间主体
    # 只在盘面左上 180°~240° 之间留一圈窄高光,其余全平色 —— 方向性明暗会把
    # 16px 的齿轮染成雪花
    if 180.0 <= angle <= 240.0 and r <= R_BODY:
        return C_LIGHT, True
    return C_BODY, True


def build_pixels():
    solid_map = [[classify(x + 0.5, y + 0.5)[1] for x in range(16)] for y in range(16)]
    pixels = [[None] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            color, solid = classify(x + 0.5, y + 0.5)
            if not solid:
                pixels[y][x] = (0, 0, 0, 0)
                continue
            # 描边：四邻有透明就压暗（只压外圈，不压中孔边缘的孔色）
            edge = False
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < 16 and 0 <= ny < 16:
                    if not solid_map[ny][nx]:
                        edge = True
                        break
            if edge and color is not C_HOLE:
                pixels[y][x] = C_EDGE
            else:
                pixels[y][x] = color
    return pixels


def write_png(path, pixels):
    width, height = 16, 16
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0 (None)
        for rgba in row:
            raw.extend(rgba)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8bit RGBA
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("written", path)


def main():
    base = os.path.join(
        os.path.dirname(__file__),
        "..",
        "..",
        "src",
        "main",
        "resources",
        "assets",
        "futa_gtnh",
        "textures",
        "gui")
    os.makedirs(base, exist_ok=True)
    write_png(os.path.join(base, "settings_button.png"), build_pixels())


if __name__ == "__main__":
    main()
