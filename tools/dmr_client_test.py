#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DLNA DMR 控制端测试客户端
-------------------------
模拟一个真实的 DLNA 发送端（DMC），对 CastHub 接收端发起完整的投屏流程，
用于验证接收端的 device.xml / SCPD / SOAP 控制 / 状态上报是否正确。

典型用法（配合 adb forward 测试模拟器或真机）：

    # 把设备上的 DMR HTTP 端口映射到主机
    adb forward tcp:38080 tcp:39200

    # 跑测试
    python dmr_client_test.py --host 127.0.0.1 --port 38080

    # 指定测试媒体（默认用一段公开 HLS 测试流）
    python dmr_client_test.py --media https://example.com/test.m3u8
"""

import argparse
import re
import socket
import sys
import time
import urllib.error
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

DEFAULT_MEDIA = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
DEFAULT_TITLE = "CastHub 测试视频"

NS_AVT = "urn:schemas-upnp-org:service:AVTransport:1"
NS_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
NS_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"


class Result:
    def __init__(self):
        self.passed = []
        self.failed = []

    def ok(self, name, detail=""):
        self.passed.append(name)
        print(f"  [PASS] {name}" + (f" — {detail}" if detail else ""))

    def fail(self, name, detail=""):
        self.failed.append(name)
        print(f"  [FAIL] {name}" + (f" — {detail}" if detail else ""))

    def summary(self):
        total = len(self.passed) + len(self.failed)
        print()
        print("=" * 62)
        print(f"测试结果：{len(self.passed)}/{total} 通过")
        if self.failed:
            print("失败项：")
            for f in self.failed:
                print(f"  - {f}")
        print("=" * 62)
        return 0 if not self.failed else 1


def http_get(url, timeout=6):
    req = urllib.request.Request(url, headers={"User-Agent": "CastHub-Test/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8", "ignore"), dict(resp.headers)


def soap_post(url, service, action, args, timeout=10):
    body = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\n'
        "<s:Body>\n"
        f'<u:{action} xmlns:u="{service}">\n'
        + "".join(f"<{k}>{escape(v)}</{k}>\n" for k, v in args.items())
        + f"</u:{action}>\n"
        "</s:Body>\n"
        "</s:Envelope>"
    ).encode("utf-8")

    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{service}#{action}"',
            "User-Agent": "CastHub-Test/1.0 UPnP/1.0",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", "ignore")
            return resp.status, text
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "ignore")


def escape(value):
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def parse_outputs(soap_text):
    """从 SOAP 响应里取出参。"""
    result = {}
    for m in re.finditer(r"<(\w+)>(.*?)</\1>", soap_text, re.S):
        key, value = m.group(1), m.group(2)
        if key in ("Envelope", "Body"):
            continue
        result[key] = (
            value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", '"')
            .replace("&amp;", "&")
        )
    return result


def build_didl(media_url, title):
    return (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        '<item id="0" parentID="-1" restricted="1">'
        f"<dc:title>{title}</dc:title>"
        "<upnp:class>object.item.videoItem</upnp:class>"
        f'<res protocolInfo="http-get:*:video/mp4:*">{escape(media_url)}</res>'
        "</item></DIDL-Lite>"
    )


def main():
    ap = argparse.ArgumentParser(description="DLNA DMR 控制端测试")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True, help="DMR HTTP 端口")
    ap.add_argument("--media", default=DEFAULT_MEDIA, help="测试媒体 URL")
    ap.add_argument("--title", default=DEFAULT_TITLE)
    ap.add_argument("--poll", type=int, default=5, help="进度轮询次数")
    args = ap.parse_args()

    base = f"http://{args.host}:{args.port}"
    r = Result()

    print(f"目标：{base}")
    print("-" * 62)

    # ── 1. 连通性 ──
    print("[1] 连通性")
    try:
        socket.create_connection((args.host, args.port), timeout=5).close()
        r.ok("TCP 端口可达", f"{args.host}:{args.port}")
    except Exception as e:
        r.fail("TCP 端口可达", str(e))
        print("\n端口不可达，请确认：")
        print("  1. 接收端 App 已启动且模块处于「运行中」")
        print("  2. adb forward tcp:38080 tcp:<DMR端口> 已执行")
        return r.summary()

    # ── 2. 设备描述 ──
    print("\n[2] 设备描述 device.xml")
    device_xml = ""
    try:
        status, device_xml, _ = http_get(f"{base}/device.xml")
        if status == 200:
            r.ok("GET /device.xml 返回 200")
        else:
            r.fail("GET /device.xml", f"HTTP {status}")

        name = re.search(r"<friendlyName>(.*?)</friendlyName>", device_xml, re.S)
        if name:
            r.ok("friendlyName 存在", name.group(1).strip())
        else:
            r.fail("friendlyName 存在")

        udn = re.search(r"<UDN>(.*?)</UDN>", device_xml, re.S)
        if udn:
            r.ok("UDN 存在", udn.group(1).strip()[:48])
        else:
            r.fail("UDN 存在")

        if "MediaRenderer:1" in device_xml:
            r.ok("deviceType 为 MediaRenderer:1")
        else:
            r.fail("deviceType 为 MediaRenderer:1", "缺失或类型错误")

        if "AVTransport" in device_xml and "ConnectionManager" in device_xml:
            r.ok("服务列表含 AVTransport / ConnectionManager")
        else:
            r.fail("服务列表完整")
    except Exception as e:
        r.fail("GET /device.xml", str(e))
        return r.summary()

    # 解析控制地址
    control_url = None
    for block in re.findall(r"<service>(.*?)</service>", device_xml, re.S):
        if "AVTransport" in block:
            m = re.search(r"<controlURL>(.*?)</controlURL>", block, re.S)
            if m:
                path = m.group(1).strip()
                control_url = path if path.startswith("http") else f"{base}{path}"
    if control_url:
        r.ok("解析出 AVTransport 控制地址", control_url)
    else:
        r.fail("解析 AVTransport 控制地址")
        return r.summary()

    # ── 3. SCPD ──
    print("\n[3] 服务描述 SCPD")
    for path, keyword in [
        ("/avtransport.xml", "SetAVTransportURI"),
        ("/renderingcontrol.xml", "SetVolume"),
        ("/connectionmanager.xml", "GetProtocolInfo"),
    ]:
        try:
            status, text, _ = http_get(f"{base}{path}")
            if status == 200 and keyword in text:
                r.ok(f"GET {path}", f"含 {keyword}")
            else:
                r.fail(f"GET {path}", f"HTTP {status}，关键字 {keyword} {'缺失' if keyword not in text else '存在'}")
        except Exception as e:
            r.fail(f"GET {path}", str(e))

    # ── 4. GetProtocolInfo（决定发送端是否愿意推流） ──
    print("\n[4] ConnectionManager:GetProtocolInfo")
    status, text = soap_post(f"{base}/control/ConnectionManager", NS_CM, "GetProtocolInfo", {})
    if status == 200:
        sinks = parse_outputs(text).get("Sink", "")
        count = len([s for s in sinks.split(",") if s.strip()])
        if count > 0:
            r.ok("Sink 能力列表非空", f"{count} 项")
            for must in ["video/mp4", "mpegurl", "video/*"]:
                if must in sinks:
                    r.ok(f"声明支持 {must}")
                else:
                    r.fail(f"声明支持 {must}", "缺失可能导致发送端不推流")
        else:
            r.fail("Sink 能力列表非空", "为空会让发送端拒绝推流")
    else:
        r.fail("GetProtocolInfo", f"HTTP {status}")

    # ── 5. SetAVTransportURI + Play ──
    print("\n[5] 投屏流程")
    didl = build_didl(args.media, args.title)
    status, text = soap_post(
        control_url, NS_AVT, "SetAVTransportURI",
        {"InstanceID": "0", "CurrentURI": args.media, "CurrentURIMetaData": didl},
    )
    if status == 200:
        r.ok("SetAVTransportURI", f"标题={args.title}")
    else:
        r.fail("SetAVTransportURI", f"HTTP {status}: {text[:200]}")

    status, text = soap_post(control_url, NS_AVT, "Play", {"InstanceID": "0", "Speed": "1"})
    if status == 200:
        r.ok("Play")
    else:
        r.fail("Play", f"HTTP {status}: {text[:200]}")

    # ── 6. 状态与进度上报 ──
    print(f"\n[6] 状态轮询（{args.poll} 次，间隔 1s）")
    states = []
    for i in range(args.poll):
        time.sleep(1)
        status, text = soap_post(control_url, NS_AVT, "GetTransportInfo", {"InstanceID": "0"})
        if status != 200:
            r.fail("GetTransportInfo", f"HTTP {status}")
            break
        out = parse_outputs(text)
        state = out.get("CurrentTransportState", "?")
        states.append(state)

        status2, text2 = soap_post(control_url, NS_AVT, "GetPositionInfo", {"InstanceID": "0"})
        pos = parse_outputs(text2).get("RelTime", "?") if status2 == 200 else "?"
        print(f"    #{i + 1}  state={state:<18} position={pos}")

    if states:
        if any(s in ("PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK") for s in states):
            r.ok("播放状态机进入活动态", "/".join(sorted(set(states))))
        elif all(s == "STOPPED" for s in states):
            r.fail("播放状态机进入活动态", "始终 STOPPED，播放器可能未启动或拉流失败")
        else:
            r.ok("状态机有响应", "/".join(sorted(set(states))))

    # ── 7. RenderingControl ──
    print("\n[7] RenderingControl")
    status, text = soap_post(f"{base}/control/RenderingControl", NS_RC, "GetVolume",
                             {"InstanceID": "0", "Channel": "Master"})
    if status == 200:
        r.ok("GetVolume", f"CurrentVolume={parse_outputs(text).get('CurrentVolume')}")
    else:
        r.fail("GetVolume", f"HTTP {status}")

    # ── 8. 停止 ──
    print("\n[8] 收尾")
    status, _ = soap_post(control_url, NS_AVT, "Stop", {"InstanceID": "0"})
    if status == 200:
        r.ok("Stop")
    else:
        r.fail("Stop", f"HTTP {status}")

    return r.summary()


if __name__ == "__main__":
    sys.exit(main())
