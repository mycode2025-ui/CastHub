#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirPlay 发现层「应答方式」对照测试
--------------------------------
iPhone 发 mDNS 查询的方式和普通 Linux/PC 工具**不一样**，这点决定了应答必须怎么发：

  * iPhone 的查询是**一条报文里多个问题**（实测：_bbcast-local-permission + _airplay + _raop）
  * iPhone 的查询**不带 QU 位**（QU 位 = 请求单播应答），即它要的是**组播应答**
  * 源端口是 5353（不是临时端口）

本脚本用完全相同的报文形态去打设备，并把应答按**收到它的 socket** 分类：
  * 应答出现在「临时端口 socket」 -> 单播回源
  * 应答出现在「5353 监听 socket」 -> 组播应答

同时对照真实设备（小米电视），看差在哪一步。

  python airplay_discovery_probe.py
"""

import socket
import struct
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

GROUP = "224.0.0.251"
PORT = 5353
CASTHUB = "192.168.10.205"
MITV = "192.168.10.214"

Q_BBCAST = "_bbcast-local-permission._tcp.local"
Q_AIRPLAY = "_airplay._tcp.local"
Q_RAOP = "_raop._tcp.local"


def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def build_query(names, qu: bool):
    qd = len(names)
    pkt = struct.pack("!HHHHHH", 0, 0, qd, 0, 0, 0)
    for n in names:
        pkt += encode_name(n) + struct.pack("!HH", 12, 0x8001 if qu else 0x0001)
    return pkt


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
            if off + 1 >= len(data):
                break
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


def summarize(data):
    """返回这个应答里服务相关的 (来源设备, 记录类型, 名字) 简表。"""
    if len(data) < 12:
        return []
    _qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
    if not flags & 0x8000:
        return []
    off = 12
    for _ in range(qd):
        _, off = decode_name(data, off)
        off += 4
    rrs, _ = parse_rrs(data, off, an + ns + ar)
    out = []
    for name, rtype, _c, _t, _r, _ro in rrs:
        if "_airplay" in name or "_raop" in name or "_bbcast" in name:
            out.append({12: "PTR", 16: "TXT", 33: "SRV"}.get(rtype, str(rtype)))
    return out


def main():
    mon = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    mon.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    mon.bind(("", PORT))
    mon.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                   struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY))
    mon.settimeout(0.3)
    print(f"[i] 监听 socket 已绑定 {PORT} 并加入组播组（用来收「组播应答」）")

    cases = [
        ("[C1] 3 问题 / 无 QU  (完全复制 iPhone 的报文形态)", [Q_BBCAST, Q_AIRPLAY, Q_RAOP], False),
        ("[C2] 3 问题 / 带 QU", [Q_BBCAST, Q_AIRPLAY, Q_RAOP], True),
        ("[C3] 1 问题 _airplay / 无 QU", [Q_AIRPLAY], False),
        ("[C4] 1 问题 _airplay / 带 QU", [Q_AIRPLAY], True),
    ]

    for title, names, qu in cases:
        print(f"\n===== {title} =====")
        q = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        q.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        q.bind(("", 0))
        q.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        try:
            q.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
        except OSError:
            pass
        q.settimeout(0.3)
        pkt = build_query(names, qu)
        q.sendto(pkt, (GROUP, PORT))
        print(f"  已发出（源端口 {q.getsockname()[1]}，{len(pkt)} 字节）")

        end = time.time() + 4.0
        hits = {}
        while time.time() < end:
            for sock, ch in ((q, "单播回源"), (mon, "组播应答")):
                try:
                    data, addr = sock.recvfrom(9000)
                except socket.timeout:
                    continue
                except OSError:
                    continue
                recs = summarize(data)
                if not recs:
                    continue
                key = (addr[0], ch)
                hits.setdefault(key, set()).update(recs)
        if not hits:
            print("  -> 没有任何设备应答")
        for (ip, ch) in sorted(hits):
            who = "本机 CastHub" if ip == CASTHUB else ("小米电视" if ip == MITV else ip)
            print(f"  -> {who:<12} {ip:<15} 通过【{ch}】应答，记录: {sorted(hits[(ip, ch)])}")
        q.close()

    # C5：从 5353 端口发（iOS 的源端口就是 5353）
    print("\n===== [C5] 从源端口 5353 发 3 问题 / 无 QU（最贴近 iPhone）=====")
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", PORT))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_LOOP, 0)
        except OSError:
            pass
        s.settimeout(0.3)
        s.sendto(build_query([Q_BBCAST, Q_AIRPLAY, Q_RAOP], False), (GROUP, PORT))
        print("  已发出")
        end = time.time() + 4.0
        hits = {}
        while time.time() < end:
            for sock, ch in ((s, "单播回源:5353"), (mon, "组播应答")):
                try:
                    data, addr = sock.recvfrom(9000)
                except socket.timeout:
                    continue
                except OSError:
                    continue
                recs = summarize(data)
                if not recs:
                    continue
                hits.setdefault((addr[0], ch), set()).update(recs)
        if not hits:
            print("  -> 没有任何设备应答")
        for (ip, ch) in sorted(hits):
            who = "本机 CastHub" if ip == CASTHUB else ("小米电视" if ip == MITV else ip)
            print(f"  -> {who:<12} {ip:<15} 通过【{ch}】应答，记录: {sorted(hits[(ip, ch)])}")
        s.close()
    except OSError as e:
        print(f"  ! 无法绑定 5353 作为源端口：{e}")

    mon.close()


if __name__ == "__main__":
    main()
