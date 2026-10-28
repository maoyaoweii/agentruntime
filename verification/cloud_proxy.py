#!/usr/bin/env python3
"""把模拟器发往 10.0.2.2 的请求转发到真实讯飞 MaaS 端点。

用途：沙箱里模拟器 guest 无出网 NAT，但宿主机可访问 xf-yun。
模拟器访问 10.0.2.2:<port> 即宿主机；本代理在宿主机侧把请求原样转发到
https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions，
并补上 Authorization 头（Agent 里也会带，这里做兜底）。

只做最小转发，不记录请求正文，避免把密钥/对话写进日志。
"""
import http.server
import urllib.request
import urllib.error
import os
import sys
import time

UPSTREAM = "https://maas-api.cn-huabei-1.xf-yun.com/v2/chat/completions"
# 密钥从环境变量读取，绝不写进仓库。运行前：export MAAS_API_KEY=...
API_KEY = os.environ.get("MAAS_API_KEY", "")
PORT = 8900


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _forward(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length) if length else b""
        req = urllib.request.Request(UPSTREAM, data=body, method=self.command)
        req.add_header("Content-Type", "application/json")
        auth = self.headers.get("Authorization")
        req.add_header("Authorization", auth if auth else "Bearer " + API_KEY)
        # 上游偶发 RemoteDisconnected / 读超时：实测连跑时第 3~5 次规划会稳定失败。
        # 单次重试即可覆盖这类瞬断，且不改变 HTTPError（429/500 等真实状态码）的透传语义。
        last = None
        for attempt in (0, 1):
            try:
                with urllib.request.urlopen(req, timeout=200) as r:
                    data = r.read()
                    self.send_response(r.status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                return
            except urllib.error.HTTPError as e:
                data = e.read()
                self.send_response(e.code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            except Exception as e:  # 只对非 HTTP 状态码的传输层异常重试
                last = e
                sys.stderr.write("upstream attempt %d failed: %s: %s\n"
                                 % (attempt, type(e).__name__, e))
                sys.stderr.flush()
                if attempt == 0:
                    time.sleep(3)
        msg = ('{"error":"proxy_upstream_failed","detail":"%s"}' % type(last).__name__).encode()
        self.send_response(502)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(msg)))
        self.end_headers()
        self.wfile.write(msg)

    def do_POST(self):
        self._forward()

    def do_GET(self):
        self._forward()


if __name__ == "__main__":
    # ThreadingHTTPServer：Agent 用 HTTP/1.1 keep-alive，单线程 server 会被空闲长连接堵死，
    # 表现为后续请求 ConnectException（连接建不起来），而单发一次却正常。
    http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
