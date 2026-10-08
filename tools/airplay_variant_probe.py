#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirPlay 多种 TXT 变体对照探针（一次动作定位 iOS 的准入条件）
--------------------------------------------------------------
背景：CastHub 的 mDNS 四件套齐全、对 iOS 的任何查询形态都能正确应答，
      但 iPhone 的隔空播放面板就是不列出它。同一网段的小米电视（乐播实现）
      却能列出。两者 TXT 只差四项：
          1) features 位宽/内容   0x19       vs 0x5A7FFFF7,0x1E
          2) pk 配对公钥          无         vs b077...
          3) pi 配对标识          无         vs 2e388006-...
          4) _raop._tcp 服务      无         vs 有

本脚本在本机同时冒充 5 台接收端，**每台只差一个变量**，于是「iPhone 面板里
出现哪几个名字」直接告诉我 iOS 卡在哪一条，不用一轮一轮试。

  A 基线        = CastHub 当前 TXT（对照组，预期不出现）
  B 宽能力      = A + 只把 features 换成 64 位宽掩码
  C 加配对信息  = A + pk + pi
  D 全对齐小米  = 完全照抄小米电视的字段集合（去掉 manufacturer/protovers）
  E 全对齐+RAOP = D + 额外发布 _raop._tcp

同时在本机 7000 端口提供 /info 与 /server-info（二进制 plist，模仿小米电视），
这样「TXT 之外的 HTTP 能力描述」这一层对所有变体一致，不干扰结论。

用法：
  python airplay_variant_probe.py --seconds 300
  运行后在 iPhone 上打开哔哩哔哩 -> 投屏 -> AirPlay，看列表里出现哪几个 CH-* 名字。
"""

import argparse
import plistlib
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

GROUP = "224.0.0.251"
PORT = 5353
HTTP_PORT = 7000

AIRPLAY_SVC = "_airplay._tcp.local"
RAOP_SVC = "_raop._tcp.local"
ENUM_SVC = "_services._dns-sd._udp.local"

T_A, T_PTR, T_TXT, T_AAAA, T_SRV = 1, 12, 16, 28, 33

PK = "b1c2d3e4f5061728394a5b6c7d8e9f00112233445566778899aabbccddeeff00"  # 32 字节占位公钥
PI = str(uuid.uuid4()).lower()


def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def encode_txt(entries) -> bytes:
    out = b""
    for e in entries:
        b = e.encode("utf-8")[:255]
        out += bytes([len(b)]) + b
    return out or b"\x00"


def srv_rdata(port: int, target: str) -> bytes:
    return struct.pack("!HHH", 0, 0, port) + encode_name(target)


def record(name, rtype, ttl, rdata, flush=True):
    cls = 0x8001 if flush else 0x0001
    return (encode_name(name) + struct.pack("!HHI", rtype, cls, ttl)
            + struct.pack("!H", len(rdata)) + rdata)


def build_response(answers, additionals, qid=0, question_bytes=b"", qd=0):
    hdr = struct.pack("!HHHHHH", qid, 0x8400, qd, len(answers), 0, len(additionals))
    return hdr + question_bytes + b"".join(answers) + b"".join(additionals)


def parse_questions(data):
    if len(data) < 12:
        return [], 0, 0
    qid, flags, qd = struct.unpack("!HHH", data[:6])
    off = 12
    qs = []
    for _ in range(qd):
        labels, guard = [], 0
        name_start = off
        while guard < 128:
            guard += 1
            if off >= len(data):
                break
            l = data[off]
            if l == 0:
                off += 1
                break
            if l & 0xC0 == 0xC0:
                off += 2
                break
            off += 1
            labels.append(data[off:off + l].decode("utf-8", "ignore"))
            off += l
        if off + 4 > len(data):
            break
        qtype, qclass = struct.unpack("!HH", data[off:off + 4])
        off += 4
        qs.append((".".join(labels), qtype, qclass, name_start, off - name_start))
    return qs, qid, flags


def local_ipv4() -> str | None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.10.1", 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def build_variants(ip: str):
    """返回 [(实例名, port, [txt...]), ...] 以及 _raop 列表。"""
    base_devid = "02:AA:BB:CC:DD:0A"

    def mac(n):
        return f"02:AA:BB:CC:DD:{n:02X}"

    def txt(d):
        return [f"{k}={v}" for k, v in d.items()]

    a = {
        "deviceid": mac(0x0A), "features": "0x19", "flags": "0x4",
        "model": "AppleTV3,2", "manufacturer": "CastHub",
        "srcvers": "220.68", "protovers": "1.0", "vv": "2", "pw": "false",
    }
    b = dict(a, **{"deviceid": mac(0x0B), "features": "0x5A7FFFF7,0x1E"})
    c = dict(a, **{"deviceid": mac(0x0C), "pk": PK, "pi": PI})
    d = {
        "deviceid": mac(0x0D), "features": "0x5A7FFFF7,0x1E", "srcvers": "220.68",
        "flags": "0x4", "vv": "2", "model": "AppleTV2,1", "pw": "false",
        "rhd": "5.5.20", "pk": PK, "pi": PI,
    }
    e = dict(d, **{"deviceid": mac(0x0E)})

    airplay = [
        ("CH-A 基线", txt(a)),
        ("CH-B 宽能力", txt(b)),
        ("CH-C 配对", txt(c)),
        ("CH-D 全对齐", txt(d)),
        ("CH-E 带RAOP", txt(e)),
    ]
    raop_name = f"{PK[:12]}@CH-E 带RAOP.{RAOP_SVC}"
    raop_txt = [
        "txtvers=1", "ch=2", "cn=0,1,2,3", "da=true", "et=0,3,5", "md=0,1,2",
        "pw=false", "sv=false", "sr=44100", "ss=16", "tp=UDP", "vn=65537",
        "vs=220.68", "am=AppleTV2,1", "sf=0x4", f"pk={PK}",
        "ft=0x5A7FFFF7,0x1E", "vv=2",
    ]
    return airplay, (raop_name, raop_txt), base_devid


def make_info_plist(deviceid, name, model, features) -> bytes:
    obj = {
        "pk": bytes.fromhex(PK),
        "pi": PI,
        "deviceid": deviceid,
        "macAddress": deviceid,
        "name": name,
        "model": model,
        "features": features,
        "flags": 0x4,
        "statusFlags": 0x4,
        "srcvers": "220.68",
        "sourceVersion": "220.68",
        "vv": 2,
        "protovers": "1.0",
        "audioFormats": [0x40000],
        "audioOutputFormats": [0x40000],
        "displays": [{
            "uuid": str(uuid.uuid4()).upper(),
            "name": name,
            "type": "AppleTV",
            "deviceID": deviceid,
            "features": features,
            "widthPhysical": 1920,
            "heightPhysical": 1080,
            "widthPixels": 1920,
            "heightPixels": 1080,
            "maxFPS": 60,
            "refreshRate": 60,
            "rotation": False,
            "overscanned": False,
        }],
    }
    return plistlib.dumps(obj, fmt=plistlib.FMT_BINARY)


class InfoHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"  [HTTP] {self.address_string()} {self.command} {self.path}", flush=True)

    def _send(self, body: bytes, ctype: str, extra=None):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/info":
            body = make_info_plist("02:AA:BB:CC:DD:0A", "CH Probe", "AppleTV2,1", 0x5A7FFFF7)
            self._send(body, "application/x-apple-binary-plist", {"Server": "AirTunes/220.68"})
        elif path == "/server-info":
            body = plistlib.dumps({
                "deviceid": "02:AA:BB:CC:DD:0A", "features": 0x5A7FFFF7,
                "model": "AppleTV2,1", "protovers": "1.0", "srcvers": "220.68",
                "macAddress": "02:AA:BB:CC:DD:0A", "vv": 2,
            })
            self._send(body, "text/x-apple-plist+xml")
        else:
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def do_POST(self):
        self.do_GET()


class Responder:
    def __init__(self, ip, airplay, raop):
        self.ip = ip
        self.airplay = airplay
        self.raop = raop
        self.host = "chprobe.local"
        self.sock = None
        self.running = True

    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", PORT))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                     struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        s.settimeout(0.5)
        self.sock = s

    def answers_for(self, qname, qtype):
        """返回 (answers, additionals) 或 None。"""
        qn = qname.rstrip(".")
        ans, add = [], []
        ipb = socket.inet_aton(self.ip)

        if qn == AIRPLAY_SVC:
            for name, txt in self.airplay:
                inst = f"{name}.{AIRPLAY_SVC}"
                ans.append(record(AIRPLAY_SVC, T_PTR, 4500, encode_name(inst), flush=False))
                add.append(record(inst, T_SRV, 120, srv_rdata(HTTP_PORT, self.host)))
                add.append(record(inst, T_TXT, 4500, encode_txt(txt)))
            add.append(record(self.host, T_A, 120, ipb))
            return ans, add
        if qn == RAOP_SVC:
            name, txt = self.raop
            ans.append(record(RAOP_SVC, T_PTR, 4500, encode_name(name), flush=False))
            add.append(record(name, T_SRV, 120, srv_rdata(HTTP_PORT, self.host)))
            add.append(record(name, T_TXT, 4500, encode_txt(txt)))
            add.append(record(self.host, T_A, 120, ipb))
            return ans, add
        if qn == ENUM_SVC:
            ans.append(record(ENUM_SVC, T_PTR, 4500, encode_name(AIRPLAY_SVC), flush=False))
            if self.raop:
                ans.append(record(ENUM_SVC, T_PTR, 4500, encode_name(RAOP_SVC), flush=False))
            return ans, add

        for name, txt in self.airplay:
            inst = f"{name}.{AIRPLAY_SVC}"
            if qn == inst:
                add.append(record(inst, T_SRV, 120, srv_rdata(HTTP_PORT, self.host)))
                add.append(record(inst, T_TXT, 4500, encode_txt(txt)))
                add.append(record(self.host, T_A, 120, ipb))
                if qtype == T_TXT:
                    return [record(inst, T_TXT, 4500, encode_txt(txt))], []
                if qtype == T_SRV:
                    return [record(inst, T_SRV, 120, srv_rdata(HTTP_PORT, self.host))], []
                return [record(inst, T_SRV, 120, srv_rdata(HTTP_PORT, self.host))], add
        rname, rtxt = self.raop
        if qn == rname:
            add.append(record(rname, T_SRV, 120, srv_rdata(HTTP_PORT, self.host)))
            add.append(record(rname, T_TXT, 4500, encode_txt(rtxt)))
            add.append(record(self.host, T_A, 120, ipb))
            if qtype == T_TXT:
                return [record(rname, T_TXT, 4500, encode_txt(rtxt))], []
            return [record(rname, T_SRV, 120, srv_rdata(HTTP_PORT, self.host))], add
        if qn == self.host:
            return [record(self.host, T_A, 120, ipb)], []
        return None

    def announce(self):
        ans, add = self.answers_for(AIRPLAY_SVC, T_PTR)
        ans2, add2 = self.answers_for(RAOP_SVC, T_PTR)
        pkt = build_response(ans + ans2, add + add2)
        try:
            self.sock.sendto(pkt, (GROUP, PORT))
        except OSError:
            pass

    def run(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                return
            if len(data) < 12:
                continue
            qs, qid, flags = parse_questions(data)
            if flags & 0x8000:
                continue  # 是应答不是查询
            if not qs:
                continue
            answers, additionals = [], []
            for qname, qtype, qclass, nstart, nlen in qs:
                r = self.answers_for(qname, qtype)
                if r:
                    answers += r[0]
                    additionals += r[1]
                    print(f"  [查询] {addr[0]}:{addr[1]} 问 {qname} type={qtype}"
                          f"{' QU' if qclass & 0x8000 else ''}  -> 予应答 "
                          f"{len(r[0])} 条 + 附加 {len(r[1])} 条", flush=True)
            if not answers:
                continue
            # 应答：组播一份（模拟小米电视的做法），另外单播回源一份
            multi = build_response(answers, additionals)
            try:
                self.sock.sendto(multi, (GROUP, PORT))
            except OSError:
                pass
            if addr[1] != PORT:
                legacy = build_response(answers, additionals, qid=qid,
                                        question_bytes=data[12:12 + sum(q[4] for q in qs)],
                                        qd=len(qs))
                try:
                    self.sock.sendto(legacy, addr)
                except OSError:
                    pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=300)
    ap.add_argument("--no-http", action="store_true")
    args = ap.parse_args()

    ip = local_ipv4()
    if not ip:
        print("[!] 找不到本机 IPv4")
        return
    if not ip.startswith("192.168.10."):
        print(f"[!] 本机 IP 是 {ip}，不在 192.168.10.0/24，多点广播可能到不了 iPhone。继续尝试。")

    airplay, raop, base_devid = build_variants(ip)
    print(f"[i] 本机 IP {ip}，将冒充 {len(airplay) + 1} 台 AirPlay 接收端：")
    for name, txt in airplay:
        feats = [t for t in txt if t.startswith("features")]
        extra = [t.split("=")[0] for t in txt if t.split("=")[0] in ("pk", "pi", "manufacturer", "protovers", "rhd")]
        print(f"      {name:<12} {feats[0]:<22} 额外字段={extra}")
    print(f"      {raop[0]:<12} (_raop._tcp)")

    r = Responder(ip, airplay, raop)
    r.start()
    print(f"[i] mDNS 应答器已就绪（{GROUP}:{PORT}），PID 文件无需；Ctrl+C 退出")

    if not args.no_http:
        try:
            httpd = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), InfoHandler)
            threading.Thread(target=httpd.serve_forever, daemon=True).start()
            print(f"[i] HTTP 已就绪：http://{ip}:{HTTP_PORT}/info （二进制 plist，模仿小米电视）")
        except OSError as e:
            print(f"[!] 无法监听 {HTTP_PORT}：{e}（不影响 mDNS 测试）")

    threading.Thread(target=r.run, daemon=True).start()

    end = time.time() + args.seconds
    n = 0
    while time.time() < end:
        r.announce()
        n += 1
        time.sleep(2)
    print(f"[i] 共组播通告 {n} 次，退出")
    r.running = False


if __name__ == "__main__":
    main()
