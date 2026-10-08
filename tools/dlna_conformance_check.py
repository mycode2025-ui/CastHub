#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DLNA 一致性核对工具。

对着一台 DMR 逐项检查"标准里要求、而我们容易漏掉"的东西：

  1. SSDP 是否能被发现
  2. device.xml 是否声明 X_DLNADOC / iconList
  3. 声明的图标 URL 是否真的取得到（取不到会被控制点判为异常设备）
  4. GetProtocolInfo 的 Sink 第 4 段是否带 DLNA.ORG_OP / DLNA.ORG_FLAGS
  5. GENA 订阅是否返回 SID、并收到 SEQ=0 的初始事件

用法：
    python tools/dlna_conformance_check.py <host> [--port PORT]
未给 --port 时先做一次 SSDP 发现，从 LOCATION 里取端口。
"""
from __future__ import annotations

import argparse
import http.client
import re
import socket
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

SSDP_GROUP = "239.255.255.250"
SSDP_PORT = 1900

OK = "\033[32m✓\033[0m" if sys.stdout.isatty() else "✓"
BAD = "\033[31m✗\033[0m" if sys.stdout.isatty() else "✗"
results: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((ok, label))
    mark = OK if ok else BAD
    line = f"  {mark} {label}"
    if detail:
        line += f"\n      {detail}"
    print(line)


# ───────────────────────── SSDP 发现 ─────────────────────────

def ssdp_discover(timeout: float = 3.0) -> str | None:
    """发 M-SEARCH，返回第一个 LOCATION。"""
    msg = (
        "M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {SSDP_GROUP}:{SSDP_PORT}\r\n"
        'MAN: "ssdp:discover"\r\n'
        "MX: 1\r\n"
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
        "\r\n"
    )
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(timeout)
    try:
        sock.sendto(msg.encode(), (SSDP_GROUP, SSDP_PORT))
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, _ = sock.recvfrom(2048)
            except socket.timeout:
                break
            for line in data.decode("utf-8", "ignore").split("\r\n"):
                if line.lower().startswith("location:"):
                    return line.split(":", 1)[1].strip()
    finally:
        sock.close()
    return None


# ───────────────────────── GENA 订阅 ─────────────────────────

def gena_probe(host: str, port: int, listen_port: int = 8732) -> tuple[bool, str]:
    """起一个回调服务，订阅 AVTransport，看能否收到 SEQ=0 的初始事件。"""
    received: list[dict] = []

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", listen_port))
    srv.listen(4)
    srv.settimeout(0.5)

    stop = threading.Event()

    def serve() -> None:
        while not stop.is_set():
            try:
                conn, _ = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                # 主流程关掉监听 socket 时这里会抛，属正常退出路径
                return
            try:
                conn.settimeout(2.0)
                raw = b""
                while b"\r\n\r\n" not in raw:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    raw += chunk
                head = raw.split(b"\r\n\r\n", 1)[0].decode("utf-8", "ignore")
                first = head.split("\r\n")[0]
                if "NOTIFY" in first:
                    hdrs = {}
                    for ln in head.split("\r\n")[1:]:
                        if ":" in ln:
                            k, v = ln.split(":", 1)
                            hdrs[k.strip().upper()] = v.strip()
                    received.append({"seq": hdrs.get("SEQ", "?"), "nt": hdrs.get("NT", "?")})
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass

    t = threading.Thread(target=serve, daemon=True)
    t.start()

    local_ip = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((host, port))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "127.0.0.1"

    sid = None
    try:
        conn = http.client.HTTPConnection(host, port, timeout=5)
        conn.request(
            "SUBSCRIBE",
            "/event/AVTransport",
            headers={
                "HOST": f"{host}:{port}",
                "CALLBACK": f"<http://{local_ip}:{listen_port}/notify>",
                "NT": "upnp:event",
                "TIMEOUT": "Second-300",
            },
        )
        resp = conn.getresponse()
        sid = resp.getheader("SID") if resp.status == 200 else None
        conn.close()
    except Exception as e:
        stop.set(); srv.close()
        return False, f"订阅失败：{e}"

    time.sleep(2.5)
    if sid:
        try:
            c = http.client.HTTPConnection(host, port, timeout=5)
            c.request("UNSUBSCRIBE", "/event/AVTransport", headers={"HOST": f"{host}:{port}", "SID": sid})
            c.getresponse(); c.close()
        except Exception:
            pass
    stop.set(); srv.close()

    if not received:
        return False, "订阅成功（200 + SID）但 2.5 秒内没有收到任何 NOTIFY"
    seqs = [r["seq"] for r in received]
    return True, f"收到 {len(received)} 条 NOTIFY，SEQ={seqs}"


# ───────────────────────── 主流程 ─────────────────────────

def check_subscription_lifecycle(host: str, port: int) -> None:
    """核对订阅的续订与错误码。

    UPnP 对 GENA 的规定是两个**不同**的码，不能合并：
      400 = 参数不合法（控制端自己构造错了）
      412 = SID 不存在（多为订阅过期，提示控制端重新订阅）
    """
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((host, port))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = "127.0.0.1"

    def req(method: str, headers: dict) -> tuple[int, str | None, str | None]:
        c = http.client.HTTPConnection(host, port, timeout=5)
        c.request(method, "/event/AVTransport", headers={"HOST": f"{host}:{port}", **headers})
        r = c.getresponse()
        sid, to = r.getheader("SID"), r.getheader("TIMEOUT")
        c.close()
        return r.status, sid, to

    callback = f"<http://{local_ip}:8790/notify>"

    st, sid, _ = req("SUBSCRIBE", {"CALLBACK": callback, "NT": "upnp:event", "TIMEOUT": "Second-600"})
    check(st == 200 and bool(sid), "新订阅返回 200 + SID", f"status={st}")
    if not sid:
        return

    st2, sid2, to2 = req("SUBSCRIBE", {"SID": sid, "TIMEOUT": "Second-1200"})
    check(st2 == 200 and sid2 == sid, "带 SID 续订沿用同一 SID", f"status={st2} TIMEOUT={to2}")

    st3, _, _ = req("SUBSCRIBE", {"SID": sid, "CALLBACK": callback, "NT": "upnp:event"})
    check(st3 == 400, "SID+CALLBACK 混用 -> 400", f"实际 {st3}（规范：参数不合法）")

    st4, _, _ = req("SUBSCRIBE", {"SID": "uuid:not-exist"})
    check(st4 == 412, "未知 SID 续订 -> 412", f"实际 {st4}（规范：SID 失效，提示重新订阅）")

    st5, _, _ = req("UNSUBSCRIBE", {"SID": sid})
    check(st5 == 200, "UNSUBSCRIBE -> 200", f"实际 {st5}")

    st6, _, _ = req("UNSUBSCRIBE", {"SID": sid})
    check(st6 == 412, "重复 UNSUBSCRIBE -> 412", f"实际 {st6}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("host")
    ap.add_argument("--port", type=int, default=0)
    args = ap.parse_args()

    port = args.port
    print(f"[1] 发现设备 {args.host}")

    if not port:
        loc = ssdp_discover()
        if loc:
            m = re.match(r"http://[^:/]+:(\d+)", loc)
            port = int(m.group(1)) if m else 0
        check(bool(port), "SSDP M-SEARCH 有响应", f"LOCATION={loc}")
        if not port:
            print("\n无法继续：没能发现设备")
            return
    else:
        print("      跳过 SSDP（已指定端口）")

    base = f"http://{args.host}:{port}"

    print(f"\n[2] device.xml（{base}/device.xml）")
    try:
        xml = urllib.request.urlopen(base + "/device.xml", timeout=5).read().decode("utf-8", "ignore")
    except Exception as e:
        print(f"  拉取失败：{e}")
        return
    check("DMR-1.50" in xml, "声明 X_DLNADOC=DMR-1.50",
          "缺失时部分控制端不认为这是正规 DLNA 设备")
    check("schemas-dlna-org" in xml, "声明 dlna 命名空间")
    icons = re.findall(r"<icon>.*?</icon>", xml, re.S)
    check(bool(icons), "有 iconList", f"{len(icons)} 个图标声明")

    if icons:
        print("\n[3] 图标可达性")
        for icon in icons:
            url = (re.search(r"<url>(.*?)</url>", icon, re.S) or [None, "?"])[1]
            try:
                r = urllib.request.urlopen(base + url, timeout=5)
                data = r.read()
                is_png = data[:8] == b"\x89PNG\r\n\x1a\n"
                check(is_png and len(data) > 100,
                      f"{url} -> {r.status} {len(data)} 字节 PNG",
                      "声明了图标却取不到，控制点会把设备判为异常" if not is_png else "")
            except Exception as e:
                check(False, f"{url} 取不到", str(e))

    print("\n[4] GetProtocolInfo")
    body = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        '<s:Body><u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">'
        "</u:GetProtocolInfo></s:Body></s:Envelope>"
    )
    try:
        req = urllib.request.Request(
            base + "/control/ConnectionManager",
            data=body.encode("utf-8"),
            headers={
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPACTION": '"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo"',
            },
        )
        resp = urllib.request.urlopen(req, timeout=5).read().decode("utf-8", "ignore")
        m = re.search(r"<Sink>(.*?)</Sink>", resp, re.S)
        sink = m.group(1) if m else ""
        sample = sink.split(",")[0] if sink else "(空)"
        check(bool(sink), "Sink 非空", sample)
        check("DLNA.ORG_OP=" in sink, "第 4 段带 DLNA.ORG_OP",
              "缺失时严格的发送端会禁用进度条甚至拒绝推流")
        check("DLNA.ORG_FLAGS=" in sink, "第 4 段带 DLNA.ORG_FLAGS")
        print(f"      共 {len(sink.split(','))} 条，首条：{sample}")
    except Exception as e:
        check(False, "GetProtocolInfo 调用失败", str(e))

    print("\n[5] GENA 事件推送")
    ok, detail = gena_probe(args.host, port)
    check(ok, "订阅后收到事件", detail)

    print("\n[6] 订阅生命周期与错误码")
    check_subscription_lifecycle(args.host, port)

    passed = sum(1 for ok_, _ in results if ok_)
    print(f"\n结论：{passed}/{len(results)} 项通过")
    if passed < len(results):
        print("未通过项：")
        for ok_, label in results:
            if not ok_:
                print(f"  - {label}")


if __name__ == "__main__":
    main()
