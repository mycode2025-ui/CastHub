#!/usr/bin/env python3
"""SSDP 应答速度实测（零依赖，纯标准库）。

测的是**用户真实感受到的那个延迟**：投屏 App 发出 M-SEARCH 后，多久能收到本机应答。

为什么要这样测：
  UPnP 的 M-SEARCH 应答是**单播回请求方的源端口**，因此测试端用任意随机端口即可，
  不需要绑定 1900。这一点很关键 —— Windows 上 1900 被系统 SSDP 服务(SSDPSRV)占用，
  绑定它反而收不到组播包（系统允许多个 socket 绑定同一端口，但只会把包投给其中一个），
  很容易被误判成"设备没广播"。

  ssdp:alive 通告的频率不在这里测：同样受上面那个限制影响，且已在 App 日志里
  直接可见（"已广播 ssdp:alive N 次（间隔 1000ms）"，N 与耗时秒数一致即证明间隔正确）。

用法：
    python tools/ssdp_speed_test.py 192.168.10.205
"""

import socket
import sys
import time

SSDP_GROUP = "239.255.255.250"
SSDP_PORT = 1900
MSEARCH_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

ROUNDS = 6
TIMEOUT = 4.0


def measure_search_latency(target: str, rounds: int) -> None:
    print(f"[M-SEARCH 应答延迟] 目标 {target}，{rounds} 次，ST={MSEARCH_ST}")

    # 绑定随机端口：应答会单播回这里，无需（也不应）占用 1900
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", 0))
    sock.settimeout(TIMEOUT)

    message = (
        "M-SEARCH * HTTP/1.1\r\n"
        f"HOST: {SSDP_GROUP}:{SSDP_PORT}\r\n"
        'MAN: "ssdp:discover"\r\n'
        "MX: 1\r\n"
        f"ST: {MSEARCH_ST}\r\n"
        "\r\n"
    ).encode()

    latencies = []
    for i in range(rounds):
        sent_at = time.time()
        sock.sendto(message, (SSDP_GROUP, SSDP_PORT))

        reply_at = None
        while True:
            try:
                _, addr = sock.recvfrom(4096)
            except socket.timeout:
                break
            if addr[0] == target:
                reply_at = time.time()
                break

        if reply_at is None:
            print(f"  #{i + 1} 超时（{TIMEOUT:.0f} 秒内无应答）")
        else:
            ms = (reply_at - sent_at) * 1000
            latencies.append(ms)
            print(f"  #{i + 1} {ms:>4.0f} ms")
        time.sleep(0.4)

    sock.close()

    print()
    if latencies:
        print(f"  应答 {len(latencies)}/{rounds} 次，平均 {sum(latencies) / len(latencies):.0f} ms，"
              f"最快 {min(latencies):.0f} ms")
    else:
        print("  全部超时。逐项排查：")
        print("    1) 设备与本机是否同一网段、是否开了 AP 隔离")
        print("    2) 本机是否有 VPN / 虚拟网卡抢占默认路由")
        print("    3) 设备侧 UDP 1900 入站是否被拦（多播锁是否获取成功）")


def main() -> None:
    target = sys.argv[1] if len(sys.argv) > 1 else "192.168.10.205"
    measure_search_latency(target, ROUNDS)


if __name__ == "__main__":
    main()
