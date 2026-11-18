#!/usr/bin/env python3
"""为「自定义供应商出图」写一个只回 base64 的桩，验证降级标注。

主桩（openai_stub.py）按 response_format 回外链，覆盖不了「上游只会 b64」这一支。
Antigravity 是那一支的提供者，但本机 0 账号，所以这里造一个只回 b64 的
OpenAI 兼容桩——口径与真实上游一致：**无视 response_format，永远回 b64_json**。

用法：python3 b64_only_stub.py <port>
"""
import base64
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


def log(msg: str) -> None:
    print(f"[b64only] {msg}", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.split("?")[0].rstrip("/").endswith("/models"):
            self._json(200, {"object": "list", "data": [
                {"id": "b64-image-1", "object": "model"},
                {"id": "b64-chat", "object": "model"},
            ]})
            return
        self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if not path.endswith("/images/generations"):
            self._json(404, {"error": {"message": f"未实现 {path}"}})
            return
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._json(400, {"error": {"message": "bad json"}})
            return
        log(f"POST /v1/images/generations model={req.get('model')} "
            f"response_format={req.get('response_format')} (本桩无视它，只回 b64)")
        if req.get("model") != "b64-image-1":
            self._json(400, {"error": {"message": f"{req.get('model')} 不是图像模型"}})
            return
        self._json(200, {
            "created": 1700000000,
            "data": [{"b64_json": base64.b64encode(PNG_1PX).decode("ascii")}],
        })


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18712
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    log(f"listening on 127.0.0.1:{port}")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
