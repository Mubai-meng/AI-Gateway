#!/usr/bin/env python3
"""OpenAI 兼容桩：只为端到端验证网关链路，不参与生产。

覆盖四条被网关真实调用的端点：
  GET  /v1/models                模型目录（含一个图像模型名，供界面「拉取」用）
  POST /v1/chat/completions      非流式 + 流式（stream=true 回 SSE）
  POST /v1/images/generations    出图。按请求的 response_format 回 b64 或外链
  POST /v1/audio/transcriptions  multipart 转写，回固定中文文本

每次请求都往 stdout 打一行 `[stub] <method> <path> <关键字段>`，
于是「网关到底有没有把请求发到上游、发了什么参数」有独立证据，
不是靠网关自己的调用记录自说自话。
"""

import base64
import json
import re
import struct
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 1x1 透明 PNG：真实可解码的图，前端 <img> 能渲染
PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

MODELS = ["stub-chat", "stub-image-1", "stub-audio"]


def log(msg: str) -> None:
    print(f"[stub] {msg}", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 压掉默认 access 日志，只留自己的
        pass

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth(self) -> str:
        return self.headers.get("Authorization", "")

    def do_GET(self):
        if self.path.split("?")[0].rstrip("/").endswith("/models"):
            log(f"GET {self.path} auth={self._auth()[:14]}")
            self._json(200, {
                "object": "list",
                "data": [{"id": m, "object": "model", "owned_by": "stub"} for m in MODELS],
            })
            return
        self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))

        if path.endswith("/chat/completions"):
            self._chat(raw)
        elif path.endswith("/images/generations"):
            self._image(raw)
        elif path.endswith("/audio/transcriptions"):
            self._audio(raw)
        else:
            log(f"POST {path} <- 未实现的端点")
            self._json(404, {"error": {"message": f"stub 未实现 {path}"}})

    # ---------------------------------------------------------------- chat

    def _chat(self, raw: bytes):
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._json(400, {"error": {"message": "bad json"}})
            return
        model = req.get("model", "")
        stream = bool(req.get("stream"))
        log(f"POST /v1/chat/completions model={model} stream={stream}")

        # 上游要求显式模型名：空模型直接拒，模拟真实中转站的行为
        if not model:
            self._json(400, {"error": {"message": "model is required"}})
            return
        # 不是图像模型却被要求出图时（网关选错模型的现场），上游同样拒
        if model in ("stub-chat", "stub-audio"):
            if req.get("purpose") == "image":
                self._json(400, {"error": {"message": f"{model} 不是图像模型"}})
                return

        text = f"stub 收到：{model}"
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            for piece in (text[:6], text[6:]):
                chunk = {
                    "id": "chatcmpl-stub", "object": "chat.completion.chunk", "model": model,
                    "choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}],
                }
                self.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode("utf-8"))
                self.wfile.flush()
            done = {
                "id": "chatcmpl-stub", "object": "chat.completion.chunk", "model": model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            }
            self.wfile.write(f"data: {json.dumps(done)}\n\n".encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.close_connection = True
            return

        self._json(200, {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "created": 1700000000,
            "model": model,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": text},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7},
        })

    # --------------------------------------------------------------- image

    def _image(self, raw: bytes):
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            self._json(400, {"error": {"message": "bad json"}})
            return
        model = req.get("model", "")
        fmt = req.get("response_format", "url")
        log(
            "POST /v1/images/generations "
            f"model={model} n={req.get('n')} size={req.get('size')} "
            f"response_format={fmt} quality={req.get('quality')} prompt={str(req.get('prompt'))[:20]}"
        )
        if not model:
            self._json(400, {"error": {"message": "model is required"}})
            return
        # 只有 stub-image-1 能出图：网关若把文本模型塞过来，这里就是那条独立证据
        if model != "stub-image-1":
            self._json(400, {"error": {"message": f"{model} 不是图像模型"}})
            return
        n = int(req.get("n") or 1)
        items = []
        for i in range(max(1, min(n, 4))):
            if fmt == "b64_json":
                items.append({"b64_json": base64.b64encode(PNG_1PX).decode("ascii"),
                              "revised_prompt": "stub 改写后的提示词"})
            else:
                items.append({"url": f"http://127.0.0.1:{self.server.server_address[1]}/img/{i}.png",
                              "revised_prompt": "stub 改写后的提示词"})
        self._json(200, {"created": 1700000000, "data": items})

    # --------------------------------------------------------------- audio

    def _audio(self, raw: bytes):
        ctype = self.headers.get("Content-Type", "")
        boundary = ""
        m = re.search(r'boundary=([^;]+)', ctype)
        if m:
            boundary = m.group(1).strip().strip('"')
        # 用原始字节抓字段名/文件名：任何 UTF-8 重编码都会改写 0x80 以上
        fields = {}
        names = re.findall(rb'name="([^"]+)"', raw)
        for name in names:
            fields[name.decode("utf-8", "replace")] = True
        fname = re.search(rb'filename="([^"]+)"', raw)
        size = len(raw)
        log(
            f"POST /v1/audio/transcriptions ctype={ctype[:40]} boundary={'Y' if boundary else 'N'} "
            f"fields={sorted(fields)} file={fname.group(1).decode('utf-8', 'replace') if fname else '-'} "
            f"bytes={size}"
        )
        if not boundary:
            self._json(400, {"error": {"message": "missing multipart boundary"}})
            return
        if b'name="file"' not in raw:
            self._json(400, {"error": {"message": "missing file part"}})
            return
        self._json(200, {"text": "这是一段桩生成的转写文本，用于验证 multipart 被逐字节转发。",
                         "language": "zh", "duration": 1.25})


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18711
    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    srv.daemon_threads = True
    log(f"listening on 127.0.0.1:{port} models={MODELS}")
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
