#!/usr/bin/env python3
"""客观判定"视频画面在屏幕上是否被拉伸/裁切"。

为什么需要它：竖屏视频投到横屏电视时，肉眼很难分清"保持比例加了黑边"和
"被横向拉伸"—— 尤其当画面本身是对称图形时。测试素材里放了一个**正方形标记**，
正方形一旦变成矩形就是被拉伸，这是可量化、不依赖主观判断的证据。

配套素材由 tools/ 之外的一次性命令生成（见 README / 记忆笔记）：
  · 深蓝底 0x102040
  · 中央 400x400 纯黄 0xFFCC00 正方形
  · 竖屏 720x1280（DAR 9:16）、横屏 1280x720（DAR 16:9）

用法：
    adb exec-out screencap > shot.raw
    python tools/video_frame_fit_check.py shot.raw --source 720x1280
"""
from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path


def load_raw(path: Path) -> tuple[int, int, bytes]:
    """解析 `adb exec-out screencap` 的裸帧。

    头部是 3~4 个小端 uint32（宽、高、像素格式、可选 colorspace），
    像素紧随其后。这里按"头之后正好剩下 w*h*4 字节"来定位头部长度，
    比硬编码 12 或 16 更稳（不同 Android 版本头部长度不同）。
    """
    data = path.read_bytes()
    if len(data) < 16:
        raise SystemExit("文件太小，不是裸帧")
    width, height, fmt = struct.unpack_from("<III", data, 0)
    if not (0 < width < 10000 and 0 < height < 10000):
        raise SystemExit(f"头部不像裸帧：{width}x{height}")
    for header in (16, 12):
        if len(data) - header == width * height * 4:
            return width, height, data[header:]
    raise SystemExit(
        f"像素数对不上：文件 {len(data)} 字节，{width}x{height} 需要 "
        f"{width * height * 4} 字节（+12 或 16 字节头）"
    )


def is_yellow(r: int, g: int, b: int) -> bool:
    return r > 170 and g > 130 and b < 110


def is_blue(r: int, g: int, b: int) -> bool:
    return b >= 40 and b > r + 10 and b > g + 10 and r < 90


def is_black(r: int, g: int, b: int) -> bool:
    return r < 18 and g < 18 and b < 18


def bbox(pixels: bytes, width: int, height: int, match) -> tuple[int, int, int, int, int] | None:
    left, top, right, bottom, count = width, height, -1, -1, 0
    for y in range(height):
        row = y * width * 4
        for x in range(width):
            offset = row + x * 4
            r, g, b = pixels[offset], pixels[offset + 1], pixels[offset + 2]
            if match(r, g, b):
                count += 1
                if x < left:
                    left = x
                if x > right:
                    right = x
                if y < top:
                    top = y
                if y > bottom:
                    bottom = y
    if count == 0:
        return None
    return left, top, right, bottom, count


def main() -> int:
    parser = argparse.ArgumentParser(description="判定视频画面的缩放是否正确")
    parser.add_argument("raw", help="adb exec-out screencap 导出的裸帧")
    parser.add_argument("--source", required=True, help="源视频尺寸，如 720x1280")
    parser.add_argument("--ignore-top", type=int, default=0,
                        help="忽略顶部这么多行（OSD 浮层会盖住画面）")
    args = parser.parse_args()

    src_w, src_h = (int(v) for v in args.source.lower().split("x"))
    src_dar = src_w / src_h

    width, height, pixels = load_raw(Path(args.raw))
    print(f"屏幕：{width}x{height}   源视频：{src_w}x{src_h}（DAR {src_dar:.4f}）")

    if args.ignore_top:
        # 直接把顶部若干行涂黑，等价于"不看这块区域"
        stride = width * 4
        pixels = b"\x00" * (stride * min(args.ignore_top, height)) + pixels[stride * min(args.ignore_top, height):]

    ok = True

    square = bbox(pixels, width, height, is_yellow)
    if square is None:
        print("  ✗ 找不到黄色正方形标记 —— 画面没渲染出来？")
        return 1
    sl, st, sr, sb, scount = square
    sw, sh = sr - sl + 1, sb - st + 1
    distortion = sw / sh
    print(f"\n① 正方形标记（源 400x400 → 应仍为正方形）")
    print(f"   实测 {sw}x{sh} px   宽高比 {distortion:.3f}  （1.000 = 未畸变）")
    if abs(distortion - 1.0) <= 0.03:
        print("   ✅ 未畸变")
    else:
        direction = "横向拉伸" if distortion > 1 else "竖向拉伸"
        print(f"   ❌ 画面被{direction}：横向为正确比例的 {distortion:.2f} 倍")
        ok = False

    frame = bbox(pixels, width, height, is_blue)
    if frame is None:
        print("\n② 视频内容区域：找不到深蓝底 —— 可能整屏被裁切")
        return 1 if ok else 0
    fl, ft, fr, fb, fcount = frame
    fw, fh = fr - fl + 1, fb - ft + 1
    measured = fw / fh
    print(f"\n② 视频内容区域（源 DAR {src_dar:.4f}）")
    print(f"   x {fl}→{fr}（宽 {fw}）  y {ft}→{fb}（高 {fh}）")
    print(f"   实测宽高比 {measured:.4f}")

    # 内容区域应当与源同比例，否则就是被拉去填满或裁掉了
    if abs(measured - src_dar) / src_dar <= 0.04:
        print("   ✅ 与源同比例")
        bar_left, bar_right = fl, width - fr - 1
        if min(bar_left, bar_right) > 4 or ft > 4 or (height - fb - 1) > 4:
            print(f"   黑边：左右 {bar_left} / {bar_right} px，上 {ft} px，下 {height - fb - 1} px（保持比例的正常留边）")
        else:
            print("   无黑边（内容正好铺满，符合比例）")
    else:
        print(f"   ❌ 与源比例不符（差 {abs(measured - src_dar) / src_dar * 100:.0f}%）—— 画面被强行改变形状")
        ok = False

    # 交叉验证：内容区域的高度是否等于屏幕高度（说明是"填满型"而非"留边型"）
    if fb >= height - 2 and ft <= 1:
        print("   （内容在垂直方向铺满了整屏）")

    print("\n结论：" + ("✅ 缩放正确" if ok else "❌ 存在畸变"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
