#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 HTTPS 测试流转成局域网 HTTP 明文，供时间错乱的电视拉取。

为什么需要它
------------
那台 Android 9 电视的 NTP 时好时坏，时间一复位（实测掉到 2009-01-01）就会让
HTTPS 证书校验失败：

    CertificateException: Unacceptable certificate: CN=Certainly Intermediate R1

这时"播放失败"是**测试环境的锅**，不是接收端代码的问题。用它把
`https://test-streams.mux.dev/...` 在局域网里以 http 明文再暴露一次，
就能把协议/播放链路和证书链路分开验证。

路径按前缀拼接，所以 m3u8 里的**相对分片地址**也会自动落到本机，
不需要重写播放列表内容。

用法：
    python tools/http_proxy_server.py [端口]
    # 然后：http://<本机IP>:8899/x36xhzz.m3u8
"""

import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = "https://test-streams.mux.dev/x36xhzz/"
PASS_HEADERS = ("Content-Type", "Content-Length", "Content-Range",
                "Accept-Ranges", "ETag", "Last-Modified")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[proxy] %s\n" % (fmt % args))

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only: bool) -> None:
        url = UPSTREAM + self.path.split("?", 1)[0].lstrip("/")
        req = urllib.request.Request(url, headers={"User-Agent": "CastHub-Test/1.0"})
        rng = self.headers.get("Range")
        if rng:
            req.add_header("Range", rng)

        try:
            resp = urllib.request.urlopen(req, timeout=30)
            body = resp.read()
            self.send_response(resp.status)
            for k in PASS_HEADERS:
                v = resp.headers.get(k)
                if v:
                    self.send_header(k, v)
            self.end_headers()
            if not head_only:
                self.wfile.write(body)
        except Exception as e:  # noqa: BLE001 - 诊断用，失败信息直接回给调用方
            self.send_error(502, "upstream failed: %s" % e)


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    print("proxying %s  ->  http://0.0.0.0:%d" % (UPSTREAM, port))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
