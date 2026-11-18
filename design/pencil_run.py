"""Pencil.dev MCP stdio 客户端：绕开宿主未连接的 MCP，直连本地 editor transport 操作 .pen 文件。

用法：
  python pencil_run.py design     从 make_ui.js 执行 batch_design
  python pencil_run.py state      打印编辑器状态
  python pencil_run.py shot       截图 document 存为 preview.png
  python pencil_run.py export     导出 document 为 PNG
  python pencil_run.py call <tool> '<json arguments>'
"""
import base64
import json
import os
import queue
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
PEN = os.path.join(ROOT, "ai-gateway-ui.pen")
EXE = r"C:\Program Files\Pencil\resources\app.asar.unpacked\out\mcp-server-windows-x64.exe"
ARGS = ["--app", "desktop", "--agent", "claudeDesktop"]


class MCP:
    def __init__(self):
        self.p = subprocess.Popen(
            [EXE] + ARGS, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1, encoding="utf-8", errors="replace"
        )
        self.q = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.seq = 0

    def _read(self):
        for line in self.p.stdout:
            self.q.put(line.strip())

    def _send(self, obj):
        self.p.stdin.write(json.dumps(obj) + "\n")
        self.p.stdin.flush()

    def _wait(self, msg_id, timeout=90):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.q.get(timeout=0.4)
            except queue.Empty:
                continue
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj.get("id") == msg_id:
                return obj
        return None

    def start(self):
        self.seq += 1
        self._send({
            "jsonrpc": "2.0", "id": self.seq, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                       "clientInfo": {"name": "workbuddy", "version": "1.0"}},
        })
        self._wait(self.seq)
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}})

    def call(self, name, arguments, timeout=90):
        self.seq += 1
        self._send({"jsonrpc": "2.0", "id": self.seq, "method": "tools/call",
                    "params": {"name": name, "arguments": arguments}})
        return self._wait(self.seq, timeout)

    def stop(self):
        try:
            self.p.terminate()
            self.p.wait(timeout=3)
        except Exception:
            self.p.kill()


def show(resp, limit=4000):
    print(json.dumps(resp, ensure_ascii=False)[:limit] if resp else "TIMEOUT")


def save_images(resp, prefix):
    if not resp or "result" not in resp:
        return 0
    n = 0
    for item in resp["result"].get("content", []):
        if item.get("type") == "image":
            n += 1
            out = os.path.join(ROOT, f"{prefix}-{n}.png")
            with open(out, "wb") as f:
                f.write(base64.b64decode(item["data"]))
            print("saved", out)
    return n


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    m = MCP()
    m.start()
    try:
        if cmd == "design":
            jsfile = sys.argv[2] if len(sys.argv) > 2 else "make_ui.js"
            state = m.call("batch_get", {"filePath": PEN, "readDepth": 1})
            try:
                nodes = json.loads(state["result"]["content"][0]["text"])
            except Exception:
                nodes = []
            prefix = "".join('Delete("%s");\n' % n["id"] for n in nodes if isinstance(n, dict) and n.get("id"))
            js = prefix + open(os.path.join(ROOT, jsfile), encoding="utf-8").read()
            show(m.call("batch_design", {"filePath": PEN, "input": js}))
        elif cmd == "state":
            show(m.call("get_editor_state", {"include_schema": False}))
        elif cmd == "shot":
            node = sys.argv[2] if len(sys.argv) > 2 else "document"
            if node == "document":
                state = m.call("batch_get", {"filePath": PEN, "readDepth": 1})
                try:
                    node = json.loads(state["result"]["content"][0]["text"])[0]["id"]
                except Exception:
                    pass
            r = m.call("get_screenshot", {"filePath": PEN, "nodeId": node})
            if save_images(r, "preview") == 0:
                show(r)
        elif cmd == "export":
            node = sys.argv[2] if len(sys.argv) > 2 else "document"
            show(m.call("export_nodes", {"filePath": PEN, "nodeIds": [node], "outputDir": ROOT,
                                         "format": "png", "scale": 2}))
        elif cmd == "call":
            show(m.call(sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}))
        else:
            print("unknown command", cmd)
    finally:
        m.stop()


if __name__ == "__main__":
    main()
