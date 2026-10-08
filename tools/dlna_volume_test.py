#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 DLNA 控制端复现"手机端调音量 → 电视端静音"
----------------------------------------------
用户报告：在手机端调节音量大小，电视端直接静音。
本脚本扮演发送端（夸克/爱奇艺那类），走真实 SOAP 动作序列：

  1. 起播（SetAVTransportURI + Play）
  2. 手机音量条**往下调到底** → SetVolume(0)（部分发送端同时发 SetMute(1)）
  3. 手机音量条**再往上调** → SetVolume(40)
  4. 检查电视是否恢复有声：音量 > 0 且不处于静音

这正是"调音量把电视弄哑"的最可能路径：第 2 步把上限/音量压到 0，
第 3 步若恢复不回来，就是缺陷。

    python tools/dlna_volume_test.py --port 43989
"""

import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

ADB = r"C:/Users/Administrator/.workbuddy/toolchain/android-sdk/platform-tools/adb.exe"
SERIAL = os.environ.get("CASTHUB_SERIAL", "192.168.10.205:5555")
HOST = SERIAL.split(":")[0]
MEDIA = "http://192.168.10.40:8899/x36xhzz.m3u8"

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


def music_state():
    ensure_adb()
    out = adb("shell", "dumpsys audio", timeout=30)
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if "STREAM_MUSIC:" not in line:
            continue
        muted = vol = maxv = None
        for j in range(i + 1, min(i + 10, len(lines))):
            s = lines[j].strip()
            if s.startswith("Muted:"):
                muted = "true" in s.lower()
            elif s.startswith("Max:") and maxv is None:
                m = re.search(r"(\d+)", s)
                maxv = int(m.group(1)) if m else None
            elif s.startswith("Current:") and vol is None:
                # 各版本格式不同，逐个试：
                #   Android 9:  "Current: 3 (speaker): 5, 40000 (default): 2"
                #   Android 6.0:"Current: 2 (speaker): 4, 40000000 (default): 16"
                # 取第一个 (xxx): N 形式的值；括号内无数字时退回冒号后的第一个整数
                nums = re.findall(r"\((?:[^()]*)\):\s*(\d+)", s)
                if nums:
                    vol = int(nums[0])
                else:
                    m = re.search(r":\s*(\d+)", s.split("(", 1)[-1])
                    vol = int(m.group(1)) if m else None
            elif s.startswith("Devices:"):
                break
        return vol, bool(muted), maxv
    return None, None, None


def soap(port, service, action, args):
    body = ['<?xml version="1.0" encoding="utf-8"?>',
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>',
            f'<u:{action} xmlns:u="urn:schemas-upnp-org:service:{service}:1">']
    for k, v in args.items():
        body.append(f"<{k}>{v}</{k}>")
    body.append(f"</u:{action}></s:Body></s:Envelope>")
    data = "".join(body).encode("utf-8")
    url = f"http://{HOST}:{port}/control/{service}"
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION", f'"urn:schemas-upnp-org:service:{service}:1#{action}"')
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:
        return f"ERR {e}"


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 43989
    ensure_adb()
    print(f"目标 {HOST}:{port}")

    didl = (f'<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            f'xmlns:dc="http://purl.org/dc/elements/1.1/" '
            f'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            f'<item id="0" parentID="-1" restricted="1">'
            f'<dc:title>音量复现测试</dc:title>'
            f'<upnp:class>object.item.videoItem</upnp:class>'
            f'<res protocolInfo="http-get:*:video/mp4:*">{MEDIA}</res>'
            f'</item></DIDL-Lite>')
    print("SetAVTransportURI →", soap(port, "AVTransport", "SetAVTransportURI",
                                {"InstanceID": "0", "CurrentURI": MEDIA,
                                 "CurrentURIMetaData": didl}))
    print("Play →", soap(port, "AVTransport", "Play", {"InstanceID": "0", "Speed": "1"}))
    time.sleep(3)

    vol0, muted0, maxv0 = music_state()
    print(f"\n起播后：音量 {vol0}/{maxv0}，静音={muted0}\n")

    print("—— 手机音量条往下调到底 ——")
    print("SetVolume(0) →", soap(port, "RenderingControl", "SetVolume",
                             {"InstanceID": "0", "Channel": "Master", "DesiredVolume": "0"}))
    time.sleep(1.5)
    vol_low, muted_low, _ = music_state()
    print(f"   电视：音量 {vol_low}，静音={muted_low}")

    print("\n—— 手机音量条往回调 ——")
    print("SetVolume(40) →", soap(port, "RenderingControl", "SetVolume",
                              {"InstanceID": "0", "Channel": "Master", "DesiredVolume": "40"}))
    time.sleep(1.5)
    vol_up, muted_up, _ = music_state()
    print(f"   电视：音量 {vol_up}，静音={muted_up}")

    judge(vol_up is not None and vol_up > 0,
          f"✔ 调回音量后电视恢复有声（{vol_up}）",
          f"✘ 调回音量后电视仍是 {vol_up} —— 这就是'调音量把电视弄哑'")
    judge(not muted_up, "✔ 电视不处于静音", f"✘ 电视处于静音状态（音量 {vol_up}）")

    # 场景：发送端先静音、再发音量。
    # 真实发送端（夸克等）在用户按"音量放大"时就是这么发的 ——
    # 所以这里期望的正是"音量指令能把静音顶掉"，而不是尊重静音。
    print("\n—— 发送端先静音、再发音量（应恢复有声）——")
    print("SetMute(1) →", soap(port, "RenderingControl", "SetMute",
                            {"InstanceID": "0", "Channel": "Master", "DesiredMute": "1"}))
    time.sleep(0.5)
    vol_m, muted_m, _ = music_state()
    print(f"   静音后：音量 {vol_m}，静音={muted_m}")
    print("SetVolume(60) →", soap(port, "RenderingControl", "SetVolume",
                              {"InstanceID": "0", "Channel": "Master", "DesiredVolume": "60"}))
    time.sleep(1.5)
    vol_n, muted_n, _ = music_state()
    print(f"   发音量后：音量 {vol_n}，静音={muted_n}")
    judge((not muted_n) and (vol_n or 0) > 0,
          f"✔ 静音后发音量指令即恢复有声（音量 {vol_n}）—— 这正是手机按音量放大的场景",
          f"✘ 静音后发音量指令仍是静音（音量 {vol_n}，静音={muted_n}）"
          "　这就是用户报告的'手机上音量在放大、电视是哑的'")

    print("\nSetMute(0) →", soap(port, "RenderingControl", "SetMute",
                             {"InstanceID": "0", "Channel": "Master", "DesiredMute": "0"}))
    time.sleep(1.0)
    vol_f, muted_f, _ = music_state()
    print(f"恢复后：音量 {vol_f}，静音={muted_f}")

    soap(port, "AVTransport", "Stop", {"InstanceID": "0"})
    print("\n结论：" + ("音量恢复正常" if ok else "音量链路有问题"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
