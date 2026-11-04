#!/usr/bin/env python3
"""Deterministic OpenAI-compatible chat server for the emulator e2e job.

No API key. Response bodies are canned. Request bodies are not written to the log.
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CRISIS = (
    "自杀",
    "自残",
    "割腕",
    "不想活了",
    "跳楼",
    "轻生",
    "上吊",
    "安眠药",
    "活着没意思",
)
FINAL = "算式的结果是 4。先想一想为什么。"
NEXT_STEP = "下一步：把两个 2 相加。"
STEM = "题干：2+2。请确认。"
SOCRATIC = "想一想，这一步可以怎么做？"
STATS = {"requests": 0, "crisis_reached_model": False}


def flatten(content):
    text_parts = []
    image = False
    if isinstance(content, str):
        text_parts.append(content)
    elif isinstance(content, list):
        for part in content:
            if not isinstance(part, dict):
                continue
            kind = part.get("type")
            if kind in ("image_url", "image", "input_image"):
                image = True
            if kind in ("text", "input_text") or "text" in part:
                value = part.get("text")
                if isinstance(value, str):
                    text_parts.append(value)
            url = part.get("image_url")
            if isinstance(url, dict) or isinstance(url, str):
                image = True
    return "\n".join(text_parts), image


def classify(messages):
    crisis = False
    last_role = ""
    last_text = ""
    last_image = False
    for message in messages:
        if not isinstance(message, dict):
            continue
        blob = json.dumps(message, ensure_ascii=False)
        if any(word in blob for word in CRISIS):
            crisis = True
        text, image = flatten(message.get("content"))
        last_role = message.get("role") or ""
        last_text = text
        last_image = image
    return last_role, last_text, last_image, crisis


def decide(body):
    messages = body.get("messages") if isinstance(body, dict) else None
    if not isinstance(messages, list):
        messages = []
    role, text, image, crisis = classify(messages)
    if crisis:
        STATS["crisis_reached_model"] = True
    STATS["requests"] += 1
    if role == "tool":
        shown = text.strip() or "?"
        return ("text", f"算式的结果是 {shown}。先想一想为什么。")
    if image:
        return ("text", STEM)
    lowered = text.lower()
    if "again" in lowered:
        return ("text", NEXT_STEP)
    if "2+2" in text or "2 + 2" in text:
        return ("tool", "2+2")
    return ("text", SOCRATIC)


def chunk(delta, finish):
    return {
        "id": "chatcmpl-e2e",
        "object": "chat.completion.chunk",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Status line only. Never the request body.
        print("fake-model", self.command, self.path.split("?", 1)[0], flush=True)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/healthz":
            self._json({"ok": True})
            return
        if path == "/e2e/stats":
            self._json(dict(STATS))
            return
        if path in ("/v1/models", "/models"):
            self._json({"object": "list", "data": [{"id": "k12-e2e-model"}]})
            return
        self.send_error(404)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b""
        if not path.rstrip("/").endswith("/chat/completions"):
            self.send_error(404)
            return
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self.send_error(400)
            return
        kind, payload = decide(body)
        stream = body.get("stream", True)
        if stream:
            self._sse(kind, payload)
        else:
            self._json(self._completion(kind, payload))

    def _completion(self, kind, payload):
        if kind == "tool":
            message = {
                "role": "assistant",
                "content": None,
                "tool_calls": [
                    {
                        "id": "call_calc",
                        "type": "function",
                        "function": {
                            "name": "k12_calc",
                            "arguments": json.dumps({"expression": payload}),
                        },
                    }
                ],
            }
            finish = "tool_calls"
        else:
            message = {"role": "assistant", "content": payload}
            finish = "stop"
        return {
            "id": "chatcmpl-e2e",
            "object": "chat.completion",
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
        }

    def _sse(self, kind, payload):
        if kind == "tool":
            pieces = [
                chunk({"role": "assistant", "content": ""}, None),
                chunk(
                    {
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call_calc",
                                "type": "function",
                                "function": {"name": "k12_calc", "arguments": ""},
                            }
                        ]
                    },
                    None,
                ),
                chunk(
                    {
                        "tool_calls": [
                            {
                                "index": 0,
                                "function": {
                                    "arguments": json.dumps({"expression": payload})
                                },
                            }
                        ]
                    },
                    None,
                ),
                chunk({}, "tool_calls"),
            ]
        else:
            pieces = [
                chunk({"role": "assistant", "content": ""}, None),
                chunk({"content": payload}, None),
                chunk({}, "stop"),
            ]
        data = b"".join(
            ("data: " + json.dumps(piece, ensure_ascii=False) + "\n\n").encode("utf-8")
            for piece in pieces
        )
        data += b"data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _json(self, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18082)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
