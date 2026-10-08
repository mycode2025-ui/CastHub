#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mDNS 全服务扫描器（AirPlay 发现层对照用）
----------------------------------------
用途：把局域网里**所有** AirPlay 相关实例的 SRV / TXT / A 记录抓出来，
     和本机（CastHub）逐字段对照。

为什么需要它：iPhone 的「隔空播放」面板里能看到别的电视、看不到我们，
     说明面板的筛选条件我们没满足。真实设备的 TXT 比任何文档都权威。

    python mdns_scan.py                 # 扫 _airplay._tcp / _raop._tcp / 服务枚举
    python mdns_scan.py --listen 20     # 只被动监听 20 秒（抓通告，不发查询）
    python mdns_scan.py --service _x._tcp.local

关键实现点（踩过的坑，勿改）：
  * 查询与接收**必须用两个 socket**：查询从临时端口发（带 QU 位，让对端单播回
    临时端口），监听用 5353 收组播通告。混用一个会让「发了查询就收不到通告」。
  * 中文实例名的标签长度必须按 **UTF-8 字节数**，不是字符数。
  * SRV/PTR 里的域名要用 **rdata 在整报文中的绝对偏移** 解（压缩指针指向报文别处）。
  * 查实例的 A 记录要查 **SRV 的 target 主机名**，不是实例名。
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

TYPE_A, TYPE_PTR, TYPE_TXT, TYPE_SRV, TYPE_AAAA, TYPE_ANY = 1, 12, 16, 33, 28, 255
RTYPE_NAME = {1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV", 47: "NSEC"}


def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")  # 字节数，不是字符数
        out += bytes([len(b)]) + b
    return out + b"\x00"


def decode_name(data: bytes, off: int):
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
            ptr = struct.unpack("!H", data[off:off + 2])[0] & 0x3FFF
            if orig_end is None:
                orig_end = off + 2
            off = ptr
            continue
        off += 1
        labels.append(data[off:off + l].decode("utf-8", "ignore"))
        off += l
    return ".".join(labels), (orig_end if orig_end is not None else off)


def parse_rrs(data: bytes, off: int, count: int):
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


def sniff_log(data: bytes, addr, label: str):
    """打印一个报文里所有记录（含 Answer/Authority/Additional 段）。"""
    if len(data) < 12:
        return
    qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
    if not flags & 0x8000:
        return  # 是查询，不是应答
    off = 12
    for _ in range(qd):
        _, off = decode_name(data, off)
        off += 4
    rrs, off = parse_rrs(data, off, an + ns + ar)
    for name, rtype, rclass, ttl, rdata, roff in rrs:
        tn = RTYPE_NAME.get(rtype, str(rtype))
        if rtype == TYPE_PTR:
            tgt, _ = decode_name(data, roff)
            print(f"  [{label}] {addr[0]:<15} PTR  {name}  ->  {tgt}   (ttl={ttl})")
        elif rtype == TYPE_SRV:
            prio, weight, port = struct.unpack("!HHH", rdata[:6])
            host, _ = decode_name(data, roff + 6)
            print(f"  [{label}] {addr[0]:<15} SRV  {name}  ->  {host}:{port}   (ttl={ttl})")
        elif rtype == TYPE_TXT:
            print(f"  [{label}] {addr[0]:<15} TXT  {name}   (ttl={ttl})")
            i = 0
            while i < len(rdata):
                l = rdata[i]
                if l == 0:
                    break
                i += 1
                kv = rdata[i:i + l].decode("utf-8", "ignore")
                print(f"            {kv}")
                i += l
        elif rtype == TYPE_A:
            print(f"  [{label}] {addr[0]:<15} A    {name}  ->  {socket.inet_ntoa(rdata[:4])}")
        elif rtype == TYPE_AAAA:
            print(f"  [{label}] {addr[0]:<15} AAAA {name}  ->  {socket.inet_ntop(socket.AF_INET6, rdata[:16])}")
        else:
            print(f"  [{label}] {addr[0]:<15} {tn:<4} {name}")


def send_query(qsock, name: str, qtype: int):
    q = encode_name(name) + struct.pack("!HH", qtype, 0x8001)  # QU: 单播回我
    qsock.sendto(struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0) + q, (GROUP, PORT))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--service", action="append", default=None)
    ap.add_argument("--listen", type=float, default=0, help="只被动监听 N 秒")
    ap.add_argument("--wait", type=float, default=3.0, help="每次查询后等待秒数")
    args = ap.parse_args()

    services = args.service or [
        "_airplay._tcp.local",
        "_raop._tcp.local",
        "_services._dns-sd._udp.local",
    ]

    # 监听 socket：绑 5353 收组播
    msock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    msock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        msock.bind(("", PORT))
        msock.setsockopt(
            socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
            struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY),
        )
        msock.settimeout(0.5)
        print(f"[i] 已加入组播 {GROUP}:{PORT}")
    except OSError as e:
        msock = None
        print(f"[!] 无法绑定 5353（{e}）——只收单播应答")

    # 查询 socket：临时端口
    qsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    qsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    qsock.bind(("", 0))
    qsock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    qsock.settimeout(0.5)
    print(f"[i] 查询源端口 {qsock.getsockname()[1]}（QU 单播应答将回到这里）\n")

    if args.listen > 0:
        print(f"=== 被动监听 {args.listen:.0f} 秒（抓主动通告）===")
        end = time.time() + args.listen
        while time.time() < end:
            for s in (msock, qsock):
                if s is None:
                    continue
                try:
                    data, addr = s.recvfrom(9000)
                    sniff_log(data, addr, "通告" if s is msock else "单播")
                except socket.timeout:
                    pass
        return

    for svc in services:
        print(f"=== 查询 {svc} ===")
        send_query(qsock, svc, TYPE_PTR)
        end = time.time() + args.wait
        instances = []
        while time.time() < end:
            for s in (msock, qsock):
                if s is None:
                    continue
                try:
                    data, addr = s.recvfrom(9000)
                except socket.timeout:
                    continue
                sniff_log(data, addr, "查询" if s is qsock else "组播")
                if len(data) < 12:
                    continue
                qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
                if not flags & 0x8000:
                    continue
                off = 12
                for _ in range(qd):
                    _, off = decode_name(data, off)
                    off += 4
                rrs, _ = parse_rrs(data, off, an + ns + ar)
                for name, rtype, _c, _t, rdata, roff in rrs:
                    if rtype == TYPE_PTR:
                        tgt, _ = decode_name(data, roff)
                        if svc in tgt or "dns-sd" in name:
                            instances.append(tgt)
        insts = sorted(set(instances))
        print(f"  -> 实例 {len(insts)} 个")
        for i in insts:
            print(f"     {i}")
        print()

        # 逐个实例取 SRV/TXT，再按 SRV target 取 A
        for inst in insts:
            if "dns-sd" in inst:
                continue
            print(f"--- {inst} ---")
            targets = []
            send_query(qsock, inst, TYPE_ANY)
            end = time.time() + args.wait
            while time.time() < end:
                for s in (msock, qsock):
                    if s is None:
                        continue
                    try:
                        data, addr = s.recvfrom(9000)
                    except socket.timeout:
                        continue
                    if len(data) < 12 or not struct.unpack("!H", data[2:4])[0] & 0x8000:
                        continue
                    qid, flags, qd, an, ns2, ar = struct.unpack("!HHHHHH", data[:12])
                    off = 12
                    for _ in range(qd):
                        _, off = decode_name(data, off)
                        off += 4
                    rrs, _ = parse_rrs(data, off, an + ns2 + ar)
                    for name, rtype, _c, ttl, rdata, roff in rrs:
                        if rtype == TYPE_SRV:
                            prio, weight, port = struct.unpack("!HHH", rdata[:6])
                            host, _ = decode_name(data, roff + 6)
                            targets.append(host)
                            print(f"  SRV  {name} -> {host}:{port} (prio={prio} weight={weight} ttl={ttl})")
                        elif rtype == TYPE_TXT:
                            print(f"  TXT  {name} (ttl={ttl}) 共 {len(rdata)} 字节")
                            i = 0
                            while i < len(rdata):
                                l = rdata[i]
                                if l == 0:
                                    break
                                i += 1
                                print(f"       {rdata[i:i+l].decode('utf-8','ignore')}")
                                i += l
            for t in sorted(set(targets)):
                send_query(qsock, t, TYPE_A)
                end = time.time() + 1.5
                while time.time() < end:
                    for s in (msock, qsock):
                        if s is None:
                            continue
                        try:
                            data, addr = s.recvfrom(9000)
                            sniff_log(data, addr, "A")
                        except socket.timeout:
                            pass
            print()


if __name__ == "__main__":
    main()
