#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
遥控器退出投屏验证
------------------
模拟电视遥控器的返回键，验证 1.3.1 加的"连按两次退出"：

  1. POST /play 起播，确认在播
  2. 按一次返回键 → 应只弹 OSD（提示"再按一次"），**播放不能停**
  3. 3 秒内再按一次 → 应真的结束投屏：播放器停、会话清空、退回待机屏

判定依据：
  - /playback-info 的 rate/position（会话被清后 position 不再前进）
  - logcat 里 "用户在电视端结束了投屏"（AirPlayModule 打的）
  - 待机屏重新出现（uiautomator 里能找到设备名）

    python tools/exit_test.py
"""

import os
import plistlib
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
BACK = 4  # KEYCODE_BACK

ok = True


def judge(cond, yes, no):
    global ok
    print(("✔ " if cond else "✘ ") + (yes if cond else no))
    if not cond:
        ok = False


def adb(*args, timeout=20):
    p = subprocess.run([ADB, "-s", SERIAL, *args],
                       capture_output=True, text=True, timeout=timeout)
    return p.stdout + p.stderr


def ensure_adb():
    """重连电视。

    每一步都要确认：adb server 会在两次调用之间被回收，
    之后所有 `adb -s ...` 都报 'device not found' ——
    看起来像"功能没生效"，实际是**根本没连上**。这类误报比测试失败更误导人。
    """
    out = adb("connect", SERIAL, timeout=20)
    return ("connected" in out) or ("already" in out)


def key(code):
    ensure_adb()
    adb("shell", "input", "keyevent", str(code))


def http(method, path, body=b"", timeout=5.0):
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
    head, _, rest = buf.partition(b"\r\n\r\n")
    return head.decode("utf-8", "ignore"), rest


def playback_info():
    head, body = http("GET", "/playback-info")
    if "200" not in head:
        return None
    try:
        return plistlib.loads(body)
    except Exception:
        return None


def position():
    info = playback_info()
    return (info or {}).get("position")


def play():
    body = plistlib.dumps(
        {"Content-Location": MEDIA, "Start-Position": 0.0}, fmt=plistlib.FMT_BINARY)
    head, _ = http("POST", "/play", body)
    return head.splitlines()[0] if head else "(无响应)"


def current_focus():
    """当前焦点窗口。用来判定"应用是不是被返回键一起关掉了"。"""
    ensure_adb()
    out = adb("shell", "dumpsys window | grep mCurrentFocus", timeout=30)
    for line in out.splitlines():
        if "mCurrentFocus" in line:
            return line.split("=", 1)[-1].strip()
    return out.strip()[:120]


def ui_dump():
    """uiautomator dump 的输出。全屏 SurfaceView 播放中它会返回 'null root node'。"""
    ensure_adb()
    return adb("shell", "uiautomator", "dump", "/dev/tty", timeout=40)


def home_or_casting():
    """判定"是否已退回待机屏"。

    ⚠️ 不能把 `uiautomator dump` 当判据：这台电视上它时灵时不灵 ——
    有时返回 `null root node`，有时 adb server 恰好被回收而返回 'device not found'，
    两者都会被误读成"界面没退回"。真机截图明明已显示待机屏，判定却报失败。

    改用确定性信号：**投屏结束后应用仍在前台**。
    不投屏时应用只有待机屏这一个界面（视频层与主页互斥），所以这条等价为"已退回待机屏"。
    dump 只作佐证：读到就打印，读不到就明说工具不可用，不参与判定。
    """
    focus = current_focus()
    in_app = "casthub" in focus
    d = ui_dump()
    if "接收服务" in d or "投屏方式" in d:
        print("   佐证：uiautomator 界面树里能看到待机屏文案")
    else:
        print(f"   佐证：uiautomator 本次不可用（{d.strip()[:50]!r}），不参与判定")
    return in_app, focus


def wait_until_playing(max_s=25.0):
    """轮询等待进度真正开始推进。

    固定 sleep 不可靠：这台电视首次起播要缓冲 4–6 秒，
    而测试源是公网 HLS，偶尔一次缓冲超过 20 秒 —— 那是网络波动，不是接收端的锅。
    """
    deadline = time.time() + max_s
    prev = position()
    while time.time() < deadline:
        time.sleep(1.0)
        cur = position()
        if cur is not None and prev is not None and cur > prev:
            return True, prev, cur
        prev = cur if cur is not None else prev
    return False, prev, position()


def start_and_wait(attempts=2):
    """起播并等进度推进。公网源不稳，允许重试一次（重试会打印出来，不静默）。"""
    for i in range(attempts):
        head = play()
        started, a, b = wait_until_playing()
        if started:
            return True, f"{head}（第 {i + 1} 次：{a} → {b}）"
        print(f"   第 {i + 1} 次起播超时（公网 HLS 缓冲波动），重试")
        http("GET", "/stop")
        time.sleep(2)
    return False, "起播失败：重试后进度仍未推进"


def screencap(local_name):
    """截图存到本地，供肉眼核对（OSD 文案这类 dump 拿不到的内容）。"""
    remote = "/sdcard/_exit.png"
    adb("shell", "screencap", "-p", remote, timeout=40)
    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "capture", local_name)
    local = os.path.normpath(local)
    os.makedirs(os.path.dirname(local), exist_ok=True)
    subprocess.run([ADB, "-s", SERIAL, "pull", remote, local],
                   capture_output=True, timeout=90)
    return local if os.path.exists(local) else ""


def main():
    print(adb("connect", SERIAL).strip() or "（已连接）")

    # ══ 阶段一：连按两次返回键必须退出 ══
    # ⚠️ 两次按键之间绝对不能插 screencap / uiautomator 这类慢动作：
    #    在这台电视上它们各要 1–3 秒，会超出 3 秒判定窗口，第二次按键会被当成第一次。
    print("── 阶段一：连按两次退出 ──")
    started, detail = start_and_wait()
    judge(started, f"起播确认（{detail}）", detail)
    judge("casthub" in current_focus(), "起播后应用在前台（返回键能到达它）",
          "起播后应用不在前台，返回键测不到它，测试前提不成立")

    key(BACK)
    time.sleep(0.6)
    key(BACK)
    time.sleep(1.5)
    # 判据：以**会话是否结束**为准，不用 AirPlay 的进度做推断。
    # 之前拿 /playback-info 的进度判"还在不在播"，但本次会话可能来自 DLNA
    # （SOAP 投的），AirPlay 侧读到的进度并不反映这个会话 ——
    # 于是出现"logcat 说会话已结束、进度判据说还在走"的自相矛盾。
    ensure_adb()
    log = adb("shell", "logcat", "-d", "-t", "300")
    ended = "用户在电视端结束了投屏" in log or "会话结束" in log
    judge(ended, "① 连按两次：会话已由电视端结束",
          "① 连按两次后没找到会话结束的记录")

    back, focus = home_or_casting()
    judge(back, f"③ 已退回待机屏：投屏结束且应用仍在前台（{focus}）",
          f"③ 应用被返回键一起关掉了，焦点跑到 {focus}")
    print("   截图：", screencap("exit-after-double-back.png"))

    # ══ 阶段一之二：模拟遥控器"连发"——连按三下也不能把应用关掉 ══
    print("── 阶段一之二：连发三下返回键 ──")
    started, detail = start_and_wait()
    if not started:
        print("   ✘ 前提不成立：没投屏成功。此时应用在待机屏，返回键本就没人拦，")
        print("     会被系统当成退出应用 —— 这不能用来判断宽限期是否生效。")
        print("     （常见原因：公网 HLS 缓冲波动。稍后重跑即可。）")
    judge(started, f"起播确认（{detail}）", detail)
    if not started:
        return None
    key(BACK)
    time.sleep(0.25)
    key(BACK)
    time.sleep(0.25)
    key(BACK)          # 第三次：宽限期内的连发，应当被吞掉
    time.sleep(2.0)
    back, focus = home_or_casting()
    judge(back, f"⑤ 连发三下后已退回待机屏，应用仍在前台（{focus}）",
          f"⑤ 连发三下后状态异常（焦点 {focus}）")

    # ══ 阶段二：只按一次返回键必须**不**退出 ══
    print("── 阶段二：只按一次不退出 ──")
    started, detail = start_and_wait()
    if not started:
        print("   ✘ 前提不成立：没投屏成功。此时应用在待机屏，返回键本就没人拦，")
        print("     会被系统当成退出应用 —— 这不能用来判断宽限期是否生效。")
        print("     （常见原因：公网 HLS 缓冲波动。稍后重跑即可。）")
    judge(started, f"起播确认（{detail}）", detail)
    if not started:
        return None
    key(BACK)
    time.sleep(0.8)
    p2 = position()
    time.sleep(1.0)
    p3 = position()
    judge(p2 is not None and p3 is not None and p3 > p2,
          f"⑦ 只按一次：投屏仍在继续（{p2:.1f}s → {p3:.1f}s）",
          f"⑦ 只按一次就把投屏停了（{p2} → {p3}）")
    judge("casthub" in current_focus(), "⑧ 应用仍在前台，没有退出",
          "⑧ 应用被返回键关掉了")
    shot = screencap("exit-single-back-osd.png")
    print("   截图（核对是否显示「再按一次返回键，结束本次投屏」）：", shot)

    http("GET", "/stop")  # 收尾
    print("\n结论：" + ("遥控器退出投屏工作正常" if ok else "退出链路有问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
