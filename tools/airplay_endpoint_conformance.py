#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirPlay 端点一致性与帧结构自测
------------------------------
为什么要专门测这个：

1. **帧结构**。响应头末尾必须有空行（`\\r\\n\\r\\n`）。少一个 \\r\\n 时，
   curl 这类宽松客户端仍能靠 Content-Length 猜出正文，但 iOS 的 CFNetwork
   会把正文当成头部继续解析，直接判响应无效 —— 而且失败是静默的。
   这里用 Python 的 http.client 发请求，它按 RFC 解析，帧不合法会直接抛异常。

2. **三处公告必须一致**。同一台设备在 mDNS TXT、/info、/server-info 三处
   描述自己，客户端会挑任意一处判定可用性。曾经出现过 TXT 说 srcvers=220.68、
   /info 却说 130.14 的自相矛盾。本脚本交叉核对它们。

  python airplay_endpoint_conformance.py --ip 192.168.10.205
"""

import argparse
import http.client
import plistlib
import socket
import struct
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

GROUP = "224.0.0.251"
PORT_MDNS = 5353

_passed = 0
_failed = 0


def check(name: str, ok: bool, detail: str = ""):
    global _passed, _failed
    if ok:
        _passed += 1
        print(f"  \033[32mPASS\033[0m {name}" + (f"  {detail}" if detail else ""))
    else:
        _failed += 1
        print(f"  \033[31mFAIL\033[0m {name}  {detail}")


# ─────────────────── mDNS 查询（取 TXT 用于交叉核对）───────────────────

def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def decode_name(data, off):
    labels, orig_end, guard = [], None, 0
    while guard < 128:
        guard += 1
        if off >= len(data):
            break
        l = data[off]
        if l == 0:
            off += 1
            break
        if l & 0xC0 == 0xC0:
            ptr = struct.unpack("!H", data[off:off + 2])[0] & 0x3FFF
            if orig_end is None:
                orig_end = off + 2
            off = ptr
            continue
        off += 1
        labels.append(data[off:off + l].decode("utf-8", "ignore"))
        off += l
    return ".".join(labels), (orig_end if orig_end is not None else off)


def parse_rrs(data, off, count):
    out = []
    for _ in range(count):
        try:
            name, off = decode_name(data, off)
            rtype, rclass, ttl = struct.unpack("!HHI", data[off:off + 8])
            off += 8
            (rdlen,) = struct.unpack("!H", data[off:off + 2])
            off += 2
            rdata_off = off
            rdata = data[off:off + rdlen]
            off += rdlen
            out.append((name, rtype, rclass, ttl, rdata, rdata_off))
        except Exception:
            break
    return out, off


def mdns_txt(target_ip: str, service="_airplay._tcp.local", timeout=4.0):
    """返回该 IP 宣告的 TXT 字典（取它对自己实例的回答）。"""
    q = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    q.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    q.bind(("", 0))
    q.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    q.settimeout(0.5)
    q.sendto(struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0)
             + encode_name(service) + struct.pack("!HH", 12, 0x8001), (GROUP, PORT_MDNS))

    end = time.time() + timeout
    txts = {}
    while time.time() < end:
        try:
            data, addr = q.recvfrom(9000)
        except socket.timeout:
            continue
        if addr[0] != target_ip or len(data) < 12:
            continue
        _qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
        if not flags & 0x8000:
            continue
        off = 12
        for _ in range(qd):
            _, off = decode_name(data, off)
            off += 4
        rrs, _ = parse_rrs(data, off, an + ns + ar)
        for name, rtype, _c, _t, rdata, _ro in rrs:
            if rtype != 16:
                continue
            d = {}
            i = 0
            while i < len(rdata):
                ln = rdata[i]
                if ln == 0:
                    break
                i += 1
                kv = rdata[i:i + ln].decode("utf-8", "ignore")
                i += ln
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    d[k] = v
            if d:
                txts[name] = d
    q.close()
    return txts


# ───────────────────────── HTTP ─────────────────────────

def raw_response(ip, port, method, path, body=None, headers=None, timeout=6):
    """用 http.client 发请求（它按 RFC 校验帧结构），返回 (状态, 头字典, 正文)。"""
    conn = http.client.HTTPConnection(ip, port, timeout=timeout)
    try:
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        return resp.status, dict(resp.getheaders()), data
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.10.205")
    ap.add_argument("--port", type=int, default=7000)
    args = ap.parse_args()
    ip, port = args.ip, args.port

    print(f"=== AirPlay 端点一致性自测 {ip}:{port} ===\n")

    print("[1] HTTP 帧结构与端点基本可用性")
    results = {}
    for path in ("/server-info", "/info", "/playback-info"):
        try:
            st, hdrs, body = raw_response(ip, port, "GET", path)
            results[path] = (st, hdrs, body)
            check(f"GET {path} 状态 200", st == 200, f"实际 {st}")
            cl = hdrs.get("Content-Length")
            check(f"GET {path} Content-Length 与正文一致",
                  cl is not None and int(cl) == len(body),
                  f"声明 {cl}，实收 {len(body)}")
            check(f"GET {path} 未把正文当头部吞掉（帧合法）", len(body) > 0, f"正文 {len(body)} 字节")
        except Exception as e:
            check(f"GET {path}", False, f"{type(e).__name__}: {e}")

    print("\n[2] plist 可解析性")
    info = {}
    si = {}
    if "/info" in results and results["/info"][2]:
        try:
            info = plistlib.loads(results["/info"][2])
            check("/info 是合法 plist", True, f"{len(info)} 个字段")
        except Exception as e:
            check("/info 是合法 plist", False, str(e))
    if "/server-info" in results and results["/server-info"][2]:
        try:
            si = plistlib.loads(results["/server-info"][2])
            check("/server-info 是合法 plist", True, f"{len(si)} 个字段")
        except Exception as e:
            check("/server-info 是合法 plist", False, str(e))
    if "/playback-info" in results and results["/playback-info"][2]:
        try:
            plistlib.loads(results["/playback-info"][2])
            check("/playback-info 是合法 plist", True)
        except Exception as e:
            check("/playback-info 是合法 plist", False, str(e))

    print("\n[3] 关键字段齐备")
    for k in ("deviceID", "macAddress", "name", "model", "features", "srcvers", "protovers", "vv"):
        check(f"/info 含 {k}", k in info, repr(info.get(k, "(缺)")))
    for k in ("deviceid", "features", "model", "name", "srcvers", "vv"):
        check(f"/server-info 含 {k}", k in si, repr(si.get(k, "(缺)")))

    print("\n[4] 三处公告必须一致（mDNS TXT / /info / /server-info）")
    txts = mdns_txt(ip)
    ours = None
    for name, d in txts.items():
        if "CastHub" in name or d.get("deviceid") == info.get("deviceID"):
            ours = d
            break
    if ours is None:
        check("取到本机在 mDNS 上的 TXT", False, f"收到 {list(txts)}")
    else:
        check("取到本机在 mDNS 上的 TXT", True, str(sorted(ours)))
        # 字段集必须与同网内两台真实可用接收端（乐播 SDK）完全一致。
        # 少字段会让客户端走进兼容性分支 —— 早期我们就是少了 pk/pi/rhd 而被面板忽略。
        REFERENCE_TXT_FIELDS = {
            "deviceid", "features", "srcvers", "flags",
            "vv", "model", "pw", "rhd", "pk", "pi",
        }
        missing = sorted(REFERENCE_TXT_FIELDS - set(ours))
        extra = sorted(set(ours) - REFERENCE_TXT_FIELDS)
        check("TXT 字段集与可用参照一致", not missing, f"缺={missing} 多={extra}")
        pairs = [
            ("deviceid", ours.get("deviceid"), info.get("deviceID")),
            ("deviceid", ours.get("deviceid"), si.get("deviceid")),
            ("model", ours.get("model"), info.get("model")),
            ("model", ours.get("model"), si.get("model")),
            ("srcvers", ours.get("srcvers"), info.get("srcvers")),
            ("srcvers", ours.get("srcvers"), si.get("srcvers")),
            ("pi", ours.get("pi"), info.get("pi")),
        ]
        for field, a, b in pairs:
            check(f"{field} 一致（TXT vs plist）", a is not None and a == b, f"TXT={a!r} plist={b!r}")
        # features：TXT 是 "低32位,高32位"，plist 是低 32 位整数
        txt_feat = (ours.get("features") or "").split(",")[0]
        try:
            check("features 一致（TXT 低 32 位 vs plist 整数）",
                  int(txt_feat, 16) == info.get("features"),
                  f"TXT={txt_feat} plist={info.get('features')}")
        except Exception as e:
            check("features 一致", False, str(e))
        # 66 位十六进制 pk
        pk_txt = ours.get("pk")
        pk_info = info.get("pk")
        check("pk 一致（TXT 十六进制 vs /info 二进制）",
              isinstance(pk_txt, str) and isinstance(pk_info, bytes)
              and pk_txt.lower() == pk_info.hex(),
              f"TXT={pk_txt!r:.20} /info={pk_info.hex()[:20] if isinstance(pk_info, bytes) else pk_info!r}")

    print("\n[5] 错误处理")
    st, _, _ = raw_response(ip, port, "POST", "/play")
    check("/play 无地址时回 400", st == 400, f"实际 {st}")
    for path in ("/pair-setup", "/pair-verify"):
        st, _, _ = raw_response(ip, port, "POST", path, body=b"\x00")
        check(f"{path} 明确回 404（未实现配对）", st == 404, f"实际 {st}")

    print(f"\n=== 通过 {_passed}，失败 {_failed} ===")
    return 0 if _failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
