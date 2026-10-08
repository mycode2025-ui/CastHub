#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mDNS 服务查询器
---------------
用途：向局域网查询某个投屏服务的真实实例，解析出它的 SRV / TXT / A 记录。
典型用法：照抄真实设备（如小米电视上的乐播）的 TXT 字段，好让自己的接收端
能被同一个 App 识别。

  python mdns_query.py --service _leboremote._tcp.local
  python mdns_query.py --service _airplay._tcp.local
  python mdns_query.py --raw            # 先枚举局域网里所有服务类型
"""

import argparse
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


def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def decode_name(data: bytes, off: int):
    """标准 DNS 域名解码：跟随压缩指针，一直到 0 结束。返回 (名字, 结束偏移)。"""
    labels = []
    orig_end = None
    guard = 0
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
            ptr = struct.unpack("!H", data[off : off + 2])[0] & 0x3FFF
            if orig_end is None:
                orig_end = off + 2
            off = ptr
            continue
        off += 1
        labels.append(data[off : off + l].decode("utf-8", "ignore"))
        off += l
    return ".".join(labels), (orig_end if orig_end is not None else off)


def parse_rrs(data: bytes, off: int, count: int):
    """返回 [(name, rtype, rclass, ttl, rdata, rdata_off)]
    rdata_off 是 rdata 在整个报文里的绝对偏移 —— 解析 PTR/SRV 里的域名必须用它，
    因为域名常用压缩指针指向报文其他位置，只拿 rdata 片段会越界。"""
    out = []
    for _ in range(count):
        try:
            name, off = decode_name(data, off)
            rtype, rclass, ttl = struct.unpack("!HHI", data[off : off + 8])
            off += 8
            (rdlen,) = struct.unpack("!H", data[off : off + 2])
            off += 2
            rdata_off = off
            rdata = data[off : off + rdlen]
            off += rdlen
            out.append((name, rtype, rclass, ttl, rdata, rdata_off))
        except Exception:
            break
    return out, off


def query(sock, name: str, qtype: int, timeout: float = 3.0):
    q = encode_name(name) + struct.pack("!HH", qtype, 0x8001)  # QU: 请求单播回应
    pkt = struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0) + q
    sock.sendto(pkt, (GROUP, PORT))
    end = time.time() + timeout
    results = []
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        if len(data) < 12:
            continue
        qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
        if not flags & 0x8000:
            continue
        off = 12
        for _ in range(qd):
            _, off = decode_name(data, off)
            off += 4
        rrs, off = parse_rrs(data, off, an)
        rrs2, _ = parse_rrs(data, off, ns + ar)
        results.append((addr[0], rrs + rrs2, data))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--service", default="_leboremote._tcp.local")
    ap.add_argument("--raw", action="store_true", help="先枚举所有服务类型")
    args = ap.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", PORT))
    sock.setsockopt(
        socket.IPPROTO_IP,
        socket.IP_ADD_MEMBERSHIP,
        struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY),
    )
    sock.settimeout(1.0)

    if args.raw:
        print("=== 枚举局域网服务类型 ===")
        for src, rrs, data in query(sock, "_services._dns-sd._udp.local", 12, 4):
            for name, rtype, rclass, ttl, rdata, roff in rrs:
                if rtype == 12:
                    try:
                        tgt, _ = decode_name(data, roff)
                        print(f"  {src:<15} -> {tgt}")
                    except Exception:
                        pass
        print()

    svc = args.service
    print(f"=== 查询服务 {svc} ===")
    instances = set()
    for src, rrs, data in query(sock, svc, 12, 3):
        for name, rtype, rclass, ttl, rdata, roff in rrs:
            if rtype == 12:
                try:
                    tgt, _ = decode_name(data, roff)
                except Exception:
                    continue
                instances.add((src, tgt))
    for src, inst in sorted(instances):
        print(f"  实例: {inst}   (由 {src} 宣告)")
    print()

    print("=== 解析实例的 SRV / TXT / A ===")
    for src, inst in sorted(instances):
        print(f"\n--- {inst} ---")
        for _, rrs, data in query(sock, inst, 255, 3):
            for name, rtype, rclass, ttl, rdata, roff in rrs:
                if rtype == 33:  # SRV
                    prio, weight, port = struct.unpack("!HHH", rdata[:6])
                    host, _ = decode_name(data, roff + 6)
                    print(f"  SRV   host={host}  port={port}  (prio={prio} weight={weight})")
                elif rtype == 16:  # TXT
                    print("  TXT :")
                    i = 0
                    while i < len(rdata):
                        l = rdata[i]
                        if l == 0:
                            break
                        i += 1
                        kv = rdata[i : i + l].decode("utf-8", "ignore")
                        print(f"      {kv}")
                        i += l
                elif rtype == 1:  # A
                    print(f"  A     {socket.inet_ntoa(rdata[:4])}")
        break

    if not instances:
        print("  未发现该服务的实例（设备可能只在被动广播，稍等再试或先跑 --raw）")


if __name__ == "__main__":
    main()
