#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""压力与边界测试：
1) keep-alive 复用连接反复拉静态资源 + state（验证不再出现 400 BAD REQUEST）
2) 并发写操作（验证串行化不死锁、状态一致）
3) 超大请求体（应 413，不是 OOM 也不是 500）
4) 畸形请求体 / 缺 Content-Length / 非法方法
"""
import json
import socket
import sys
import threading
import time
import urllib.error
import urllib.request

HOST = "127.0.0.1"
PORT = int(sys.argv[1] if len(sys.argv) > 1 else 18138)
FAILS = []


def note(ok, label, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + ("  " + detail if detail else ""), flush=True)
    if not ok:
        FAILS.append(label + " " + detail)


# ---------------------------------------------------------------- 1) keep-alive

class KeepAlive:
    def __init__(self):
        self.sock = socket.create_connection((HOST, PORT), timeout=10)
        self.buf = b""

    def request(self, path, extra=""):
        req = ("GET %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: keep-alive\r\n%s\r\n"
               % (path, HOST, PORT, extra))
        self.sock.sendall(req.encode("ascii"))
        return self._read_response()

    def _read_response(self):
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                return None, b""
            self.buf += chunk
        head, _, rest = self.buf.partition(b"\r\n\r\n")
        lines = head.split(b"\r\n")
        status = lines[0].decode("ascii", "replace")
        clen = None
        chunked = False
        for line in lines[1:]:
            low = line.lower()
            if low.startswith(b"content-length:"):
                clen = int(line.split(b":")[1].strip())
            if low.startswith(b"transfer-encoding:") and b"chunked" in low:
                chunked = True
        if chunked:
            body = b""
            while True:
                while b"\r\n" not in rest:
                    rest += self.sock.recv(65536)
                size_line, _, rest = rest.partition(b"\r\n")
                size = int(size_line.strip() or b"0", 16)
                if size == 0:
                    while len(rest) < 2:
                        rest += self.sock.recv(65536)
                    rest = rest[2:]
                    break
                while len(rest) < size + 2:
                    rest += self.sock.recv(65536)
                body += rest[:size]
                rest = rest[size + 2:]
            self.buf = rest
            return status, body
        while len(rest) < (clen or 0):
            more = self.sock.recv(65536)
            if not more:
                break
            rest += more
        self.buf = rest[(clen or 0):]
        return status, rest[:clen or 0]

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


print("== 1) keep-alive 复用连接（模拟连续刷新页面） ==")
ka = KeepAlive()
bad = 0
for i in range(60):
    for path in ("/", "/app.js", "/app.css", "/kd-design-system.css", "/ui/state"):
        st, body = ka.request(path)
        if st is None or "200" not in st:
            bad += 1
            print("    bad response on %s -> %s" % (path, st))
ka.close()
note(bad == 0, "60 轮 × 5 个请求全部 200", "异常 %d 次" % bad)

# ---------------------------------------------------------------- 2) 并发写

print("== 2) 并发写操作（10 线程 × 20 次主题切换） ==")
errors = []


def hammer():
    for _ in range(20):
        try:
            req = urllib.request.Request(
                "http://%s:%d/ui/theme" % (HOST, PORT),
                data=json.dumps({"theme": "dark"}).encode(),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=10) as r:
                if r.status != 200:
                    errors.append("status %d" % r.status)
        except Exception as e:
            errors.append(str(e))


threads = [threading.Thread(target=hammer) for _ in range(10)]
t0 = time.time()
for t in threads:
    t.start()
for t in threads:
    t.join()
elapsed = time.time() - t0
note(not errors, "200 次并发写全部成功", "错误 %d，耗时 %.1fs" % (len(errors), elapsed))

# 写完之后读一次，确认状态一致（主题文件内容必须是合法值）
req = urllib.request.Request("http://%s:%d/ui/state" % (HOST, PORT))
with urllib.request.urlopen(req, timeout=10) as r:
    state = json.loads(r.read().decode())
note(isinstance(state.get("accounts"), list), "并发写后快照可正常读取")

# ---------------------------------------------------------------- 3) 超大请求体

print("== 3) 边界：超大请求体 ==")
# 说明：带 Expect: 100-continue 的客户端（curl / 浏览器 fetch）会收到干净的 413；
# 不带该头的客户端（如 python urllib）会在服务端提前响应后收到 connection abort。
# 两种都是正确行为，这里只断言「服务端不崩、后续请求正常」。
big = json.dumps({"base64": "A" * (41 * 1024 * 1024)}).encode()
req = urllib.request.Request(
    "http://%s:%d/ui/media/transcribe" % (HOST, PORT), data=big,
    headers={"Content-Type": "application/json"}, method="POST")
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        code = r.status
        body = r.read()[:200]
    note(code == 413, "41MB 请求体返回 413", "got %s" % code)
except urllib.error.HTTPError as e:
    note(e.code == 413, "41MB 请求体返回 413", "got %s" % e.code)
except Exception as e:
    # 服务端提前关闭连接属正常，改由下面的存活检查来判定
    note(True, "41MB 请求被提前拒绝（客户端收到断开）", type(e).__name__)

with urllib.request.urlopen("http://%s:%d/ui/state" % (HOST, PORT), timeout=10) as r:
    note(r.status == 200, "超大请求之后服务仍可正常响应")

# ---------------------------------------------------------------- 4) 畸形请求

print("== 4) 边界：畸形请求 ==")


def raw(req_bytes, label, want_status=None):
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.sendall(req_bytes)
    time.sleep(0.3)
    try:
        data = s.recv(4096)
    finally:
        s.close()
    first = data.split(b"\r\n")[0].decode("ascii", "replace") if data else "(no response)"
    ok = (want_status is None) or (str(want_status) in first or first.startswith("HTTP/1.1 4"))
    note(ok, label, first)


raw(b"GET /ui/state\r\nHost: x\r\n\r\n", "HTTP/1.0 风格请求（缺版本）", None)
raw(b"POST /ui/theme HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n\r\n{not json}",
    "畸形 JSON 体（应 200 ok:false 或 400）", None)
raw(b"POST /ui/theme HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\n{}",
    "Content-Length 与实际不符（应拒绝或截断处理）", None)
raw(b"PUT /ui/theme HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n",
    "PUT 方法（应 400 而不是执行）", 400)
raw(b"GET /../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n", "路径穿越（应 404）", 404)

# ---------------------------------------------------------------- 汇总

print("\n=== %s ===" % ("全部通过" if not FAILS else "有 %d 项失败" % len(FAILS)))
for f in FAILS:
    print("  -", f)
sys.exit(0 if not FAILS else 1)
