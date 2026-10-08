#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
投屏探针 v2 (mDNS / DNS-SD 方向)
--------------------------------
v1 证实：夸克网盘的投屏列表里没有标准 DLNA/SSDP 设备，只有注册了 `_leboremote._tcp`
的小米电视。说明夸克走的是 **mDNS 私有服务发现**（很可能是乐播 SDK）。

本工具做两件事：
  1. 被动监听：把局域网内所有设备发出的 mDNS 查询原样记录下来
     —— 手机一点「投屏」，它搜什么服务名，这里一清二楚。
  2. 主动注册：把本机伪装成多个投屏接收端，注册
     _leboremote._tcp(乐播) / _airplay._tcp / _raop._tcp / _dial._tcp / _airkan._tcp，
     看夸克的设备列表里会不会出现本设备。

零第三方依赖，Python 3.8+。日志落盘到 mdns_cast_probe.log。

用法：
    python cast_probe_v2.py                    # 设备名默认 投屏探针v2
    python cast_probe_v2.py --name 我的盒子 --port 8201
"""

import argparse
import socket
import struct
import sys
import threading
import time
import uuid

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

MDNS_GROUP = "224.0.0.251"
MDNS_PORT = 5353
LOG_FILE = "mdns_cast_probe.log"
_lock = threading.Lock()


def log(msg: str) -> None:
    with _lock:
        print(msg, flush=True)
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(msg + "\n")
        except Exception:
            pass


def get_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


# ---------------------------------------------------------------- DNS 编解码
def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def decode_name(data: bytes, off: int):
    labels = []
    jumped = False
    end = off
    while True:
        l = data[off]
        if l == 0:
            off += 1
            if not jumped:
                end = off
            break
        if l & 0xC0 == 0xC0:
            ptr = struct.unpack("!H", data[off : off + 2])[0] & 0x3FFF
            n, _ = decode_name(data, ptr)
            labels.append(n)
            off += 2
            if not jumped:
                end = off
                jumped = True
            break
        off += 1
        labels.append(data[off : off + l].decode("utf-8", "ignore"))
        off += l
        if not jumped:
            end = off
    return ".".join(labels), end


def rr(name: str, rtype: int, rdata: bytes, ttl: int = 120, flush: bool = False) -> bytes:
    cls = 1 | (0x8000 if flush else 0)
    return (
        encode_name(name)
        + struct.pack("!HHI", rtype, cls, ttl)
        + struct.pack("!H", len(rdata))
        + rdata
    )


def txt_rdata(kv) -> bytes:
    out = b""
    for k, v in kv:
        e = f"{k}={v}".encode("utf-8")
        out += bytes([len(e)]) + e
    return out or b"\x00"


TYPE_NAME = {1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV", 255: "ANY"}


# ---------------------------------------------------------------- 服务定义
class Service:
    def __init__(self, stype: str, port: int, txt):
        self.stype = stype          # e.g. "_leboremote._tcp.local"
        self.port = port
        self.txt = txt


def get_mac() -> str:
    n = uuid.getnode()
    return ":".join(f"{(n >> i) & 0xff:02x}" for i in (40, 32, 24, 16, 8, 0))


def build_services(port: int, name: str, hostname: str, device_id: str):
    p = str(port)
    mac = get_mac()
    return [
        # 乐播投屏 —— TXT 字段照抄真实小米电视（实测抓取），
        # 关键：乐播 SDK 从 TXT 的 port 字段取端口，不是 SRV 端口。
        Service(
            "_leboremote._tcp.local",
            port,
            [
                ("port", p),
                ("version", "5.0"),
                ("w", "1920"),
                ("h", "1080"),
                ("raop", p),
                ("airplay", p),
                ("remote", p),
                ("lelinkport", p),
                ("devicemac", mac),
                ("mirror", "7030"),
                ("channel", "LEBO-APK-sdk-5.5.20"),
                ("feature", "223"),
                ("lebofeature", "223"),
                ("packagename", "SdkPackage"),
            ],
        ),
        # AirPlay
        Service(
            "_airplay._tcp.local",
            port,
            [("deviceid", "00:00:00:00:00:00"), ("features", "0x5A7FFFF7"),
             ("model", "AppleTV3,2"), ("srcvers", "220.68"), ("pi", "00:00:00:00:00:00")],
        ),
        # RAOP（AirPlay 音频）
        Service(
            "_raop._tcp.local",
            port,
            [("ch", "2"), ("cn", "0,1"), ("da", "true"), ("et", "0,3,5"),
             ("md", "0,1,2"), ("sf", "0x4"), ("sr", "44100"), ("ss", "16"),
             ("tp", "UDP"), ("vn", "3"), ("vs", "220.68")],
        ),
        # DIAL（Chromecast / Netflix 用）
        Service(
            "_dial._tcp.local",
            port,
            [("n", name), ("w", "1")],
        ),
        # AirKan（海信/部分国产投屏）
        Service(
            "_airkan._tcp.local",
            port,
            [("name", name), ("deviceid", device_id)],
        ),
    ]


# ---------------------------------------------------------------- mDNS 响应器
class MdnsResponder(threading.Thread):
    def __init__(self, ip: str, name: str, port: int, services):
        super().__init__(daemon=True)
        self.ip = ip
        self.name = name
        self.port = port
        self.services = services
        self.hostname = "cast-probe.local"
        self.instance = {s.stype: f"{name}.{s.stype}" for s in services}
        self.sock = None

    def _records_for(self, svc: Service) -> bytes:
        inst = self.instance[svc.stype]
        out = rr(svc.stype, 12, encode_name(inst))                       # PTR
        out += rr(inst, 33,
                  struct.pack("!HHH", 0, 0, svc.port) + encode_name(self.hostname),
                  flush=True)                                            # SRV
        out += rr(inst, 16, txt_rdata(svc.txt), flush=True)              # TXT
        out += rr(self.hostname, 1, socket.inet_aton(self.ip), flush=True)  # A
        return out

    def announce(self) -> None:
        for svc in self.services:
            ancount = 4
            pkt = struct.pack("!HHHHHH", 0, 0x8400, 0, ancount, 0, 0) + self._records_for(svc)
            try:
                self.sock.sendto(pkt, (MDNS_GROUP, MDNS_PORT))
            except Exception as e:
                log(f"[mDNS] announce failed: {e}")
        # 服务枚举
        ans = b""
        for svc in self.services:
            ans += rr("_services._dns-sd._udp.local", 12, encode_name(svc.stype))
        pkt = struct.pack("!HHHHHH", 0, 0x8400, 0, len(self.services), 0, 0) + ans
        try:
            self.sock.sendto(pkt, (MDNS_GROUP, MDNS_PORT))
        except Exception:
            pass
        log(f"[mDNS] 已广播 {len(self.services)} 个投屏服务")

    def run(self) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            self.sock.bind(("", MDNS_PORT))
        except OSError as e:
            log(f"[mDNS] bind 5353 失败: {e} —— 端口可能被 Bonjour 服务占用")
            return
        self.sock.setsockopt(
            socket.IPPROTO_IP,
            socket.IP_ADD_MEMBERSHIP,
            struct.pack("4sl", socket.inet_aton(MDNS_GROUP), socket.INADDR_ANY),
        )
        self.sock.settimeout(1.0)
        self.announce()
        threading.Thread(target=self._reannounce, daemon=True).start()
        log(f"[mDNS] 监听 {MDNS_GROUP}:{MDNS_PORT}，等待手机搜索…")
        while True:
            try:
                data, addr = self.sock.recvfrom(9000)
            except socket.timeout:
                continue
            except Exception:
                break
            if len(data) < 12:
                continue
            qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
            if flags & 0x8000:      # 响应包，忽略
                continue
            if qd == 0:
                continue
            if addr[0] == self.ip:  # 自己发的，忽略
                continue
            off = 12
            questions = []
            try:
                for _ in range(qd):
                    nm, off = decode_name(data, off)
                    qtype, qclass = struct.unpack("!HH", data[off : off + 4])
                    off += 4
                    questions.append((nm, qtype, qclass))
            except Exception:
                continue
            for nm, qtype, qclass in questions:
                tname = TYPE_NAME.get(qtype, str(qtype))
                log(f"[mDNS] 查询 <- {addr[0]:<15} {nm}  ({tname})")
                self._respond(nm, qtype, qclass, addr)

    def _respond(self, qname: str, qtype: int, qclass: int, addr) -> None:
        qname = qname.lower()
        ans = b""
        add = b""
        n = 0
        if qname.startswith("_services._dns-sd._udp"):
            for svc in self.services:
                ans += rr("_services._dns-sd._udp.local", 12, encode_name(svc.stype))
                n += 1
        else:
            for svc in self.services:
                st = svc.stype.lower()
                if qname == st and qtype in (12, 255):
                    inst = self.instance[svc.stype]
                    ans += rr(svc.stype, 12, encode_name(inst))
                    add += rr(inst, 33,
                              struct.pack("!HHH", 0, 0, svc.port) + encode_name(self.hostname),
                              flush=True)
                    add += rr(inst, 16, txt_rdata(svc.txt), flush=True)
                    add += rr(self.hostname, 1, socket.inet_aton(self.ip), flush=True)
                    n += 1
                elif qname == self.instance[svc.stype].lower() and qtype in (33, 16, 255, 1):
                    inst = self.instance[svc.stype]
                    ans += rr(inst, 33,
                              struct.pack("!HHH", 0, 0, svc.port) + encode_name(self.hostname),
                              flush=True)
                    ans += rr(inst, 16, txt_rdata(svc.txt), flush=True)
                    add += rr(self.hostname, 1, socket.inet_aton(self.ip), flush=True)
                    n += 2
                elif qname == self.hostname and qtype in (1, 255):
                    ans += rr(self.hostname, 1, socket.inet_aton(self.ip), flush=True)
                    n += 1
        if n == 0:
            return
        arcount = _count_rr(add) if add else 0
        pkt = struct.pack("!HHHHHH", 0, 0x8400, 0, n, 0, arcount) + ans + add
        unicast = bool(qclass & 0x8000)
        try:
            self.sock.sendto(pkt, (addr[0], addr[1]) if unicast else (MDNS_GROUP, MDNS_PORT))
        except Exception as e:
            log(f"[mDNS] respond failed: {e}")

    def _reannounce(self) -> None:
        # 20 秒一次：很多 App（含乐播系）靠被动监听 announcement 建设备列表，
        # 广播太稀疏会被错过。真实设备（如小米电视）也是持续广播的。
        while True:
            time.sleep(20)
            self.announce()


def _count_rr(blob: bytes) -> int:
    """数一段 RR 区域里的记录条数"""
    cnt = 0
    off = 0
    while off < len(blob):
        _, off = decode_name(blob, off)
        if off + 10 > len(blob):
            break
        rtype, rclass, ttl = struct.unpack("!HHI", blob[off : off + 8])
        off += 8
        (rdlen,) = struct.unpack("!H", blob[off : off + 2])
        off += 2 + rdlen
        cnt += 1
    return cnt


# ------------------------------------------------- 原始 TCP 抓包（承接连接）
# 乐播用的是私有 TCP 协议，不是 HTTP。这里先原样记录每一个字节，
# 再判断是否为 HTTP（DLNA/SOAP 会走 HTTP），是则回 200 保持会话。
HITS = []
HTTP_VERBS = (b"GET ", b"POST", b"HEAD", b"PUT ", b"DELE", b"OPTI", b"PATC",
              b"SUBS", b"UNSU", b"NOTI", b"M-SE", b"NOTI")


import os

CAPTURE_DIR = "capture"
_conn_seq = [0]
_seq_lock = threading.Lock()


def next_conn_id() -> int:
    with _seq_lock:
        _conn_seq[0] += 1
        return _conn_seq[0]


def dump_payload(cid: int, src: str, data: bytes) -> None:
    """一次性打印完整请求体，并落盘成单独文件，避免多线程日志交错。"""
    try:
        os.makedirs(CAPTURE_DIR, exist_ok=True)
        fn = os.path.join(CAPTURE_DIR, f"conn{cid}_{src.replace('.', '_')}.bin")
        with open(fn, "ab") as f:
            f.write(data + b"\n=====\n")
    except Exception:
        fn = "(落盘失败)"
    log(f"[c{cid}][{src}] 完整请求 {len(data)} 字节 -> {fn}")
    ascii_body = data.decode("utf-8", "ignore")
    if ascii_body.isprintable() or "\r\n" in ascii_body:
        for line in ascii_body.split("\r\n"):
            if line.strip():
                log(f"    | {line}")
    else:
        for i in range(0, len(data), 16):
            chunk = data[i : i + 16]
            hx = " ".join(f"{b:02x}" for b in chunk)
            asc = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
            log(f"    {i:04x}  {hx:<48}  {asc}")


def handle_conn(c: socket.socket, addr) -> None:
    src = addr[0]
    cid = next_conn_id()
    log("=" * 66)
    log(f"[c{cid}] 新连接 {src}:{addr[1]}")
    HITS.append(("TCP", src, addr[1]))
    c.settimeout(60)
    buf = b""
    try:
        while True:
            data = c.recv(8192)
            if not data:
                log(f"[c{cid}] {src} 关闭连接")
                break
            buf += data
            # 收够一个完整 HTTP 请求再统一打印，避免分片/多线程交错
            end = buf.find(b"\r\n\r\n")
            if end != -1:
                head = buf[:end].decode("utf-8", "ignore")
                cl = 0
                for line in head.split("\r\n"):
                    if line.lower().startswith("content-length:"):
                        try:
                            cl = int(line.split(":", 1)[1].strip())
                        except Exception:
                            cl = 0
                body_start = end + 4
                if len(buf) >= body_start + cl:
                    dump_payload(cid, src, buf[: body_start + cl])
                    buf = buf[body_start + cl :]
                    try:
                        c.sendall(
                            b"HTTP/1.1 200 OK\r\n"
                            b"Content-Type: text/plain; charset=utf-8\r\n"
                            b"Content-Length: 2\r\n"
                            b"Connection: keep-alive\r\n\r\nok"
                        )
                        log(f"[c{cid}] 已回 HTTP 200")
                    except Exception as e:
                        log(f"[c{cid}] 回应失败: {e}")
            else:
                # 不是 HTTP（可能是 PTTH 反向连接或二进制协议），原样记
                dump_payload(cid, src, buf)
                buf = b""
    except socket.timeout:
        log(f"[c{cid}] {src} 60 秒无数据")
    except Exception as e:
        log(f"[c{cid}] {src} 异常: {e}")
    finally:
        try:
            c.close()
        except Exception:
            pass


def raw_server(port: int) -> None:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", port))
    s.listen(16)
    log(f"[TCP ] 原始抓包服务监听 0.0.0.0:{port}")
    while True:
        try:
            c, a = s.accept()
        except Exception:
            continue
        threading.Thread(target=handle_conn, args=(c, a), daemon=True).start()


# ---------------------------------------------------------------- main
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="投屏探针v2")
    ap.add_argument("--port", type=int, default=8201)
    ap.add_argument("--ip", default=None)
    ap.add_argument("--only", default=None, help="只注册某个服务，如 _leboremote._tcp.local")
    args = ap.parse_args()

    ip = args.ip or get_local_ip()
    device_id = uuid.uuid4().hex[:16]
    services = build_services(args.port, args.name, "cast-probe.local", device_id)
    if args.only:
        services = [s for s in services if s.stype.lower() == args.only.lower()]
        if not services:
            services = [Service(args.only, args.port, [("name", args.name)])]

    try:
        open(LOG_FILE, "w", encoding="utf-8").close()
    except Exception:
        pass

    log("=" * 66)
    log("投屏探针 v2 (mDNS / DNS-SD 方向)")
    log(f"  设备名 : {args.name}")
    log(f"  本机IP : {ip}")
    log(f"  端口   : {args.port}")
    log("  注册服务:")
    for s in services:
        log(f"    - {s.stype}  (port {s.port})")
    log("")
    log("现在请打开夸克网盘投屏，看列表里是否出现本设备。")
    log("同时这里会打印手机搜索时发出的每一个 mDNS 查询。")
    log("按 Ctrl+C 退出")
    log("=" * 66)

    MdnsResponder(ip, args.name, args.port, services).start()
    threading.Thread(target=raw_server, args=(args.port,), daemon=True).start()
    log("[TCP ] 提示：现在请在夸克里点『投屏探针v2』，这里会记录它发来的每一个字节")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        log("\n退出")


if __name__ == "__main__":
    main()
