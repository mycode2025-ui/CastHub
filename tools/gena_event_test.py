#!/usr/bin/env python3
"""GENA 事件推送验证（模拟 DLNA 控制端）。

流程：本机起一个回调服务 -> 向 DMR 发 SUBSCRIBE -> 等初始 NOTIFY -> 记录 -> UNSUBSCRIBE。

验证要点：
  1. SUBSCRIBE 返回 200 且带 SID / TIMEOUT；
  2. 订阅后**立刻**收到一条 SEQ=0 的初始事件（UPnP 要求）；
  3. NOTIFY 里 LastChange 是**转义后的 XML**，且能被解析出状态变量；
  4. UNSUBSCRIBE 返回 200。

用法：
    python tools/gena_event_test.py 192.168.10.205 41081
"""

import http.server
import sys
import threading
import time
from urllib.request import Request, urlopen
from xml.etree import ElementTree

LOCAL_IP = "192.168.10.40"
CALLBACK_PORT = 8731

received = []


class NotifyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_NOTIFY(self):  # noqa: N802  (方法名由 BaseHTTPRequestHandler 规定)
        length = int(self.headers.get("CONTENT-LENGTH", 0))
        body = self.rfile.read(length).decode("utf-8", "ignore")
        received.append({
            "sid": self.headers.get("SID"),
            "seq": self.headers.get("SEQ"),
            "nt": self.headers.get("NT"),
            "nts": self.headers.get("NTS"),
            "body": body,
        })
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def log_message(self, *args):
        pass


def parse_last_change(body: str) -> str:
    """把 NOTIFY 里的 <LastChange> 取出来并反转义，便于人读。"""
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError as e:
        return f"(propertyset 解析失败: {e})"
    ns = {"e": "urn:schemas-upnp-org:event-1-0"}
    node = root.find(".//e:property/LastChange", ns)
    if node is None or node.text is None:
        return "(未找到 LastChange)"
    return node.text.strip()


def main() -> None:
    target = sys.argv[1] if len(sys.argv) > 1 else "192.168.10.205"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 41081
    # 第三个参数：订阅后监听多少秒（用于在监听期间触发投屏，观察状态变化事件）
    watch_seconds = int(sys.argv[3]) if len(sys.argv) > 3 else 2

    server = http.server.HTTPServer(("0.0.0.0", CALLBACK_PORT), NotifyHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    time.sleep(0.3)

    event_url = f"http://{target}:{port}/event/AVTransport"
    callback = f"<http://{LOCAL_IP}:{CALLBACK_PORT}/notify>"

    print(f"[1] SUBSCRIBE {event_url}")
    try:
        resp = urlopen(
            Request(event_url, method="SUBSCRIBE", headers={
                "CALLBACK": callback,
                "NT": "upnp:event",
                "TIMEOUT": "Second-300",
            }),
            timeout=8,
        )
    except Exception as e:  # noqa: BLE001
        print(f"    ✗ 订阅失败：{e}")
        return

    sid = resp.headers.get("SID")
    timeout_header = resp.headers.get("TIMEOUT")
    print(f"    状态 {resp.status}  SID={sid}  TIMEOUT={timeout_header}")
    if resp.status == 200 and sid:
        print("    ✓ 订阅被接受")
    else:
        print("    ✗ 订阅响应异常")
        return
    resp.close()

    print(f"\n[2] 监听 {watch_seconds} 秒（初始事件 + 期间发生的状态变化）")
    time.sleep(watch_seconds)
    if not received:
        print(f"    ✗ {watch_seconds} 秒内没有收到任何 NOTIFY —— 事件推送未生效")
    else:
        print(f"    共收到 {len(received)} 条 NOTIFY：")
        for item in received:
            print(f"    ✓ SEQ={item['seq']}  NT={item['nt']}  NTS={item['nts']}")
            print(f"      {parse_last_change(item['body'])}")

    print(f"\n[3] UNSUBSCRIBE（SID={sid}）")
    try:
        resp2 = urlopen(
            Request(event_url, method="UNSUBSCRIBE", headers={"SID": sid or ""}),
            timeout=8,
        )
        print(f"    状态 {resp2.status}")
        resp2.close()
    except Exception as e:  # noqa: BLE001
        print(f"    ✗ 取消失败：{e}")

    print(f"\n结论：{'✅ 事件推送已生效' if received else '❌ 未收到事件，需要排查'}")


if __name__ == "__main__":
    main()
