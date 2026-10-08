#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 CastHub 的应用图标（纯标准库，无第三方依赖）。

产出：
  app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
  app/src/main/res/mipmap-{...}/ic_launcher_round.png
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png   (自适应图标前景)
  app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
  app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml

图案：深蓝渐变底 + 白色「屏幕 + 播放三角 + 投屏波纹」。
用 4x 超采样做抗锯齿。
"""

import math
import os
import struct
import zlib

SS = 3  # 超采样倍数

BG_TOP = (47, 107, 255)
BG_BOTTOM = (24, 79, 216)
FG = (255, 255, 255)

RES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


# ─────────────────────────── PNG 编码 ───────────────────────────

def write_png(path, width, height, pixels):
    """pixels: 二维列表，元素为 (r,g,b,a)"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            r, g, b, a = pixels[y][x]
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


# ─────────────────────────── 几何工具 ───────────────────────────

def sd_rounded_rect(px, py, cx, cy, hw, hh, r):
    """圆角矩形有符号距离，<0 表示在内部。"""
    qx = abs(px - cx) - (hw - r)
    qy = abs(py - cy) - (hh - r)
    return (math.hypot(max(qx, 0.0), max(qy, 0.0))
            + min(max(qx, qy), 0.0) - r)


def lerp(a, b, t):
    return a + (b - a) * t


def lerp_color(c1, c2, t):
    return tuple(int(round(lerp(c1[i], c2[i], t))) for i in range(3))


def in_triangle(px, py, ax, ay, bx, by, cx, cy):
    d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
    d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
    d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
    has_neg = d1 < 0 or d2 < 0 or d3 < 0
    has_pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (has_neg and has_pos)


def arc_distance(px, py, cx, cy, radius):
    return abs(math.hypot(px - cx, py - cy) - radius)


def angle_deg(px, py, cx, cy):
    return math.degrees(math.atan2(py - cy, px - cx)) % 360.0


# ─────────────────────────── 图案 ───────────────────────────

def foreground_alpha(x, y):
    """
    前景图案的覆盖度（0..1），坐标已归一化到 0..1。
    图案：屏幕轮廓 + 播放三角 + 右侧投屏波纹。
    """
    # 屏幕：圆角矩形描边
    screen = sd_rounded_rect(x, y, 0.42, 0.40, 0.26, 0.185, 0.055)
    border = 0.030

    if screen <= 0 and screen >= -border:
        return 1.0
    # 屏幕内部挖空 -> 但要让播放三角露出来，所以这里不直接返回

    # 播放三角（屏幕中央偏左）
    if in_triangle(x, y, 0.345, 0.315, 0.345, 0.485, 0.505, 0.400):
        return 1.0

    # 底座支架
    if abs(x - 0.42) <= 0.085 and abs(y - 0.625) <= 0.019:
        return 1.0

    # 投屏波纹：以右下角为圆心，三条弧
    wcx, wcy = 0.845, 0.815
    for radius in (0.085, 0.150, 0.215):
        d = arc_distance(x, y, wcx, wcy, radius)
        if d <= 0.026:
            ang = angle_deg(x, y, wcx, wcy)
            # 只保留朝左上的 180°~290° 区间
            if 175.0 <= ang <= 295.0:
                return 1.0

    return 0.0


def sample(x, y, round_mask):
    """返回单个采样点的 RGBA。"""
    if round_mask:
        inside = (x - 0.5) ** 2 + (y - 0.5) ** 2 <= 0.5 ** 2
    else:
        inside = sd_rounded_rect(x, y, 0.5, 0.5, 0.5, 0.5, 0.235) <= 0

    if not inside:
        return (0, 0, 0, 0)

    bg = lerp_color(BG_TOP, BG_BOTTOM, y)
    if foreground_alpha(x, y) > 0.5:
        return FG + (255,)
    return bg + (255,)


def render(size, round_mask=False, transparent_bg=False):
    out = []
    for py in range(size):
        row = []
        for px in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(SS):
                for sx in range(SS):
                    x = (px * SS + sx + 0.5) / (size * SS)
                    y = (py * SS + sy + 0.5) / (size * SS)
                    if transparent_bg:
                        # 自适应图标：系统会裁掉外层约 18%，
                        # 因此把图案缩到中心区域内，避免被切边。
                        scale = 0.68
                        ix = (x - 0.5) / scale + 0.5
                        iy = (y - 0.5) / scale + 0.5
                        a = foreground_alpha(ix, iy) if (0.0 <= ix <= 1.0 and 0.0 <= iy <= 1.0) else 0.0
                        c = (255, 255, 255, int(round(a * 255)))
                    else:
                        c = sample(x, y, round_mask)
                    for i in range(4):
                        acc[i] += c[i]
            n = SS * SS
            row.append(tuple(v // n for v in acc))
        out.append(row)
    return out


# ─────────────────────────── 主流程 ───────────────────────────

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

ADAPTIVE_ROUND_XML = ADAPTIVE_XML


def main():
    for density, size in DENSITIES.items():
        folder = os.path.join(RES_DIR, f"mipmap-{density}")
        write_png(os.path.join(folder, "ic_launcher.png"), size, size,
                  render(size, round_mask=False))
        write_png(os.path.join(folder, "ic_launcher_round.png"), size, size,
                  render(size, round_mask=True))
        print(f"  mipmap-{density}: {size}x{size} 已完成")

    # 自适应图标前景：内容需缩到中心 ~66%，因此用 432px 画布、图案缩放
    fg_size = 288
    write_png(os.path.join(RES_DIR, "mipmap-xxxhdpi", "ic_launcher_foreground.png"),
              fg_size, fg_size, render(fg_size, transparent_bg=True))
    print(f"  ic_launcher_foreground: {fg_size}x{fg_size} 已完成")

    anydpi = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(ADAPTIVE_XML)
    with open(os.path.join(anydpi, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(ADAPTIVE_ROUND_XML)
    print("  自适应图标 XML 已完成")


if __name__ == "__main__":
    main()
