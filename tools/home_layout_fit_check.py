#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""待机页「一屏装得下」核对工具。

为什么需要它
------------
主页是**遥控器滚不动**的一页：唯一可聚焦的元素是右上角「设置」，
而 `ScrollView` 被刻意设为 `focusable=false`（它有焦点陷阱的历史，
见 MainActivity.bindViews 的注释）。也就是说，只要内容比可视区高，
**超出的部分在电视上永远看不到**，而不是"滚动一下就能看到"。

1080p 电视通常是 320dpi，逻辑尺寸只有 960×540dp；扣掉顶栏/底栏/状态栏，
可滚区实测只剩 ~356dp。所以版式必须保证"装得进一屏"，
而这件事只能实测量出来 —— 光看预览图看不出被裁掉。

用法
----
    # 用当前屏幕配置量一次
    python tools/home_layout_fit_check.py

    # 依次把模拟器切成几种真实机型配置再量（用完自动恢复原配置）
    python tools/home_layout_fit_check.py --presets

判定口径
--------
对每个内容块取 `uiautomator dump` 的 bounds；若节点**压根不在 dump 里**，
说明它被排到了可视区之外（uiautomator 会 `Skipping invisible child`），
这比"部分超出"更严重：连标题都看不见。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

PKG = "com.casthub.app"
ACTIVITY = f"{PKG}/.MainActivity"

# 待机页上"必须能一眼看到"的内容块（按文案定位，避免依赖资源 id 顺序）
BLOCKS = [
    ("设备名", None),          # 单独用 tv_device_name 判
    ("支持的投屏方式", "支持的投屏方式"),
    ("DLNA 芯片", "DLNA / UPnP"),
    ("AirPlay 芯片", "AirPlay"),
    ("怎么投屏", "怎么投屏"),
]

# 真实机型配置：逻辑尺寸 = 像素 / (dpi/160)
PRESETS = [
    ("1080p 电视", 1920, 1080, 320),   # 960x540dp —— 待机页最窄的高度
    ("720p 电视", 1280, 720, 160),     # 1280x720dp
    ("手机竖屏", 1080, 2340, 440),     # 392dp，走 values/ 那套尺寸
]


class Adb:
    def __init__(self, adb: str):
        self.adb = adb

    def __call__(self, *args: str) -> str:
        p = subprocess.run(
            [self.adb, "shell", *args],
            capture_output=True, text=True, encoding="utf-8", errors="ignore",
        )
        return p.stdout

    def raw(self, *args: str) -> str:
        p = subprocess.run(
            [self.adb, *args],
            capture_output=True, text=True, encoding="utf-8", errors="ignore",
        )
        return p.stdout


def resolve_adb(explicit: str | None) -> str | None:
    """按 显式指定 → $ANDROID_HOME → PATH 的顺序找 adb。

    不写死绝对路径：换台机器就得改脚本，等于把工具变成一次性的。
    """
    import os
    import shutil

    if explicit:
        return explicit if os.path.exists(explicit) or shutil.which(explicit) else None
    for home_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        home = os.environ.get(home_var)
        if home:
            cand = os.path.join(home, "platform-tools", "adb.exe")
            if os.path.exists(cand):
                return cand
            cand = os.path.join(home, "platform-tools", "adb")
            if os.path.exists(cand):
                return cand
    return shutil.which("adb")


def dump_ui(adb: Adb, tries: int = 5) -> str:
    """uiautomator dump 会间歇性返回空，必须重试并确认拿到内容。"""
    for _ in range(tries):
        adb("uiautomator", "dump", "/sdcard/_fit.xml")
        xml = adb("cat", "/sdcard/_fit.xml")
        if xml and "<node" in xml:
            return xml
        time.sleep(0.6)
    raise RuntimeError("uiautomator dump 始终拿不到内容，改用截图人工核对")


def bounds_of_id(xml: str, view_id: str):
    m = re.search(
        r'resource-id="%s:id/%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % (re.escape(PKG), view_id),
        xml,
    )
    return tuple(map(int, m.groups())) if m else None


def bounds_of_text(xml: str, text: str):
    m = re.search(
        r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text),
        xml,
    )
    return tuple(map(int, m.groups())) if m else None


def measure(adb: Adb, label: str, width: int, height: int) -> bool:
    adb("am", "force-stop", PKG)
    adb("am", "start", "-n", ACTIVITY)
    time.sleep(5)
    xml = dump_ui(adb)

    scroll = bounds_of_id(xml, "home_scroll")
    print(f"== {label}  （{width}×{height}px）==")
    if scroll is None:
        print("   ✗ 拿不到 home_scroll，无法判定")
        return False

    top, bottom = scroll[1], scroll[3]
    print(f"   可滚区 y {top}→{bottom}（高 {bottom - top}px）")

    all_ok = True
    target = bounds_of_id(xml, "tv_device_name")
    if target is None:
        print("   %-14s ✗ 不在可视区" % "设备名")
        all_ok = False
    else:
        ok = top - 2 <= target[1] and target[3] <= bottom + 2
        print("   %-14s y %4d→%4d  %s" % ("设备名", target[1], target[3], "✓" if ok else "✗ 超出可视区"))
        all_ok &= ok

    for name, text in BLOCKS[1:]:
        r = bounds_of_text(xml, text)
        if r is None:
            print("   %-14s ✗ 不在可视区（被挤出屏幕）" % name)
            all_ok = False
            continue
        ok = top - 2 <= r[1] and r[3] <= bottom + 2
        note = ""
        # 被裁掉的表现常常是"节点在、但高度只剩几个像素"，
        # 光判断"在不在 dump 里"发现不了（实测点名提示被裁到只剩 7px）。
        if ok and r[3] - r[1] <= 8:
            ok = False
            note = "  ✗ 被裁到只剩 %dpx（视觉上等于看不到）" % (r[3] - r[1])
        print("   %-14s y %4d→%4d  %s%s" % (name, r[1], r[3], "✓" if ok else "✗ 超出可视区", note))
        all_ok &= ok

    # 状态卡片单独量：服务全开 vs 有一项关掉，文案长度差很多，
    # 关掉一项时它可能折成两行、把下面的芯片顶出去 ——
    # 这正是"点名提示"当年被裁掉的那个坑，所以必须按两种状态各量一次。
    status = bounds_of_id(xml, "tv_status")
    if status:
        line = "单行" if status[3] - status[1] < 60 else "多行"
        ok = status[3] <= bottom + 2
        print("   状态卡片         y %4d→%4d（高 %3dpx，%s）%s"
              % (status[1], status[3], status[3] - status[1], line, "✓" if ok else " ✗ 超出可视区"))
        all_ok &= ok

    rightmost = max((int(m.group(1)) for m in re.finditer(r'bounds="\[\d+,\d+\]\[(\d+),\d+\]"', xml)), default=0)
    if rightmost > width:
        print(f"   横向溢出：最右 {rightmost} > 屏宽 {width} ✗")
        all_ok = False

    # 垂直留白：两块内容**互相压住**说明间距被挤没了（比"超出可视区"更隐蔽）。
    # 只在两者同列（横向有重叠）时才谈间距 —— 双列版式里引导卡片在另一列、
    # 垂直位置本来就高于芯片，相减是负数，那不是问题。
    chip = bounds_of_text(xml, "DLNA / UPnP")
    guide = bounds_of_id(xml, "home_guide")
    if chip and guide:
        same_column = guide[0] < chip[2] and chip[0] < guide[2]
        if same_column:
            gap = guide[1] - chip[3]
            print(f"   芯片→引导卡片 间距 {gap}px" + ("  ✗ 两块重叠！" if gap < 0 else ""))
            all_ok &= gap >= 0
        else:
            print("   芯片与引导卡片分属两列（双列版式）")

    print("   结论：" + ("✅ 全部内容一屏可见" if all_ok else "❌ 仍有内容被挤出可视区"))
    return all_ok


def _open_settings(adb: Adb) -> None:
    adb("am", "force-stop", PKG)
    adb("am", "start", "-n", ACTIVITY)
    time.sleep(4)
    adb("input", "keyevent", "KEYCODE_DPAD_DOWN")      # 焦点 -> 设置
    time.sleep(0.5)
    adb("input", "keyevent", "KEYCODE_DPAD_CENTER")    # 进设置页
    time.sleep(2.5)


def _focused_row_bounds(xml: str):
    """设置页里当前聚焦的模块行 bounds；没有则 None。

    ⚠️ 不能用「...row_root...bounds=...focused="true"」这种顺序正则：
    uiautomator 输出里 `focused` 属性在 `bounds` **之前**，按顺序写永远匹配不上。
    正确做法是先切出每个 <node>，再在节点内部逐个看属性。
    """
    for m in re.finditer(r"<node [^>]*>", xml):
        node = m.group(0)
        if f"{PKG}:id/row_root" not in node or 'focused="true"' not in node:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            return tuple(map(int, b.groups()))
    return None


def _texts_in_row(xml: str, row) -> list:
    """落在该行 y 范围内的全部文本（用来判断"这是哪个模块"和"它是开还是关"）。"""
    out = []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        text = m.group(1)
        yc = (int(m.group(3)) + int(m.group(5))) // 2
        if row[1] - 4 <= yc <= row[3] + 4:
            out.append(text)
    return out


def set_module_state(adb: Adb, display_name: str, want_on: bool) -> bool:
    """把指定模块的接收服务开关调到目标状态。

    为什么必须能可靠地构造"全部开启 / 有一项关掉"这两种状态：
    主页文案是按模块真实状态生成的 —— 全开时状态卡片一行，关掉一项时要点名
    （文案更长、可能折两行），卡片一变高就会把下面的芯片顶出可视区；
    而主页遥控器滚不动，顶出去就等于永远看不到
    （这个坑真实发生过：点名提示被裁到只剩 7px，肉眼完全看不见）。

    定位方式：逐行按「下」，dump 出当前聚焦行，看行内文本里有没有该模块名 ——
    不靠固定次数、不靠坐标，因此换屏密（960dp / 1280dp）也不会失灵。
    """
    _open_settings(adb)
    for _ in range(12):
        xml = dump_ui(adb)
        row = _focused_row_bounds(xml)
        if row is None:
            adb("input", "keyevent", "KEYCODE_DPAD_DOWN")
            time.sleep(0.5)
            continue
        texts = _texts_in_row(xml, row)
        if display_name in texts:
            on = "运行中" in texts
            if on != want_on:
                adb("input", "keyevent", "KEYCODE_DPAD_CENTER")   # 整行可点 = 切换
                time.sleep(2.5)
            adb("input", "keyevent", "KEYCODE_BACK")
            time.sleep(1.5)
            return True
        adb("input", "keyevent", "KEYCODE_DPAD_DOWN")
        time.sleep(0.5)
    adb("input", "keyevent", "KEYCODE_BACK")
    time.sleep(1.0)
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--adb",
        default=None,
        help="adb 路径；不给则依次尝试 $ANDROID_HOME/platform-tools/adb 与 PATH 里的 adb",
    )
    ap.add_argument("--presets", action="store_true", help="依次切换三种真实机型配置分别核对")
    ap.add_argument(
        "--with-partial",
        action="store_true",
        help="额外核对「有一项接收服务关掉」的版式（状态卡片会变长，最容易溢出）",
    )
    args = ap.parse_args()

    adb_path = resolve_adb(args.adb)
    if adb_path is None:
        print("找不到 adb，请用 --adb 指定（或设置 ANDROID_HOME）")
        return 2
    adb = Adb(adb_path)
    if not adb.raw("devices").strip():
        print(f"adb 无法执行：{adb_path}")
        return 2

    if not args.presets:
        size = re.search(r"(\d+)x(\d+)", adb("wm", "size"))
        dens = re.search(r"(\d+)", adb("wm", "density"))
        if not size or not dens:
            print("读不到屏幕配置")
            return 2
        w, h, d = int(size.group(1)), int(size.group(2)), int(dens.group(1))
        print(f"   逻辑尺寸 {w * 160 // d}×{h * 160 // d}dp")
        return 0 if measure(adb, "当前配置", w, h) else 1

    results = []
    try:
        for label, w, h, d in PRESETS:
            adb("wm", "size", f"{w}x{h}")
            adb("wm", "density", str(d))
            time.sleep(1)
            title = f"{label}（{w*160//d}×{h*160//d}dp）"

            # 先确保两个服务都开着，否则这一档量的其实是"关掉一项"的版式、标签会骗人
            if not set_module_state(adb, "AirPlay", True):
                print(f"   （{title}：没能把 AirPlay 打开，跳过）")
                continue
            results.append((title + "·服务全开", measure(adb, title + " · 服务全开", w, h)))

            if args.with_partial:
                if set_module_state(adb, "AirPlay", False):
                    results.append((title + "·关掉一项",
                                    measure(adb, title + " · 关掉一项", w, h)))
                    set_module_state(adb, "AirPlay", True)
                else:
                    print("   （没能关掉 AirPlay，跳过「部分未开启」这一项）")
            print()
    finally:
        # 恢复原配置，别把设备留在奇怪的密度上
        adb("wm", "size", "reset")
        adb("wm", "density", "reset")
        print("（已恢复设备原始屏幕配置）")

    bad = [label for label, ok in results if not ok]
    print()
    print("=" * 58)
    print("汇总：" + ("全部配置一屏可见 ✅" if not bad else "仍有问题配置：" + "、".join(bad) + " ❌"))
    print("=" * 58)
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
