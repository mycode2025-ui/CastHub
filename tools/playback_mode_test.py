#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
播放模式验证（单曲播放 / 单曲循环）
-----------------------------------
只验证接收端能可靠实现的两种模式。顺序播放、列表循环**故意不实现** ——
接收端拿不到播放列表（手机投一集只发一个 URL，列表在手机 App 里），
真机日志实测会停在"无下一条"。详见 core/PlaybackQueue.kt 的注释。

验证手法：把媒体 seek 到临近结尾，等它自然播完，看：
- 单曲循环：应回到 0 重新播
- 单曲播放：应停在末尾（会话保留）

模式通过 **root 直接改 SharedPreferences** 设置，不走 UI 自动化：
电视上 uiautomator 在全屏播放态下只能 dump 出约 2.8KB 的空树，
节点属性顺序也不保证，坐标点击会静默失败并被误读成"功能没实现"。

    python tools/playback_mode_test.py [初始DMR端口]
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
MEDIA = os.environ.get("CASTHUB_TEST_MEDIA",
                       "http://192.168.10.40:8899/x36xhzz.m3u8")
PKG = "com.casthub.app"
PREFS = "/data/data/com.casthub.app/shared_prefs/casthub_modules.xml"


def adb(*args, timeout=40, retry=2):
    """执行一条 adb 命令，掉线时自动重连重试。

    不带重试的话，这台设备上大约每几条命令就会掉一次，
    表现为"测试莫名中断"，看起来像功能问题。
    """
    for attempt in range(retry + 1):
        subprocess.run([ADB, "connect", SERIAL], capture_output=True, timeout=25)
        p = subprocess.run([ADB, "-s", SERIAL, *args],
                           capture_output=True, text=True, timeout=timeout)
        out = p.stdout + p.stderr
        if "device not found" in out or "no devices/emulators" in out.lower():
            time.sleep(2)
            continue
        return out
    return out


def ensure_adb():
    """重连后再操作。

    这台电视的 adbd 极不稳定：一次 `connect` 成功，下一条 `shell` 就可能
    `device not found`（设备端 adbd 自己掉线/重启）。
    所以**每个 adb 调用前都要重连**，不能只在开头连一次。
    代价是慢一点，但能把"整条流程中途断开"变成"偶尔一次调用重试"。
    """
    out = adb("connect", SERIAL, timeout=20)
    return ("connected" in out) or ("already" in out)


def soap(port, service, action, args):
    body = ['<?xml version="1.0" encoding="utf-8"?>',
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>',
            f'<u:{action} xmlns:u="urn:schemas-upnp-org:service:{service}:1">']
    for k, v in args.items():
        body.append(f"<{k}>{v}</{k}>")
    body.append(f"</u:{action}></s:Body></s:Envelope>")
    req = urllib.request.Request(
        f"http://{HOST}:{port}/control/{service}",
        data="".join(body).encode("utf-8"), method="POST")
    req.add_header("Content-Type", 'text/xml; charset="utf-8"')
    req.add_header("SOAPACTION",
                   f'"urn:schemas-upnp-org:service:{service}:1#{action}"')
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.read().decode("utf-8", "ignore")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")
    except Exception as e:
        return None, str(e)


def didl(title):
    return (f'<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
            f'xmlns:dc="http://purl.org/dc/elements/1.1/" '
            f'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
            f'<item id="0" parentID="-1" restricted="1">'
            f'<dc:title>{title}</dc:title>'
            f'<upnp:class>object.item.videoItem</upnp:class>'
            f'<res protocolInfo="http-get:*:video/mp4:*">{MEDIA}</res>'
            f'</item></DIDL-Lite>')


def play(port, title):
    soap(port, "AVTransport", "SetAVTransportURI",
         {"InstanceID": "0", "CurrentURI": MEDIA, "CurrentURIMetaData": didl(title)})
    soap(port, "AVTransport", "Play", {"InstanceID": "0", "Speed": "1"})


def state(port):
    _, body = soap(port, "AVTransport", "GetTransportInfo", {"InstanceID": "0"})
    m = re.search(r"<CurrentTransportState>([^<]+)</CurrentTransportState>", body)
    return m.group(1) if m else "?"


def position(port):
    _, body = soap(port, "AVTransport", "GetPositionInfo", {"InstanceID": "0"})
    rel = re.search(r"<RelTime>([^<]+)</RelTime>", body)
    dur = re.search(r"<TrackDuration>([^<]+)</TrackDuration>", body)
    return (rel.group(1) if rel else "?"), (dur.group(1) if dur else "?")


def secs(t):
    m = re.match(r"(\d+):(\d+):(\d+)", t or "")
    if not m:
        return 0.0
    h, mi, s = (int(x) for x in m.groups())
    return h * 3600 + mi * 60 + s


def wait_duration(port, timeout=45):
    """等播放器解析出时长。

    没有时长就无从谈"播完"。上一轮测试栽在这里：
    媒体代理没起时 GetPositionInfo 全是 00:00:00，脚本却照样往下跑，
    得出的"失败"其实是"根本没测到"。**先确认前提，再断言。**
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        _, dur = position(port)
        if secs(dur) > 1:
            return dur
        time.sleep(2)
    return None


def _sh(*args, timeout=40):
    return adb("shell", *args, timeout=timeout)


def set_mode(mode):
    """用 root 直接改 SharedPreferences 设置播放模式，然后重启应用。

    为什么不用界面点击：
    这台电视的 uiautomator 在**全屏播放态**下只能 dump 出 2.8KB 空树，
    元素一个都没有；而模式又必须在"未投屏"时改（应用会拦投屏中切换）。
    于是每轮都要赌"此刻界面正好是可点的"，连丢十几次。
    直接改配置把 UI 这层变量彻底去掉，测的就是功能本身。
    """
    ensure_adb()
    adb("root")
    time.sleep(3)
    ensure_adb()

    # 先把应用停下 —— 运行中改偏好文件会被它退出时覆盖掉
    _sh("am", "force-stop", PKG)
    time.sleep(1.5)

    out = _sh("cat", PREFS)
    # 无条件删掉旧值再写新的：上一版留下的 SEQUENCE 在当前枚举里已不存在，
    # 应用启动时 fromName() 会把它回退成默认值，表现为"设置成功但没生效"。
    out = re.sub(r'\s*<string name="playback_mode">[^<]*</string>', "", out)
    xml = out.replace("</map>",
                      f'\n    <string name="playback_mode">{mode}</string>\n</map>')

    local = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_pm_local.xml")
    with open(local, "w", encoding="utf-8") as f:
        f.write(xml)
    env = dict(os.environ, MSYS_NO_PATHCONV="1")
    # 推 + 复制。用相对路径且关掉 Git Bash 的路径转换（否则 /data/... 会被改写成 C:/...）
    subprocess.run([ADB, "-s", SERIAL, "push", local, "/data/local/tmp/_pm.xml"],
                   capture_output=True, timeout=60, env=env)
    _sh("cp", "/data/local/tmp/_pm.xml", PREFS)
    # SharedPreferences 缓存的属主/权限要跟着对，否则应用读不到
    _sh("chown", "u0_a40:u0_a40", PREFS)
    _sh("chmod", "660", PREFS)

    _sh("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(8)
    return f">{mode}<" in _sh("cat", PREFS)


def discover_port(timeout=25):
    """找出**真正在响应**的 DMR 控制端口。

    ⚠️ 不要只从 logcat 里取"最后一条"：logcat 缓冲区里堆着历次启动的记录，
    脚本一旦挑到旧端口，SOAP 请求就打到已经不存在的服务上，
    症状是"模式设成功了、端口也读到了，但播放毫无反应"——
    看起来像功能坏了，其实是端口挑错。

    所以这里**逐个候选端口做一次 SOAP 自检**，只返回能应答的那个。
    这是唯一可靠的判定方式：能应答 = 服务在；应答不了 = 端口是死的。
    """
    ensure_adb()
    log = _sh("logcat", "-d", "-t", "1500")
    # 从新到旧排列候选端口
    cands = [int(m.group(1)) for m in
             re.finditer(r"DLNA 模块已启动：CastHub 投屏 @[\d.]+:(\d+)", log)]
    cands.reverse()
    for p in cands[:6]:
        if state(p) != "?":
            return p
    # 日志里一个都没有 → 重启应用再试
    _sh("am", "force-stop", PKG)
    time.sleep(1.5)
    _sh("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(9)
    log = _sh("logcat", "-d", "-t", "800")
    for m in re.finditer(r"DLNA 模块已启动：CastHub 投屏 @[\d.]+:(\d+)", log):
        p = int(m.group(1))
        if state(p) != "?":
            return p
    return None


def seek_near_end(port, remain=3):
    _, dur = position(port)
    target = max(0, int(secs(dur) - remain))
    soap(port, "AVTransport", "Seek",
         {"InstanceID": "0", "Unit": "REL_TIME", "Target": f"0:00:{target:02d}"})
    return target


def run_case(mode, expect_replay):
    print(f"\n—— 模式：{mode} ——")
    if not set_mode(mode):
        print("   ✘ 没能写入播放模式，**未验证**")
        return None
    # 取日志里最新的端口。DLNA 每次启动都是随机端口，
    # 用旧端口发 SOAP 会打到已经不存在的服务上 ——
    # 症状是"设置成功、端口也读到了，但播放毫无反应"。
    port = discover_port()
    if not port:
        print("   ✘ 没读到 DMR 端口，**未验证**")
        return None
    print(f"   ✔ 已设为 {mode}（DMR 端口 {port}）")

    # 连通性自检：GetTransportInfo 通了才继续，否则后面全是空断言
    st = state(port)
    if st == "?":
        print(f"   ✘ 端口 {port} 不响应 SOAP，请求打到了不存在的服务，**未验证**")
        return None
    print(f"   ✔ 端口 {port} 响应正常（state={st}）")

    play(port, f"测试-{mode}")
    dur = wait_duration(port)
    if not dur:
        print("   ✘ 前提不成立：媒体没起播（读不到时长）。"
              "先确认 http_proxy_server.py 在跑。")
        return None
    print(f"   ✔ 已起播，时长 {dur}")

    t = seek_near_end(port)
    print(f"   已 seek 到 {t}s，等它播完…")

    restarted = False
    trace = []
    for i in range(20):
        time.sleep(2)
        pos, _ = position(port)
        st = state(port)
        trace.append((f"t+{(i + 1) * 2}s", pos, st))
        # 单曲循环的判定：位置回到开头附近并重新开始走
        if expect_replay and secs(pos) < 5 and st in ("PLAYING", "TRANSITIONING"):
            restarted = True
            break
        if not expect_replay and secs(pos) >= secs(dur) - 1.5:
            break

    for tag, pos, st in trace[-6:]:
        print(f"      {tag:>7}  {pos}  {st}")

    if expect_replay:
        print("   ✔ 播完后回到开头重播（单曲循环生效）" if restarted
              else "   ✘ 播完没有重播")
        return restarted
    stopped = bool(trace) and secs(trace[-1][1]) >= secs(dur) - 1.5
    print("   ✔ 播完停在末尾、不重播（单曲播放，会话保留）" if stopped
          else "   ✘ 单曲模式下却动起来了")
    return stopped


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else "0"
    ensure_adb()
    print(f"目标 {HOST}")
    print(f"媒体 {MEDIA}")
    if arg.isdigit() and arg != "0":
        print(f"初始 DMR 端口 {arg}（重启后会变，脚本会自动重新获取）")

    r1 = run_case("REPEAT_ONE", expect_replay=True)
    r2 = run_case("SINGLE", expect_replay=False)

    print("\n" + "=" * 52)
    if r1 is None or r2 is None:
        print("存在未验证项 —— 不能认为功能可用")
        return 2
    print("两种模式均已验证" if (r1 and r2) else "有模式未通过")
    return 0 if (r1 and r2) else 1


if __name__ == "__main__":
    sys.exit(main())
