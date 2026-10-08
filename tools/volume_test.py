#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
投屏音量闸门验证
----------------
真机验证"投屏后音量不会比投屏前更大"这件事。

为什么单独写这个脚本：
  音量只能从电视侧读到（`-dumpsys audio`），而投屏指令只能从 PC 侧发。
  两边必须在同一次运行里交替进行，否则读到的就是过期值。

流程：
  1. 用遥控器按键把电视音量设到一个已知值（先按到底、再往上加 N 格）
  2. 起播（POST /play）
  3. 依次发 /volume?volume=1.0 / 0.5 / 0.0，每一步读一次电视真实音量
  4. 期望：1.0 → 不超过投屏前的值；0.5 → 大约一半；0.0 → 0

    python tools/volume_test.py
    python tools/volume_test.py --start-volume 5 --media http://192.168.10.40:8899/x36xhzz.m3u8
"""

import argparse
import plistlib
import re
import socket
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ADB = r"C:/Users/Administrator/.workbuddy/toolchain/android-sdk/platform-tools/adb.exe"
SERIAL = "192.168.10.205:5555"


# ─────────────────────────── 电视侧 ───────────────────────────

def adb(*args, timeout=15):
    p = subprocess.run([ADB, "-s", SERIAL, *args],
                       capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def key(code, times=1, gap=0.18):
    for _ in range(times):
        adb("shell", "input", "keyevent", str(code))
        time.sleep(gap)


def _music_block(lines):
    """返回 dumpsys audio 中 STREAM_MUSIC 段落的后续行（没有则空列表）。"""
    for i, line in enumerate(lines):
        if "STREAM_MUSIC:" in line:
            return lines[i + 1:min(i + 10, len(lines))]
    return []


def read_volume():
    """读 STREAM_MUSIC 在**当前输出设备**上的音量（从 dumpsys，不是 settings 提供器）。

    实测 Android 9 电视的格式（多设备并列，必须按 Devices 行挑）：
      Current: 2 (speaker): 0, 4 (headset): 10, ..., 40000000 (default): 25
      Devices: speaker
    """
    out = adb("shell", "dumpsys", "audio", timeout=25)
    lines = out.splitlines()
    block = _music_block(lines)

    device = "speaker"
    for s in block:
        s = s.strip()
        if s.startswith("Devices:"):
            # 可能有多个设备（逗号分隔），取第一个
            device = s.split(":", 1)[1].strip().split(",")[0].strip()

    for s in block:
        s = s.strip()
        if s.startswith("Current:"):
            body = s.split(":", 1)[1]
            pairs = dict(
                (name.strip(), int(val))
                for name, val in re.findall(r"\(([^)]+)\):\s*(-?\d+)", body)
            )
            if device in pairs:
                return pairs[device]
            # 设备名对不上时退回 default，再不行取第一个
            if "default" in pairs:
                return pairs["default"]
            if pairs:
                return next(iter(pairs.values()))
    return None


def read_max():
    out = adb("shell", "dumpsys", "audio", timeout=25)
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if "STREAM_MUSIC:" in line:
            for j in range(i + 1, min(i + 8, len(lines))):
                s = lines[j].strip()
                if s.startswith("Max:"):
                    try:
                        return int(s.split(":")[1].strip())
                    except Exception:
                        return None
    return None


# ─────────────────────────── 发送端 ───────────────────────────

def http(method, path, ip, port, body=b"", timeout=5.0):
    s = socket.create_connection((ip, port), timeout=timeout)
    s.sendall(
        f"{method} {path} HTTP/1.1\r\nHost: {ip}:{port}\r\n"
        f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode() + body
    )
    buf = b""
    try:
        while True:
            c = s.recv(65536)
            if not c:
                break
            buf += c
    except socket.timeout:
        pass
    s.close()
    head = buf.split(b"\r\n\r\n", 1)[0].decode("utf-8", "ignore")
    return head.splitlines()[0] if head else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.10.205")
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--start-volume", type=int, default=5, help="投屏前把电视音量设为多少")
    ap.add_argument("--media", default="http://192.168.10.40:8899/x36xhzz.m3u8")
    args = ap.parse_args()

    print("连接电视…")
    print(adb("connect", SERIAL).strip() or "（已连接）")

    maxv = read_max()
    print(f"电视音量量程：0..{maxv}\n")

    print(f"把电视音量复位到 {args.start_volume}（先按到底，再往上加）…")
    key(25, 25)          # KEYCODE_VOLUME_DOWN
    key(24, args.start_volume)   # KEYCODE_VOLUME_UP
    time.sleep(0.6)
    before = read_volume()
    print(f"① 投屏前电视音量 = {before}/{maxv}\n")

    body = plistlib.dumps(
        {"Content-Location": args.media, "Start-Position": 0.0},
        fmt=plistlib.FMT_BINARY,
    )
    print("POST /play →", http("POST", "/play", args.ip, args.port, body))
    time.sleep(2.0)

    steps = [1.0, 0.5, 0.0]
    labels = {1.0: "② 发送端拉满 volume=1.0", 0.5: "③ 发送端减半 volume=0.5",
              0.0: "④ 发送端静音 volume=0.0"}
    rows = []
    for v in steps:
        http("GET", f"/volume?volume={v}", args.ip, args.port)
        time.sleep(0.8)
        got = read_volume()
        rows.append((v, got))
        print(f"{labels[v]} → 电视音量 = {got}/{maxv}")

    http("GET", "/stop", args.ip, args.port)
    print("\n──────── 判定 ────────")
    ok = True
    if before is None or maxv is None:
        print("✘ 读不到电视音量，无法判定")
        return 1

    v_full = rows[0][1]
    if v_full is not None and v_full <= before:
        print(f"✔ 拉满不超过投屏前（{v_full} <= {before}）—— 不会被吓到")
    else:
        print(f"✘ 拉满仍然顶上去了（{v_full} > {before}）")
        ok = False

    v_half = rows[1][1]
    expect_half = round(before / 2)
    if v_half is not None and abs(v_half - expect_half) <= 1:
        print(f"✔ 减半按比例生效（{v_half} ≈ {expect_half}）")
    else:
        print(f"✘ 减半没按比例走（得到 {v_half}，期望约 {expect_half}）")
        ok = False

    v_zero = rows[2][1]
    if v_zero == 0:
        print("✔ 静音生效（0）")
    else:
        print(f"✘ 静音没生效（{v_zero}）")
        ok = False

    print("\n结论：" + ("音量闸门工作正常" if ok else "音量闸门未生效，需继续排查"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
