#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AirPlay 发现层现场取证
--------------------
在用户「打开 iPhone 隔空播放面板」的那一刻，同时记录：

  A. 网段里所有 mDNS 报文（含 iOS 发的**查询**和设备的**应答**）
  B. CastHub 应用自身的 logcat（它会把每个 AirPlay HTTP 请求打出来）

用途：一次动作就能判定失败发生在哪一层 ——
  * iOS 根本没发 `_airplay._tcp` 查询        -> 与发送端有关
  * iOS 查了但我们没应答                     -> 应答器/网络层
  * 我们应答了、iOS 却没来访问 7000 端口      -> TXT 筛选（iOS 内部过滤）
  * iOS 访问了 /info 或别的路径但失败        -> HTTP 能力描述层
  * 一切正常                                 -> 再看面板渲染

用法：
  python airplay_probe_capture.py --seconds 180
  然后立刻在 iPhone 上操作：哔哩哔哩 -> 投屏 -> AirPlay
"""

import argparse
import datetime
import os
import re
import socket
import struct
import subprocess
import sys
import threading
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

GROUP = "224.0.0.251"
PORT = 5353
ADB = r"C:\Users\Administrator\.workbuddy\toolchain\android-sdk\platform-tools\adb.exe"
DEVICE = "192.168.10.205:5555"
SELF_IP = "192.168.10.205"

RTYPE_NAME = {1: "A", 12: "PTR", 16: "TXT", 28: "AAAA", 33: "SRV", 47: "NSEC"}

_out_lock = threading.Lock()
_log_file = None


def emit(line: str):
    ts = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
    text = f"{ts} {line}"
    with _out_lock:
        print(text, flush=True)
        if _log_file:
            _log_file.write(text + "\n")
            _log_file.flush()


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


def describe(data, addr, channel):
    """把一个 mDNS 报文里的人话打印出来。区分查询(QR=0)与应答(QR=1)。"""
    if len(data) < 12:
        return
    _qid, flags, qd, an, ns, ar = struct.unpack("!HHHHHH", data[:12])
    is_query = not (flags & 0x8000)
    off = 12
    qs = []
    for _ in range(qd):
        name, off = decode_name(data, off)
        if off + 4 > len(data):
            break
        qtype = struct.unpack("!H", data[off:off + 2])[0]
        qclass = struct.unpack("!H", data[off + 2:off + 4])[0]
        off += 4
        qu = " QU" if qclass & 0x8000 else ""
        qs.append(f"{name} type={RTYPE_NAME.get(qtype, qtype)}{qu}")

    if is_query:
        if not any("_airplay" in q or "_raop" in q or "_dns-sd" in q or "_device-info" in q
                   for q in qs):
            return  # 过滤掉无关噪音（其它 App 的浏览查询）
        emit(f"[mDNS 查询] {addr[0]:<15} ({channel}) {len(qs)} 问: " + " | ".join(qs))
        return

    rrs, _ = parse_rrs(data, off, an + ns + ar)
    air = [r for r in rrs if "_airplay" in r[0] or "_raop" in r[0] or "_device-info" in r[0]]
    if not air:
        return
    tag = "本机(CastHub)" if addr[0] == SELF_IP else addr[0]
    for name, rtype, rclass, ttl, rdata, roff in air:
        tn = RTYPE_NAME.get(rtype, str(rtype))
        if rtype == 12:
            tgt, _ = decode_name(data, roff)
            emit(f"[mDNS 应答] {tag:<15} ({channel}) PTR {name} -> {tgt}")
        elif rtype == 33:
            _p, _w, port = struct.unpack("!HHH", rdata[:6])
            host, _ = decode_name(data, roff + 6)
            emit(f"[mDNS 应答] {tag:<15} ({channel}) SRV {name} -> {host}:{port}")
        elif rtype == 16:
            items = []
            i = 0
            while i < len(rdata):
                l = rdata[i]
                if l == 0:
                    break
                i += 1
                items.append(rdata[i:i + l].decode("utf-8", "ignore"))
                i += l
            emit(f"[mDNS 应答] {tag:<15} ({channel}) TXT {name}")
            for it in items:
                emit(f"              {it}")
        elif rtype == 1:
            emit(f"[mDNS 应答] {tag:<15} ({channel}) A   {name} -> {socket.inet_ntoa(rdata[:4])}")


def mdns_worker(seconds):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", PORT))
        sock.setsockopt(
            socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
            struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY),
        )
    except OSError as e:
        emit(f"[!] mDNS 监听失败：{e}")
        return
    sock.settimeout(0.5)
    emit(f"[i] mDNS 监听已就绪（组播 {GROUP}:{PORT}）")
    end = time.time() + seconds
    while time.time() < end:
        try:
            data, addr = sock.recvfrom(9000)
        except socket.timeout:
            continue
        except OSError:
            return
        describe(data, addr, "组播")


def logcat_worker(seconds):
    try:
        subprocess.run([ADB, "connect", DEVICE], capture_output=True, timeout=15)
        pid_out = subprocess.run(
            [ADB, "-s", DEVICE, "shell", "pidof com.casthub.app"],
            capture_output=True, text=True, timeout=15,
        ).stdout.strip()
        pid = re.sub(r"\D", "", pid_out)
        if not pid:
            emit("[!] CastHub 未运行，logcat 抓不到应用日志")
            return
        emit(f"[i] CastHub pid={pid}，开始抓应用日志")
        p = subprocess.Popen(
            [ADB, "-s", DEVICE, "logcat", "-v", "time", "--pid", pid],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
            encoding="utf-8", errors="ignore", bufsize=1,
        )
        end = time.time() + seconds
        while time.time() < end:
            line = p.stdout.readline()
            if not line:
                if p.poll() is not None:
                    break
                continue
            if "_airplay" in line.lower() or "airplay" in line.lower() or "mdns" in line.lower() \
                    or "nsd" in line.lower() or "http" in line.lower():
                # 去掉 logcat 前缀里的时间/进程号，保留可读部分
                emit("[应用] " + line.rstrip())
        p.terminate()
    except Exception as e:
        emit(f"[!] logcat 线程异常：{e}")


def main():
    global _log_file
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=180)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                  "airplay_capture.txt"))
    args = ap.parse_args()

    _log_file = open(args.out, "w", encoding="utf-8")
    emit(f"=== 取证开始，持续 {args.seconds:.0f} 秒；输出同时写入 {args.out} ===")
    emit("=== 请在 iPhone 上操作：哔哩哔哩 -> 投屏 -> AirPlay（面板打开后停留 10 秒）===")

    t1 = threading.Thread(target=mdns_worker, args=(args.seconds,), daemon=True)
    t2 = threading.Thread(target=logcat_worker, args=(args.seconds,), daemon=True)
    t1.start()
    t2.start()
    t1.join(args.seconds + 10)
    t2.join(args.seconds + 10)
    emit("=== 取证结束 ===")
    _log_file.close()


if __name__ == "__main__":
    main()
