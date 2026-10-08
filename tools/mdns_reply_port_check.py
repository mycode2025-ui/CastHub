#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mDNS 答复的「源端口」检查
-------------------------
为什么查这个：iPhone 的 mDNSResponder 只接受**源端口为 5353** 的单播答复。
如果设备用临时端口回包，PC 上的扫描器一切正常（它不校验源端口），
但 iPhone 会静默丢弃 -> 症状就是「mDNS 看着没问题，面板里却永远没有这台设备」。
本项目曾出现过的同类坑：SSDP 的「刷新」逻辑其实是在自造异常。

    python mdns_reply_port_check.py
"""
import socket, struct, sys, time
try: sys.stdout.reconfigure(encoding="utf-8")
except Exception: pass

GROUP, PORT = "224.0.0.251", 5353

def enc(name):
    out = b""
    for p in name.rstrip(".").split("."):
        b = p.encode("utf-8"); out += bytes([len(b)]) + b
    return out + b"\x00"

# 监听 5353（收组播 + 收回到 5353 的单播答复）
msock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
msock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
msock.bind(("", PORT))
msock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                 struct.pack("4sl", socket.inet_aton(GROUP), socket.INADDR_ANY))
msock.settimeout(0.4)

# 查询 socket：临时端口，带 QU 位
qsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
qsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
qsock.bind(("", 0))
qsock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
qsock.settimeout(0.4)
qport = qsock.getsockname()[1]

q = enc("_airplay._tcp.local") + struct.pack("!HH", 12, 0x8001)
qsock.sendto(struct.pack("!HHHHHH", 0, 0, 1, 0, 0, 0) + q, (GROUP, PORT))
print(f"[i] 查询源端口 {qport}（带 QU 位 -> 请求单播答复）\n")

seen = {}
end = time.time() + 5
while time.time() < end:
    for tag, s in (("组播(5353)", msock), ("单播(临时)", qsock)):
        try: data, addr = s.recvfrom(9000)
        except socket.timeout: continue
        if len(data) < 12: continue
        flags = struct.unpack("!H", data[2:4])[0]
        if not flags & 0x8000: continue
        key = (addr[0], addr[1])
        if key not in seen:
            an = struct.unpack("!H", data[6:8])[0]
            seen[key] = (tag, an)
            warn = ""
            if tag == "单播(临时)" and addr[1] != PORT:
                warn = "   <<< 源端口不是 5353，严格客户端会丢弃！"
            print(f"回复来自 {addr[0]}:{addr[1]}  通道={tag}  应答记录 {an} 条{warn}")

print("\n=== 判定 ===")
if not seen:
    print("没收到任何回复")
bad = [k for k, v in seen.items() if v[0] == "单播(临时)" and k[1] != PORT]
if bad:
    print("有设备用非 5353 源端口回单播答复：", ", ".join(f"{ip}:{p}" for ip, p in bad))
else:
    print("收到的单播答复源端口均为 5353（或只有组播答复）")
