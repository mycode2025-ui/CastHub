#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
投屏音量"越调越静音"专项验证
----------------------------
用户报告：手机端把音量放大，电视端却一直静音。两条独立成因，都要验：

1. **音量上限被跟到 0** —— 用户/异常把系统音量压到 0 后，闸门把它当成
   "用户手动调小"跟随为新上限，于是 `比例 × 0 = 0`，手机音量条在动、
   电视永远无声。验证：把音量压到 0，再发满音量指令，应恢复到原上限而非 0。

2. **系统静音标志没被清掉** —— Android 的静音是独立于音量值的标志位，
   `setStreamVolume` 不清它。被静音后手机把音量拉满依然无声。
   验证：先让电视进入静音，再发满音量指令，应自动解除静音。

3. **反向保护**：发送端明确要求静音时（DLNA `SetMute(1)` 紧接着发音量），
   不能把它的静音顶掉。这一条需要 SOAP 动作，由 `dlna_client_test.py` 覆盖。

    python tools/volume_mute_test.py
"""

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
TV_IP = "192.168.10.205"
PORT = 7000
MEDIA = "http://192.168.10.40:8899/x36xhzz.m3u8"
VOL_UP, VOL_DOWN, MUTE = 24, 25, 164  # KEYCODE_VOLUME_UP / DOWN / MUTE

ok = True


def judge(cond, yes, no):
    global ok
    print(("✔ " if cond else "✘ ") + (yes if cond else no))
    if not cond:
        ok = False


def adb(*args, timeout=30):
    p = subprocess.run([ADB, "-s", SERIAL, *args],
                       capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def ensure_adb():
    out = adb("connect", SERIAL, timeout=20)
    return ("connected" in out) or ("already" in out)


def key(code, times=1, gap=0.18):
    for _ in range(times):
        ensure_adb()
        adb("shell", "input", "keyevent", str(code))
        time.sleep(gap)


def music_state():
    """返回 (当前音量, 是否静音, 量程)。只认 STREAM_MUSIC 那一段。"""
    ensure_adb()
    out = adb("shell", "dumpsys audio", timeout=30)
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if "STREAM_MUSIC:" in line:
            vol = muted = maxv = None
            for j in range(i + 1, min(i + 8, len(lines))):
                s = lines[j].strip()
                if s.startswith("Muted:"):
                    muted = "true" in s.lower()
                elif s.startswith("Max:") and maxv is None:
                    m = re.search(r"(\d+)", s)
                    maxv = int(m.group(1)) if m else None
                elif s.startswith("Current:") and vol is None:
                    part = s.split("(", 1)[1].split(")")[0] if "(" in s else ""
                    m = re.search(r":\s*(\d+)", part)
                    vol = int(m.group(1)) if m else None
            return vol, bool(muted), maxv
    return None, None, None


def http(method, path, body=b"", timeout=6.0):
    s = socket.create_connection((TV_IP, PORT), timeout=timeout)
    s.sendall(
        f"{method} {path} HTTP/1.1\r\nHost: {TV_IP}:{PORT}\r\n"
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
    return buf


def send_volume(v):
    http("GET", f"/volume?volume={v}")
    time.sleep(1.0)


def play():
    body = plistlib.dumps({"Content-Location": MEDIA, "Start-Position": 0.0},
                          fmt=plistlib.FMT_BINARY)
    http("POST", "/play", body)
    # 等进度真正推进，确保处于投屏态（音量闸门只在投屏期间生效）
    deadline = time.time() + 25
    while time.time() < deadline:
        time.sleep(1)
        try:
            info = plistlib.loads(http("GET", "/playback-info").split(b"\r\n\r\n", 1)[1])
            if (info.get("position") or 0) > 0.2:
                return True
        except Exception:
            pass
    return False


def main():
    ensure_adb()
    print("连接电视\n")

    key(VOL_DOWN, 25)
    key(VOL_UP, 8)
    time.sleep(0.5)
    vol, muted, maxv = music_state()
    print(f"起始：音量 {vol}/{maxv}，静音={muted}")
    judge(vol and vol > 0, "起始音量已就位", "读不到系统音量，无法判定")

    print("\nPOST /play →", "起播" if play() else "**起播失败**（公网 HLS 波动，可重跑）")

    # ── 用例 1：上限不得塌到 0 ──
    key(VOL_DOWN, 25)          # 把音量压到 0，模拟"用户/异常把音量清零"
    time.sleep(0.8)
    vol0, muted0, _ = music_state()
    send_volume(1.0)
    vol1, muted1, _ = music_state()
    judge(vol1 is not None and vol1 > 0,
          f"① 音量为 0 时收到满音量指令 → 恢复到 {vol1}（上限没塌成 0）",
          f"① 音量为 0 时收到满音量指令 → 仍是 {vol1}，上限被跟成了 0（电视会一直哑）")

    # ── 用例 2：静音后调大音量要能解除静音 ──
    key(VOL_UP, 8)
    time.sleep(0.5)
    key(MUTE)                  # 遥控器静音键
    time.sleep(1.2)
    vol2, muted2, _ = music_state()
    judge(bool(muted2), f"② 已让电视进入静音（音量 {vol2}，静音={muted2}）",
          f"② 没能让电视静音（音量 {vol2}，静音={muted2}），本条跳过")
    if muted2:
        send_volume(1.0)
        vol3, muted3, _ = music_state()
        judge((not muted3) and (vol3 or 0) > 0,
              f"③ 静音状态下手机调大音量 → 自动解除静音（音量 {vol3}）",
              f"③ 静音状态下手机调大音量 → 仍处于静音（音量 {vol3}，静音={muted3}）"
              "　这就是用户报告的'手机在放大、电视是哑的'")

    http("GET", "/stop")
    time.sleep(1)
    vol_end, muted_end, _ = music_state()
    print(f"\n收尾：音量 {vol_end}，静音={muted_end}")
    if muted_end:
        key(MUTE)
        print("已解除静音")

    print("\n结论：" + ("音量/静音链路正常" if ok else "仍存在问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
