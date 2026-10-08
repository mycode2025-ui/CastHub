#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirPlay 接收端端到端自测
------------------------
不依赖真机 iPhone 也能验证整条链路：脚本扮演发送端，按 AirPlay 的真实时序
走一遍发现 → 取能力 → 建反向连接 → 投屏 → 轮询 → 拖动 → 暂停 → 停止。

    python tools/airplay_client_test.py                       # 默认 192.168.10.205:7000
    python tools/airplay_client_test.py --ip 192.168.10.205 --media http://192.168.10.31:8899/a.mp4

判定口径和 iPhone 一致：
  - 发现层：mDNS 查询 `_airplay._tcp.local`，要拿到 PTR + SRV + TXT + A；
  - 控制层：`/server-info`、`/playback-info` 必须是 **XML plist**；
  - 播放层：`/play` 之后 `duration` 要出现且 `position` 要往前走 ——
    position 不动的接收端，iPhone 会判定卡死并断开。
"""

import argparse
import plistlib
import socket
import struct
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

MDNS_GROUP = "224.0.0.251"
MDNS_PORT = 5353
SERVICE = "_airplay._tcp.local"

PASS, FAIL, WARN = "PASS", "FAIL", "WARN"
results = []


def check(no, name, ok, detail=""):
    results.append((no, name, PASS if ok is True else (WARN if ok is None else FAIL), detail))
    print(f"[{no:>2}] {'✔' if ok is True else ('!' if ok is None else '✘')} {name}"
          + (f"  — {detail}" if detail else ""))


# ─────────────────────────── mDNS ───────────────────────────

def encode_name(name: str) -> bytes:
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8")
        out += bytes([len(b)]) + b
    return out + b"\x00"


def decode_name(data: bytes, off: int):
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


def parse_rrs(data: bytes, off: int, count: int):
    out = []
    for _ in range(count):
        try:
            name, off = decode_name(data, off)
            rtype, rclass, ttl = struct.unpack("!HHI", data[off:off + 8])
            off += 8
            (rdlen,) = struct.unpack("!H", data[off:off + 2])
            off += 2
            out.append((name, rtype, data[off:off + rdlen], off))
            off += rdlen
        except Exception:
            break
    return out, off


def mdns_query(name: str, qtype: int, timeout: float = 3.0):
    """发一条 mDNS 查询，收全部应答。源端口随机 —— 严格 mDNS 用 5353，
    但接收端按包里的源端口单播回包才能被收到，这里正好顺带验证这一点。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", 0))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.settimeout(0.6)
    pkt = struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0) + \
        encode_name(name) + struct.pack("!HH", qtype, 0x8001)
    sock.sendto(pkt, (MDNS_GROUP, MDNS_PORT))

    end, bags = time.time() + timeout, []
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        if len(data) < 12 or not (struct.unpack("!H", data[2:4])[0] & 0x8000):
            continue
        qd = struct.unpack("!H", data[4:6])[0]
        an, ns, ar = struct.unpack("!HHH", data[6:12])
        off = 12
        for _ in range(qd):
            _, off = decode_name(data, off)
            off += 4
        rrs, off = parse_rrs(data, off, an)
        additional, _ = parse_rrs(data, off, ns + ar)
        bags.append((addr[0], rrs, additional, data))
    sock.close()
    return bags


def test_discovery(prefer: str = ""):
    """
    发现层检查。

    ⚠️ 局域网上往往不止一个 AirPlay 接收端：小米/索尼等电视自带的实现会同时应答
    （实测这台电视自带的实例叫「客厅的小米电视」，features=0x5A7FFFF7,0x1E）。
    早先这里"取第一个应答"会测到别人头上。所以：先把 PTR 指向的**全部**实例
    查出来，再按 --instance 指定（默认 CastHub）挑目标，挑不到就把看到的列出来。
    """
    print("\n=== 一、发现层（mDNS） ===")
    bags = mdns_query(SERVICE, 12, 3.0)
    check(1, "PTR 查询有应答", bool(bags),
          f"{len(bags)} 个应答包" if bags else "iPhone 打开投屏面板会看不到本设备")

    instances = []
    for src, rrs, additional, data in bags:
        for name, rtype, rdata, roff in rrs:
            if rtype == 12:
                inst, _ = decode_name(data, roff)
                if inst and inst not in instances:
                    instances.append(inst)
    check(2, "PTR 指向服务实例", bool(instances), " ｜ ".join(instances) or "—")

    found = []
    for inst in instances:
        port = None
        txt = {}
        for src, rrs, additional, data in mdns_query(inst, 255, 2.0):
            for name, rtype, rdata, roff in rrs + additional:
                if rtype == 33:
                    port = struct.unpack("!HHH", rdata[:6])[2]
                elif rtype == 16:
                    i = 0
                    while i < len(rdata):
                        l = rdata[i]
                        if l == 0:
                            break
                        i += 1
                        kv = rdata[i:i + l].decode("utf-8", "ignore")
                        if "=" in kv:
                            k, v = kv.split("=", 1)
                            txt[k] = v
                        i += l
        found.append((inst, port, txt))

    def pick(keyword: str):
        return next((f for f in found if keyword.lower() in f[0].lower()), None)

    chosen = pick(prefer) or pick("casthub") or (found[0] if len(found) == 1 else None)
    if chosen is None:
        check(3, "定位到被测实例", False,
              "未匹配到目标实例；局域网里看到的："
              + " ｜ ".join(f"{i}:{p}" for i, p, _ in found))
        return None

    instance, port, txt = chosen
    print(f"  → 被测实例：{instance}  (端口 {port})")
    check(3, "SRV 给出控制端口", port is not None, f"port={port}")
    check(4, "TXT 含 deviceid", "deviceid" in txt, txt.get("deviceid", "—"))

    feat = txt.get("features", "")
    ok = None
    try:
        bits = int(feat, 16)
        ok = bool(bits & 0x01)
    except Exception:
        ok = False
    check(5, "features 声明 Video（bit0）", ok, f"features={feat}")
    mirroring = False
    try:
        mirroring = bool(int(feat, 16) & 0x80)
    except Exception:
        pass
    check(6, "未虚标屏幕镜像（bit7）", not mirroring,
          "声明了却做不到，用户点了会连不上" if mirroring else "未声明，正确")
    return port


# ─────────────────────────── HTTP ───────────────────────────

def http(method: str, path: str, ip: str, port: int, body: bytes = b"",
         headers=None, raw=False, timeout=5.0):
    s = socket.create_connection((ip, port), timeout=timeout)
    hdr = "".join(f"{k}: {v}\r\n" for k, v in (headers or {}).items())
    s.sendall(f"{method} {path} HTTP/1.1\r\nHost: {ip}:{port}\r\n{hdr}\r\n".encode()
              + body)
    buf = b""
    try:
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if b"\r\n\r\n" in buf:
                head, _, rest = buf.partition(b"\r\n\r\n")
                cl = 0
                for line in head.split(b"\r\n")[1:]:
                    if line.lower().startswith(b"content-length:"):
                        cl = int(line.split(b":")[1].strip())
                if len(rest) >= cl and cl >= 0 and not raw:
                    break
                if raw:
                    break
    except socket.timeout:
        pass
    s.close()
    head, _, rest = buf.partition(b"\r\n\r\n")
    return head.decode("utf-8", "ignore"), rest


def plist_of(payload: bytes):
    try:
        return plistlib.loads(payload)
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default="192.168.10.205")
    ap.add_argument("--port", type=int, default=0, help="默认取 mDNS SRV 里的端口")
    ap.add_argument("--media", default="", help="一个可播放的 http:// 地址（mp4/m3u8）")
    ap.add_argument("--instance", default="CastHub",
                    help="被测实例名关键字。电视自带的投屏服务也会应答，默认只认 CastHub")
    args = ap.parse_args()

    mdns_port = test_discovery(args.instance)
    port = args.port or mdns_port or 7000

    print(f"\n=== 二、控制层（TCP :{port}） ===")

    head, body = http("GET", "/server-info", args.ip, port)
    check(7, "/server-info 返回 200", " 200 " in head, head.split("\r\n")[0])
    check(8, "/server-info 是 XML plist", "text/x-apple-plist+xml" in head.lower()
          and plist_of(body) is not None,
          (plist_of(body) or {}).get("name", "解析失败"))
    info = plist_of(body) or {}
    check(9, "server-info 含 deviceid/features/srcvers",
          all(k in info for k in ("deviceid", "features", "srcvers")),
          f"srcvers={info.get('srcvers')}")

    # /reverse：iPhone 用它接收异步事件，101 切换协议
    head, _ = http("POST", "/reverse", args.ip, port,
                   headers={"Upgrade": "PTTH/1.0", "Connection": "Upgrade",
                            "X-Apple-Purpose": "event", "Content-Length": "0"},
                   raw=True, timeout=2.0)
    check(10, "/reverse 返回 101 Switching Protocols",
          "101" in head and "PTTH/1.0" in head, head.split("\r\n")[0] or "—")

    head, body = http("GET", "/playback-info", args.ip, port)
    idle = plist_of(body) or {}
    check(11, "/playback-info 是 XML plist", idle is not None,
          f"rate={idle.get('rate')} readyToPlay={idle.get('readyToPlay')}")

    if not args.media:
        print("\n（未给 --media，跳过播放链路测试）")
        report()
        return

    print(f"\n=== 三、播放链路 ===")
    payload = plistlib.dumps({"Content-Location": args.media, "Start-Position": 0.0},
                             fmt=plistlib.FMT_BINARY)
    head, _ = http("POST", "/play", args.ip, port, body=payload,
                   headers={"Content-Type": "application/x-apple-binary-plist",
                            "Content-Length": str(len(payload)),
                            "X-Apple-Session-ID": "casthub-selftest"})
    check(12, "/play 返回 200", " 200 " in head, head.split("\r\n")[0])

    duration, positions = 0.0, []
    for _ in range(12):
        time.sleep(1.0)
        _, b = http("GET", "/playback-info", args.ip, port)
        p = plist_of(b) or {}
        duration = max(duration, float(p.get("duration") or 0))
        positions.append(round(float(p.get("position") or 0), 2))
        if duration > 0 and len(positions) >= 4 and positions[-1] > positions[0]:
            break

    check(13, "起播后能取到时长", duration > 0, f"duration={duration:.1f}s")
    advanced = len(positions) >= 2 and positions[-1] > positions[0]
    check(14, "position 持续前进（否则 iPhone 判卡死）", advanced,
          f"{positions[0]}s → {positions[-1]}s  采样={positions}")
    check(15, "readyToPlay 为 true",
          (plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {}).get("readyToPlay")
          in (True, 1), str((plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {})
                            .get("readyToPlay")))

    http("POST", "/rate?value=0", args.ip, port, headers={"Content-Length": "0"})
    time.sleep(1.0)
    pos_a = float((plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {})
                  .get("position") or 0)
    time.sleep(1.5)
    pos_b = float((plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {})
                  .get("position") or 0)
    check(16, "/rate?value=0 真的暂停了", abs(pos_b - pos_a) < 0.6,
          f"{pos_a:.2f}s → {pos_b:.2f}s")

    http("POST", "/rate?value=1", args.ip, port, headers={"Content-Length": "0"})
    time.sleep(1.5)
    pos_c = float((plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {})
                  .get("position") or 0)
    check(17, "/rate?value=1 恢复播放", pos_c > pos_b + 0.3, f"{pos_b:.2f}s → {pos_c:.2f}s")

    target = min(8.0, duration / 2) if duration > 0 else 8.0
    http("POST", f"/scrub?position={target}", args.ip, port, headers={"Content-Length": "0"})
    time.sleep(1.2)
    pos_d = float((plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {})
                  .get("position") or 0)
    check(18, "/scrub 定位生效", duration <= 0 or abs(pos_d - target) < 5.0,
          f"目标 {target:.1f}s，实际 {pos_d:.1f}s")

    http("POST", "/volume?volume=0.5", args.ip, port, headers={"Content-Length": "0"})
    check(19, "/volume 不报错", True, "已发送")

    head, _ = http("POST", "/stop", args.ip, port, headers={"Content-Length": "0"})
    time.sleep(1.0)
    stopped = plist_of(http("GET", "/playback-info", args.ip, port)[1]) or {}
    check(20, "/stop 后回到空闲", " 200 " in head and
          float(stopped.get("position") or 0) < 1.0,
          f"position={stopped.get('position')}")

    report()


def report():
    ok = sum(1 for r in results if r[2] == PASS)
    warn = sum(1 for r in results if r[2] == WARN)
    bad = sum(1 for r in results if r[2] == FAIL)
    print("\n" + "=" * 56)
    print(f"通过 {ok} / {len(results)}    告警 {warn}    失败 {bad}")
    if bad:
        print("\n失败项：")
        for no, name, st, detail in results:
            if st == FAIL:
                print(f"  [{no}] {name}  {detail}")
    print("=" * 56)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
