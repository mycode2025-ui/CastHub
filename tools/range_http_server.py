#!/usr/bin/env python3
"""支持 HTTP Range 的极简静态文件服务（纯标准库）。

为什么需要它
------------
Python 自带的 `http.server` **不支持 Range 请求**。播放器对 mp4 做 seek 时会发
`Range: bytes=...`，服务器却返回 200（整个文件）而不是 206（片段），
这时量出来的"快进失败"是测试环境的锅，不能反映真实情况。

用法：
    python tools/range_http_server.py [端口] [根目录]
    python tools/range_http_server.py 8899 /tmp
"""

import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.getcwd()


class RangeHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[http] %s\n" % (fmt % args))

    def do_HEAD(self):
        self._serve(head_only=True)

    def do_GET(self):
        self._serve(head_only=False)

    def _serve(self, head_only: bool) -> None:
        rel = self.path.split("?", 1)[0].lstrip("/")
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            self.send_error(404, "not found")
            return

        size = os.path.getsize(path)
        ctype = "video/mp4" if path.lower().endswith(".mp4") else "application/octet-stream"

        start, end, partial = 0, size - 1, False
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)$", rng.strip())
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(0, size - int(m.group(2)))
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                partial = True

        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        if head_only:
            return

        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def main() -> None:
    global ROOT
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    if len(sys.argv) > 2:
        ROOT = sys.argv[2]
    print(f"serving {ROOT} on 0.0.0.0:{port}（支持 Range）")
    ThreadingHTTPServer(("0.0.0.0", port), RangeHandler).serve_forever()


if __name__ == "__main__":
    main()
