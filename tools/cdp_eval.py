# -*- coding: utf-8 -*-
"""最小 WebSocket + CDP 客户端（纯标准库），用来在真机 WebView 里执行 JS。

用途：ReadIt 的 EpubWebView 没有实现 onConsoleMessage，JS 报错在 logcat 里完全不可见。
这里通过 WebView 远程调试（debug 包默认开启）拿 WebSocket 调试地址，
再用 Runtime.evaluate 直接问页面真实状态。

用法：python cdp_eval.py <ws_url_or_http_base> <js_expression_file> [await_promise]
"""

import base64
import json
import os
import socket
import ssl  # noqa: F401  (kept for parity)
import struct
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")


def http_json(base):
    with urllib.request.urlopen(base.rstrip("/") + "/json", timeout=10) as r:
        return json.loads(r.read().decode("utf-8"))


class WS:
    def __init__(self, url):
        # ws://127.0.0.1:PORT/devtools/page/ID
        assert url.startswith("ws://"), url
        rest = url[len("ws://"):]
        hostport, _, path = rest.partition("/")
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=20)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET /{path} HTTP/1.1\r\n"
            f"Host: {hostport}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            "\r\n"
        )
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("ws handshake closed")
            buf += chunk
        head, _, self.rest = buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise RuntimeError("ws handshake failed: " + head.decode("latin-1")[:200])
        self.buf = self.rest

    # ---------------- framing ----------------
    def send_text(self, text):
        data = text.encode("utf-8")
        header = bytearray()
        header.append(0x81)  # FIN + text
        mask = os.urandom(4)
        n = len(data)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.sock.sendall(bytes(header) + masked)

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("ws closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv_text(self):
        while True:
            b0, b1 = self._read_exact(2)
            fin = b0 & 0x80
            opcode = b0 & 0x0F
            masked = b1 & 0x80
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read_exact(8))[0]
            if masked:
                mk = self._read_exact(4)
            payload = self._read_exact(n)
            if masked:
                payload = bytes(b ^ mk[i % 4] for i, b in enumerate(payload))
            if opcode == 0x8:
                raise RuntimeError("ws close frame")
            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
                continue
            if opcode in (0x1, 0x2):
                return payload.decode("utf-8", "replace")


def main():
    base = sys.argv[1]
    expr_file = sys.argv[2]
    await_promise = len(sys.argv) > 3 and sys.argv[3] == "1"

    targets = http_json(base)
    print("=== CDP targets ===")
    for t in targets:
        print("  type=%s title=%r url=%s" % (t.get("type"), t.get("title"), t.get("url")))
    page = None
    for t in targets:
        if t.get("type") == "page":
            page = t
            break
    if page is None:
        print("no page target")
        return
    ws_url = page["webSocketDebuggerUrl"]
    print("ws:", ws_url)

    with open(expr_file, encoding="utf-8") as f:
        expr = f.read()

    ws = WS(ws_url)
    msg = {
        "id": 1,
        "method": "Runtime.evaluate",
        "params": {
            "expression": expr,
            "returnByValue": True,
            "awaitPromise": await_promise,
            "userGesture": True,
        },
    }
    ws.send_text(json.dumps(msg))
    while True:
        raw = ws.recv_text()
        obj = json.loads(raw)
        if obj.get("id") == 1:
            print("=== Runtime.evaluate result ===")
            print(json.dumps(obj.get("result", {}), ensure_ascii=False, indent=2))
            break


if __name__ == "__main__":
    main()
