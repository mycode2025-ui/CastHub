#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
iOS 风格 /play 验证（地址放在 HTTP 请求头里）
---------------------------------------------
用户报告：iPhone 上哔哩哔哩点 AirPlay 设备后**没反应**。

根因：iOS 发 `/play` 时把播放地址放在**请求头** `Content-Location` 里，
**body 是空的**。早期实现 `parsePlayRequest` 只看 body，
`body.isEmpty()` 直接返回 null → 回 400 → iOS 静默放弃。
表现就是"点了没反应"，且日志只有一句看不出所以然的"未解析出播放地址"。

本脚本用原生 socket 精确模拟 iOS 的请求形态（空 body + 头部带地址），
验证服务器能否正确取出地址并起播。

    python tools/airplay_header_play_test.py [端口]
"""

import os
import socket
import sys
import time
import urllib.request
import urllib.error

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ADB = r"C:/Users/Administrator/.workbuddy/toolchain/android-sdk/platform-tools/adb.exe"
SERIAL = os.environ.get("CASTHUB_SERIAL", "192.168.10.205:5555")
HOST = SERIAL.split(":")[0]
MEDIA = os.environ.get("CASTHUB_TEST_MEDIA",
                       "http://192.168.10.40:8899/x36xhzz.m3u8")
DEFAULT_PORT = 7000


def adb(*args, timeout=40):
    p = __import__("subprocess").run([ADB, "-s", SERIAL, *args],
                                     capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def ensure_adb():
    out = adb("connect", SERIAL, timeout=20)
    return ("connected" in out) or ("already" in out)


def raw_request(port, method, path, headers=None, body=b"", timeout=8):
    """发一条原始 HTTP 请求，返回状态行与响应头。"""
    h = dict(headers or {})
    if body:
        h.setdefault("Content-Length", str(len(body)))
    req = f"{method} {path} HTTP/1.1\r\nHost: {HOST}:{port}\r\n"
    for k, v in h.items():
        req += f"{k}: {v}\r\n"
    req += "Connection: close\r\n\r\n"
    s = socket.create_connection((HOST, port), timeout=timeout)
    try:
        s.sendall(req.encode() + body)
        data = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            data += chunk
            if b"\r\n\r\n" in data and len(data) > 64:
                break
    finally:
        s.close()
    return data.decode("utf-8", "ignore")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT
    ensure_adb()
    print(f"目标 {HOST}:{port}（AirPlay 控制端口）")
    print(f"媒体 {MEDIA}\n")

    ok = True

    # ── 1. iOS 形态：地址在请求头，body 为空 ──
    print("—— ① iOS 形态：Content-Location 放请求头，body 为空 ——")
    resp = raw_request(port, "POST", "/play", {
        "Content-Location": MEDIA,
        "Start-Position": "0.000000",
        "User-Agent": "AirPlay/540.31",
    }, body=b"")
    status = resp.split("\r\n")[0] if resp else "(无响应)"
    print(f"   响应：{status}")
    if "200" in status:
        print("   ✔ 服务器接受了（说明已从请求头取到地址）")
    else:
        print("   ✘ 服务器拒绝了 —— 这正是'点了没反应'的原因")
        ok = False

    # ── 2. 等起播，查进度 ──
    time.sleep(9)
    prog = raw_request(port, "GET", "/playback-info")
    body_txt = prog.split("\r\n\r\n", 1)[-1] if "\r\n\r\n" in prog else ""
    moved = ("duration" in body_txt) and not body_txt.strip().endswith("</plist>")
    # 用应用侧日志做权威判定：有没有真的起播
    log = adb("logcat", "-d", "-t", "400")
    played = "/play http" in log or "▶ /play" in log
    if played:
        print("   ✔ 应用日志确认已收到播放地址并起播")
    else:
        print("   ✘ 应用日志里没有起播记录")
        ok = False

    # ── 3. 安卓形态（body plist）不能被这次改动弄坏 ──
    print("\n—— ② 兼容检查：body 为 plist 的老客户端仍应可用 ——")
    plist = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" '
        '"http://www.apple.com/DTDs/PropertyList-1.0.dtd">'
        '<plist version="1.0"><dict>'
        f'<key>Content-Location</key><string>{MEDIA}</string>'
        '<key>Start-Position</key><real>0</real>'
        '</dict></plist>'
    ).encode()
    resp2 = raw_request(port, "POST", "/play", {
        "Content-Type": "text/x-apple-plist+xml",
    }, body=plist)
    status2 = resp2.split("\r\n")[0] if resp2 else "(无响应)"
    print(f"   响应：{status2}")
    if "200" in status2:
        print("   ✔ 老客户端路径未被破坏")
    else:
        print("   ✘ 老客户端也挂了 —— 说明改动引入了回归")
        ok = False

    print("\n" + "=" * 50)
    print("iOS 形态 /play 已修复" if ok else "仍有未通过项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
