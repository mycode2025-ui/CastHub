#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SSDP 发现测试
-------------
模拟投屏 App（DMC）发出 M-SEARCH，验证接收端是否能被局域网发现。

这是投屏链路的第一环：**设备发现失败，后面全都无从谈起**。

用法：
    python ssdp_discover_test.py
    python ssdp_discover_test.py --expect 192.168.10.205   # 只关心某台设备
"""

import argparse
import socket
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

SSDP_GROUP = "239.255.255.250"
SSDP_PORT = 1900

TARGETS = [
    ("upnp:rootdevice", "根设备"),
    ("urn:schemas-upnp-org:device:MediaRenderer:1", "MediaRenderer"),
    ("urn:schemas-upnp-org:service:AVTransport:1", "AVTransport"),
    ("urn:schemas-upnp-org:service:ConnectionManager:1", "ConnectionManager"),
]


def search(st: str, timeout: float = 3.0):
    """发一次 M-SEARCH，返回 [(ip, 完整报文)]"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.settimeout(timeout)

    message = (
        "M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {SSDP_GROUP}:{SSDP_PORT}\r\n"
        'MAN: "ssdp:discover"\r\n'
        "MX: 2\r\n"
        f"ST: {st}\r\n\r\n"
    ).encode()

    replies = []
    try:
        sock.sendto(message, (SSDP_GROUP, SSDP_PORT))
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                break
            text = data.decode("utf-8", "ignore")
            if text.startswith("HTTP/1.1 200"):
                replies.append((addr[0], text))
    except Exception as e:
        print(f"  发送失败: {e}")
    finally:
        sock.close()
    return replies


def parse(text: str) -> dict:
    result = {}
    for line in text.split("\r\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            result[k.strip().upper()] = v.strip()
    return result


def main():
    ap = argparse.ArgumentParser(description="SSDP 设备发现测试")
    ap.add_argument("--expect", default=None, help="期望出现的设备 IP")
    args = ap.parse_args()

    print("=" * 66)
    print("SSDP 设备发现测试")
    print("=" * 66)

    found_locations = {}
    for st, label in TARGETS:
        replies = search(st)
        print(f"\n[{label}]  ST={st}")
        if not replies:
            print("  无响应")
            continue
        for ip, text in replies:
            headers = parse(text)
            location = headers.get("LOCATION", "")
            server = headers.get("SERVER", "")
            usn = headers.get("USN", "")
            found_locations.setdefault(ip, {})["location"] = location
            marker = ""
            if args.expect and ip == args.expect:
                marker = "  <<< 期望设备"
            print(f"  {ip:<16} {marker}")
            print(f"      LOCATION: {location}")
            print(f"      SERVER  : {server}")
            print(f"      USN     : {usn[:70]}")

    print()
    print("=" * 66)
    print(f"共发现 {len(found_locations)} 台设备：{', '.join(sorted(found_locations)) or '无'}")

    ok = True
    if args.expect:
        if args.expect in found_locations:
            print(f"✅ 目标设备 {args.expect} 可被发现")
            loc = found_locations[args.expect].get("location", "")
            if loc:
                print(f"   设备描述地址：{loc}")
                # 进一步验证描述文档可达
                import urllib.request
                try:
                    with urllib.request.urlopen(loc, timeout=5) as resp:
                        body = resp.read().decode("utf-8", "ignore")
                    if "MediaRenderer" in body:
                        print("   ✅ 描述文档可访问且声明为 MediaRenderer:1")
                    else:
                        print("   ⚠ 描述文档可访问，但未声明 MediaRenderer")
                        ok = False
                except Exception as e:
                    print(f"   ❌ 描述文档不可访问：{e}")
                    ok = False
        else:
            print(f"❌ 未发现目标设备 {args.expect}")
            print("   排查：App 是否在前台/前台服务是否运行、是否同一 Wi-Fi、")
            print("        路由器是否开启 AP 隔离、本机防火墙是否拦截 UDP 1900")
            ok = False
    print("=" * 66)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
