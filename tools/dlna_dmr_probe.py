#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DLNA / UPnP-AV DMR 探针 (DLNA DMR Probe)
----------------------------------------
用途：在局域网内伪装成一个标准 DLNA 媒体渲染器(DMR)，用于分析「夸克网盘 / 百度网盘 /
爱奇艺 / 腾讯视频」等 App 投屏时到底走什么协议、推什么 URL、带什么元数据。

无需任何第三方依赖，Python 3.8+ 标准库即可运行。

用法：
    python dlna_dmr_probe.py                 # 自动探测本机 IP，设备名默认 DLNA-Probe
    python dlna_dmr_probe.py --name 当贝盒子  # 自定义设备名
    python dlna_dmr_probe.py --port 8200     # 自定义 HTTP 端口

然后在手机上打开夸克网盘 -> 播放视频 -> 点投屏，选择本设备。
控制台会打印全部 SSDP 发现报文与 SOAP 控制报文，并落盘到 dlna_probe.log。

按 Ctrl+C 退出。
"""

import argparse
import re
import socket
import struct
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

SSDP_GROUP = "239.255.255.250"
SSDP_PORT = 1900

LOG_FILE = "dlna_probe.log"
_log_lock = threading.Lock()


def log(msg: str) -> None:
    with _log_lock:
        print(msg, flush=True)
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(msg + "\n")
        except Exception:
            pass


def banner(msg: str) -> None:
    log("=" * 72)
    log(msg)
    log("=" * 72)


def get_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


# --------------------------------------------------------------------------
# SSDP: 设备发现层（注意：DLNA 用 SSDP/UDP 1900，不是 mDNS/5353）
# --------------------------------------------------------------------------
class SsdpServer(threading.Thread):
    def __init__(self, local_ip: str, http_port: int, device_name: str, udn: str):
        super().__init__(daemon=True)
        self.local_ip = local_ip
        self.http_port = http_port
        self.device_name = device_name
        self.udn = udn
        self.location = f"http://{local_ip}:{http_port}/device.xml"
        self.st_targets = [
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1",
            "ssdp:all",
        ]
        self._stop = threading.Event()

    def _notify(self, st: str, sock: socket.socket) -> None:
        if st == "upnp:rootdevice":
            usn = f"{self.udn}::upnp:rootdevice"
        elif st.startswith("uuid:"):
            usn = self.udn
        else:
            usn = f"{self.udn}::{st}"
        msg = (
            "NOTIFY * HTTP/1.1\r\n"
            f"HOST: {SSDP_GROUP}:{SSDP_PORT}\r\n"
            "CACHE-CONTROL: max-age=1800\r\n"
            f"LOCATION: {self.location}\r\n"
            f"NT: {st}\r\n"
            "NTS: ssdp:alive\r\n"
            f"SERVER: {self.device_name}/1.0 UPnP/1.0 DLNADOC/1.50\r\n"
            f"USN: {usn}\r\n"
            "\r\n"
        )
        try:
            sock.sendto(msg.encode("utf-8"), (SSDP_GROUP, SSDP_PORT))
        except Exception as e:
            log(f"[SSDP] notify failed: {e}")

    def run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("", SSDP_PORT))
        except OSError as e:
            log(f"[SSDP] bind 1900 failed: {e} (端口被占用？请先关闭其他 DLNA 服务)")
            return
        mreq = struct.pack("4sl", socket.inet_aton(SSDP_GROUP), socket.INADDR_ANY)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        sock.settimeout(1.0)

        # 主动广播 alive
        for st in self.st_targets + [self.udn]:
            self._notify(st, sock)
        threading.Thread(target=self._alive_loop, args=(sock,), daemon=True).start()

        log(f"[SSDP] listening on {SSDP_GROUP}:{SSDP_PORT}")
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except Exception:
                break
            text = data.decode("utf-8", "ignore")
            if "M-SEARCH" not in text.upper():
                continue
            st = ""
            for line in text.split("\r\n"):
                if line.upper().startswith("ST:"):
                    st = line.split(":", 1)[1].strip()
            mx = 1
            m = re.search(r"MX:\s*(\d+)", text, re.I)
            if m:
                mx = int(m.group(1))
            log(f"[SSDP] M-SEARCH from {addr[0]}:{addr[1]}  ST={st or '(none)'}")
            if st and st not in self.st_targets and st != self.udn:
                continue
            time.sleep(min(mx, 2) / 2.0)
            if st == "upnp:rootdevice":
                usn = f"{self.udn}::upnp:rootdevice"
            elif st == self.udn:
                usn = self.udn
            else:
                usn = f"{self.udn}::{st}" if st else self.udn
            resp = (
                "HTTP/1.1 200 OK\r\n"
                "CACHE-CONTROL: max-age=1800\r\n"
                "EXT:\r\n"
                f"LOCATION: {self.location}\r\n"
                f"ST: {st or 'ssdp:all'}\r\n"
                f"USN: {usn}\r\n"
                f"SERVER: {self.device_name}/1.0 UPnP/1.0 DLNADOC/1.50\r\n"
                "\r\n"
            )
            try:
                sock.sendto(resp.encode("utf-8"), addr)
            except Exception as e:
                log(f"[SSDP] reply failed: {e}")

    def _alive_loop(self, sock: socket.socket) -> None:
        # 60 秒重发一次 ssdp:alive。很多 App（含乐播 SDK 的 DLNA 通道）
        # 是被动监听 NOTIFY 建列表的，广播太稀疏会被错过 —— 和 mDNS 一个道理。
        while not self._stop.is_set():
            time.sleep(60)
            for st in self.st_targets + [self.udn]:
                self._notify(st, sock)
            log("[SSDP] 已重发 ssdp:alive 广播")

    def stop(self) -> None:
        self._stop.set()


# --------------------------------------------------------------------------
# XML: 设备描述 + SCPD
# --------------------------------------------------------------------------
def device_xml(name: str, udn: str) -> bytes:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>{name}</friendlyName>
    <manufacturer>Probe</manufacturer>
    <modelName>DLNA Probe</modelName>
    <UDN>{udn}</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/avtransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/renderingcontrol.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/connectionmanager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>""".encode("utf-8")


SCPD_AVT = b"""<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Play</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Pause</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Stop</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>Seek</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
      <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetPositionInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
      <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
      <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
      <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
      <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
      <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetTransportInfo</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
      <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
      <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

SCPD_RC = b"""<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>SetVolume</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetMute</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>SetMute</name><argumentList>
      <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
      <argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>Volume</name><dataType>ui2</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>Mute</name><dataType>boolean</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

SCPD_CM = b"""<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name><argumentList>
      <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
      <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetCurrentConnectionIDs</name><argumentList>
      <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetCurrentConnectionInfo</name><argumentList>
      <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
      <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""

# Sink 声明得越全，夸克等 App 越愿意把流推过来
SINK_PROTOCOL_INFO = ",".join(
    [
        "http-get:*:video/mp4:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:video/x-msvideo:*",
        "http-get:*:video/x-flv:*",
        "http-get:*:video/mpeg:*",
        "http-get:*:video/x-mpegURL:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegURL:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:video/*:*",
        "http-get:*:audio/*:*",
        "http-get:*:*/*:*",
    ]
)


# --------------------------------------------------------------------------
# HTTP: 描述下载 + SOAP 控制 + GENA 事件订阅
# --------------------------------------------------------------------------
class State:
    def __init__(self):
        self.uri = ""
        self.meta = ""
        self.title = ""
        self.transport_state = "STOPPED"
        self.volume = 50
        self.mute = 0
        self.start_wall = 0.0
        self.position = 0


STATE = State()


def human_time(sec: float) -> str:
    sec = int(max(sec, 0))
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h:02d}:{m:02d}:{s:02d}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静默默认日志
        pass

    def _send(self, code: int, body: bytes, ctype: str = "text/xml; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if extra:
            for k, v in extra.items():
                self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass

    # ---------------- GET: 描述文档 ----------------
    def do_GET(self):
        path = self.path.split("?")[0]
        udn = self.server.udn
        name = self.server.device_name
        if path == "/device.xml":
            log(f"[HTTP ] GET /device.xml  from {self.client_address[0]}")
            self._send(200, device_xml(name, udn))
        elif path == "/avtransport.xml":
            self._send(200, SCPD_AVT)
        elif path == "/renderingcontrol.xml":
            self._send(200, SCPD_RC)
        elif path == "/connectionmanager.xml":
            self._send(200, SCPD_CM)
        else:
            self._send(404, b"not found")

    # ---------------- GENA: 事件订阅 ----------------
    def do_SUBSCRIBE(self):
        sid = "uuid:" + str(uuid.uuid4())
        timeout = self.headers.get("TIMEOUT", "Second-1800")
        log(f"[GENA ] SUBSCRIBE {self.path} from {self.client_address[0]} -> {sid}")
        self._send(200, b"", "text/plain", {"SID": sid, "TIMEOUT": timeout})

    def do_UNSUBSCRIBE(self):
        log(f"[GENA ] UNSUBSCRIBE {self.path}")
        self._send(200, b"")

    def do_NOTIFY(self):
        self._send(200, b"")

    # ---------------- SOAP: 控制 ----------------
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        body = raw.decode("utf-8", "ignore")
        soap_action = self.headers.get("SOAPACTION", "")
        m = re.search(r'#(\w+)"', soap_action)
        action = m.group(1) if m else "Unknown"
        svc_m = re.search(r"service:(\w+):1", soap_action)
        service = svc_m.group(1) if svc_m else "AVTransport"

        log("")
        log(f"[SOAP ] {service}:{action}  <- {self.client_address[0]}")
        log(f"[UA   ] {self.headers.get('User-Agent', '-')}")

        args = dict(re.findall(r"<(\w+)>(.*?)</\1>", body, re.S))

        if action == "SetAVTransportURI":
            STATE.uri = _unescape(args.get("CurrentURI", ""))
            STATE.meta = _unescape(args.get("CurrentURIMetaData", ""))
            t = re.search(r"<dc:title>(.*?)</dc:title>", STATE.meta, re.S)
            STATE.title = _unescape(t.group(1)) if t else ""
            banner("收到投屏请求 SetAVTransportURI")
            log(f"  标题 : {STATE.title or '(无)'}")
            log(f"  URL  : {STATE.uri}")
            if STATE.meta:
                log("  元数据(DIDL-Lite):")
                for line in _pretty_xml(STATE.meta).splitlines():
                    log("    " + line)
                pi = re.search(r'protocolInfo="([^"]*)"', STATE.meta)
                if pi:
                    log(f"  protocolInfo : {pi.group(1)}")
                dur = re.search(r'duration="([^"]*)"', STATE.meta)
                if dur:
                    log(f"  duration     : {dur.group(1)}")
            _guess_and_log(STATE.uri)
            STATE.transport_state = "STOPPED"
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "Play":
            STATE.transport_state = "PLAYING"
            STATE.start_wall = time.time()
            banner("Play —— 发送端要求开始播放")
            log(f"  播放地址: {STATE.uri}")
            log("  注意: 流不会经过控制端，DMR 需自己用这个 URL 去拉流（注意鉴权/防盗链）")
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "Pause":
            STATE.position += time.time() - STATE.start_wall
            STATE.transport_state = "PAUSED_PLAYBACK"
            log("  -> 暂停")
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "Stop":
            STATE.transport_state = "STOPPED"
            STATE.position = 0
            log("  -> 停止")
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "Seek":
            target = args.get("Target", "")
            log(f"  -> 跳转 Target={target} Unit={args.get('Unit','')}")
            if target.startswith("0:") or ":" in target:
                try:
                    h, mm, ss = [float(x) for x in target.split(":")]
                    STATE.position = h * 3600 + mm * 60 + ss
                    STATE.start_wall = time.time()
                except Exception:
                    pass
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "GetPositionInfo":
            pos = STATE.position + (
                time.time() - STATE.start_wall if STATE.transport_state == "PLAYING" else 0
            )
            self._send(
                200,
                soap_resp(
                    service,
                    action,
                    {
                        "Track": "1",
                        "TrackDuration": "00:00:00",
                        "TrackMetaData": STATE.meta.replace("&", "&amp;").replace("<", "&lt;")
                        if STATE.meta
                        else "",
                        "TrackURI": STATE.uri,
                        "RelTime": human_time(pos),
                        "AbsTime": human_time(pos),
                        "RelCount": "2147483647",
                        "AbsCount": "2147483647",
                    },
                ),
            )
            return

        if action == "GetTransportInfo":
            self._send(
                200,
                soap_resp(
                    service,
                    action,
                    {
                        "CurrentTransportState": STATE.transport_state,
                        "CurrentTransportStatus": "OK",
                        "CurrentSpeed": "1",
                    },
                ),
            )
            return

        if action == "GetProtocolInfo":
            log(f"  -> 返回 Sink 支持列表（{len(SINK_PROTOCOL_INFO.split(','))} 项）")
            self._send(
                200,
                soap_resp(service, action, {"Source": "", "Sink": SINK_PROTOCOL_INFO}),
            )
            return

        if action == "GetVolume":
            self._send(200, soap_resp(service, action, {"CurrentVolume": str(STATE.volume)}))
            return

        if action == "SetVolume":
            STATE.volume = args.get("DesiredVolume", STATE.volume)
            log(f"  -> 设置音量 {STATE.volume}")
            self._send(200, soap_resp(service, action, {}))
            return

        if action == "GetMute":
            self._send(200, soap_resp(service, action, {"CurrentMute": str(STATE.mute)}))
            return

        if action == "SetMute":
            STATE.mute = args.get("DesiredMute", "0")
            self._send(200, soap_resp(service, action, {}))
            return

        if action in ("GetCurrentConnectionIDs", "GetCurrentConnectionInfo"):
            self._send(200, soap_resp(service, action, {"ConnectionIDs": "0", "RcsID": "0"}))
            return

        log(f"  [!] 未处理的 action: {action}")
        log("  body: " + body[:800])
        self._send(200, soap_resp(service, action, {}))


def _unescape(s: str) -> str:
    return (
        s.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", '"')
        .replace("&apos;", "'")
    )


def _pretty_xml(s: str) -> str:
    s = re.sub(r"><", ">\n<", s.strip())
    return s


def _guess_and_log(url: str) -> None:
    if not url:
        return
    log("  --- URL 解析 ---")
    low = url.lower()
    if ".m3u8" in low:
        log("  类型: HLS (m3u8) —— 注意 m3u8 内部的分片 URL 也可能带鉴权参数")
    elif ".mpd" in low:
        log("  类型: DASH (mpd)")
    elif ".mp4" in low:
        log("  类型: MP4 渐进式下载")
    elif ".mkv" in low:
        log("  类型: Matroska (MKV) —— ExoPlayer 支持，但封装兼容性需实测")
    elif ".flv" in low:
        log("  类型: FLV")
    else:
        log("  类型: 未知，需看 Content-Type")
    if url.startswith("https"):
        log("  传输: HTTPS（Android 6.0 最高支持 TLS 1.2，注意证书链与 SNI）")
    for key in ("token", "sign", "auth_key", "expires", "oss", "signature", "st"):
        if re.search(rf"[?&]{key}=", low):
            log(f"  鉴权: URL 携带 `{key}` 参数 -> 有有效期，DMR 需立即播放，不可缓存复用")
            break
    log("  建议: 用 curl -I 或浏览器直接打开验证是否需要 Referer / User-Agent")


def soap_resp(service: str, action: str, args: dict) -> bytes:
    body = "".join(f"<{k}>{v}</{k}>" for k, v in args.items())
    return (
        '<?xml version="1.0" encoding="utf-8"?>\r\n'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">\r\n'
        "<s:Body>\r\n"
        f'<u:{action}Response xmlns:u="urn:schemas-upnp-org:service:{service}:1">\r\n'
        f"{body}\r\n"
        f"</u:{action}Response>\r\n"
        "</s:Body>\r\n"
        "</s:Envelope>\r\n"
    ).encode("utf-8")


# --------------------------------------------------------------------------
def main() -> None:
    ap = argparse.ArgumentParser(description="DLNA DMR 协议探针")
    ap.add_argument("--name", default="DLNA-Probe", help="设备名（手机端看到的名称）")
    ap.add_argument("--port", type=int, default=8200, help="HTTP 端口")
    ap.add_argument("--ip", default=None, help="本机 IP（默认自动探测）")
    args = ap.parse_args()

    ip = args.ip or get_local_ip()
    udn = "uuid:" + str(uuid.uuid5(uuid.NAMESPACE_URL, f"dlna-probe-{args.name}"))
    try:
        open(LOG_FILE, "w", encoding="utf-8").close()
    except Exception:
        pass

    ssdp = SsdpServer(ip, args.port, args.name, udn)
    ssdp.start()

    httpd = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    httpd.udn = udn
    httpd.device_name = args.name

    banner("DLNA / UPnP-AV DMR 探针已启动")
    log(f"  设备名   : {args.name}")
    log(f"  本机 IP  : {ip}")
    log(f"  描述地址 : http://{ip}:{args.port}/device.xml")
    log(f"  日志     : {LOG_FILE}")
    log("")
    log("现在请在手机上打开 夸克网盘 -> 播放任意视频 -> 点『投屏』-> 选择本设备")
    log("本探针会打印：SSDP 发现报文 / SetAVTransportURI 的 URL 与 DIDL-Lite 元数据")
    log("按 Ctrl+C 退出")
    log("")

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("\n退出")
    finally:
        ssdp.stop()
        httpd.shutdown()


if __name__ == "__main__":
    main()
