#!/usr/bin/env python3
"""Container-only Web Push relay with an `android` sink.

Mirrors the iOS relay's public contract (push-relay/server.go):
  POST /v1/register          {device_token(hex), environment, application_server_key} -> {endpoint, environment}
  POST /v1/push/{token}      Content-Encoding: aes128gcm, TTL, Urgency, VAPID Authorization -> 201 + Location
  GET  /v1/stream/{token}    text/event-stream of {"id":..,"body":base64(ciphertext)}
  POST /v1/ack/{id}          removes a delivered message (exact acknowledgement)
  DELETE /v1/register/{token} retires a subscription
Bounded on purpose: 50 queued messages per device, TTL honoured, unknown tokens 404.
No APNs, no FCM, no network egress: this runs beside the isolated Test Jarvis node.
"""
import base64
import hashlib
import hmac
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from relay_vapid import key_thumbprint, verify_vapid  # noqa: E402
import os
import json
import queue
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

# Two views of the same relay: the node posts from inside the network, the emulator
# app streams through the emulator host alias. Both are this process.
NODE_AUDIENCE = os.environ.get("PUSH_NODE_ORIGIN", "https://localhost:8443")
APP_AUDIENCE = os.environ.get("PUSH_APP_ORIGIN", "https://10.0.2.2:8443")
AUDIENCES = {NODE_AUDIENCE, APP_AUDIENCE, "http://localhost:8081"}
MAX_QUEUE = 50
MAX_BODY = 4096

LOCK = threading.Lock()
DEVICES = {}      # token -> {"created": ts, "env": str, "key": str, "queue": [msg], "subs": set()}
MESSAGES = {}     # id -> {"token":..., "body":..., "expires": ts}


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, payload, code=200, headers=None):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    # -- routes ---------------------------------------------------------
    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/v1/register":
            return self.register()
        if path.startswith("/v1/push/"):
            return self.push(path.rsplit("/", 1)[-1])
        if path.startswith("/v1/ack/"):
            return self.ack(path.rsplit("/", 1)[-1])
        return self.send_json({"error": "not found"}, 404)

    def device_authorized(self, token) -> bool:
        with LOCK:
            device = DEVICES.get(token)
        if device is None:
            return False
        header = self.headers.get("Authorization") or ""
        if not header.startswith("Device "):
            return False
        return hmac.compare_digest(header[len("Device "):].strip(), device["secret"])

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path.startswith("/v1/register/"):
            token = path.rsplit("/", 1)[-1]
            if not self.device_authorized(token):
                return self.send_json({"error": "device credential required"}, 401)
            with LOCK:
                existed = DEVICES.pop(token, None) is not None
            print(f"relay retire token={token[:8]}…", flush=True)
            return self.send_json({"retired": token, "existed": existed}, 200 if existed else 404)
        return self.send_json({"error": "not found"}, 404)

    def do_GET(self):
        path = urlparse(self.path).path
        if path.startswith("/v1/stream/"):
            return self.stream(path.rsplit("/", 1)[-1])
        if path.startswith("/v1/queue/"):
            token = path.rsplit("/", 1)[-1]
            with LOCK:
                device = DEVICES.get(token)
                ids = [m["id"] for m in device["queue"]] if device else None
            return self.send_json({"queue": ids}, 200 if device is not None else 404)
        if path == "/healthz":
            return self.send_json({"status": "ok"})
        return self.send_json({"error": "not found"}, 404)

    # -- handlers -------------------------------------------------------
    def register(self):
        try:
            req = json.loads(self.body() or b"{}")
        except Exception:
            return self.send_json({"error": "request body must be JSON"}, 400)
        token_hex = str(req.get("device_token") or "").strip()
        try:
            raw = bytes.fromhex(token_hex)
        except ValueError:
            return self.send_json({"error": "device_token must be hex"}, 400)
        if not 32 <= len(raw) <= 128:
            return self.send_json({"error": "device_token length"}, 400)
        env = str(req.get("environment") or "").strip()
        if env != "android":
            return self.send_json(
                {"code": "unsupported_environment", "error": f"environment {env!r} is not enabled on this relay (supported: android)"},
                422,
            )
        key = str(req.get("application_server_key") or "").strip()
        if not key:
            return self.send_json({"error": "application_server_key is required"}, 400)
        try:
            thumbprint = key_thumbprint(key)
        except ValueError as error:
            return self.send_json({"error": "application_server_key: " + str(error)}, 400)
        secret = b64u(os.urandom(32))
        token = b64u(hashlib.sha256(raw + key.encode()).digest()[:24])
        with LOCK:
            previous = DEVICES.get(token)
            if previous:
                # Renewal may keep the endpoint but rotate the device capability.
                # Preserve unacknowledged messages; close old authenticated sockets.
                for inbox in list(previous["subs"]):
                    inbox.put(None)
            DEVICES[token] = {
                "created": time.time(), "env": env, "key": key, "thumbprint": thumbprint,
                "secret": secret, "queue": list(previous["queue"]) if previous else [], "subs": set(),
            }
        print(f"relay register env={env} device={token_hex[:12]}…", flush=True)
        # The endpoint is the Web Push URL a server posts to; the device secret is
        # the capability the app uses for its own stream/ack/retire calls.
        # `endpoint` is what the page enrols with its node; `stream` is what this app
        # dials through the emulator alias. Same subscription, two hostnames.
        return self.send_json({
            "endpoint": f"{NODE_AUDIENCE}/v1/push/{token}",
            "stream": f"{APP_AUDIENCE}/v1/stream/{token}",
            "environment": env,
            "device_secret": secret,
            "expires_in": 30 * 24 * 3600,
        })

    def push(self, token):
        payload = self.body()
        with LOCK:
            device = DEVICES.get(token)
        if device is None:
            return self.send_json({"error": "unknown subscription"}, 404)
        if (self.headers.get("Content-Encoding") or "").lower() != "aes128gcm":
            return self.send_json({"error": "Content-Encoding must be aes128gcm"}, 415)
        auth = self.headers.get("Authorization") or ""
        ok, reason = False, "audience mismatch"
        for audience in AUDIENCES:
            ok, reason = verify_vapid(auth, audience, device["key"])
            if ok:
                break
        if not ok:
            print(f"relay vapid reject: {reason}", flush=True)
            hint = "expired" if "expired" in reason else ("audience" if "audience" in reason else "invalid")
            return self.send_json({"error": reason}, 401, {"WWW-Authenticate": f'vapid error="{hint}"'})
        try:
            ttl = int(self.headers.get("TTL") or "0")
        except ValueError:
            return self.send_json({"error": "TTL header must be a non-negative integer"}, 400)
        ttl = max(0, min(ttl, 86400))
        if len(payload) > MAX_BODY:
            return self.send_json({"error": "payload too large"}, 413)
        message_id = str(uuid.uuid4())
        message = {
            "id": message_id,
            "body": b64u(payload),
            "topic": self.headers.get("Topic") or "",
            "urgency": self.headers.get("Urgency") or "normal",
            "expires": time.time() + ttl if ttl else 0,
        }
        with LOCK:
            device["queue"].append(message)
            if len(device["queue"]) > MAX_QUEUE:
                device["queue"] = device["queue"][-MAX_QUEUE:]
            MESSAGES[message_id] = {"token": token, "body": message["body"], "expires": message["expires"]}
            subscribers = list(device["subs"])
        for subscriber in subscribers:
            subscriber.put(message)
        print(f"relay push token={token[:8]}… id={message_id[:8]} bytes={len(payload)}", flush=True)
        return self.send_json({"id": message_id}, 201, {"Location": f"/v1/messages/{message_id}"})

    def ack(self, message_id):
        self.body()  # Consume POST bytes before reusing this HTTP/1.1 connection.
        with LOCK:
            owner = MESSAGES.get(message_id, {}).get("token")
        if owner is None:
            return self.send_json({"error": "unknown message"}, 404)
        if not self.device_authorized(owner):
            return self.send_json({"error": "device credential required"}, 401)
        with LOCK:
            MESSAGES.pop(message_id, None)
            for device in DEVICES.values():
                device["queue"] = [m for m in device["queue"] if m["id"] != message_id]
        print(f"relay ack id={message_id[:8]}", flush=True)
        return self.send_json({"acked": message_id})

    def stream(self, token):
        if not self.device_authorized(token):
            return self.send_json({"error": "device credential required"}, 401)
        with LOCK:
            device = DEVICES.get(token)
        if device is None:
            return self.send_json({"error": "unknown subscription"}, 404)
        inbox = queue.Queue()
        with LOCK:
            device["subs"].add(inbox)
            backlog = list(device["queue"])
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        print(f"relay stream open token={token[:8]}… backlog={len(backlog)}", flush=True)

        def deliver(message):
            self.wfile.write(f"data: {json.dumps(message)}\n\n".encode())
            self.wfile.flush()

        try:
            for message in backlog:
                deliver(message)
            while True:
                try:
                    message = inbox.get(timeout=20)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    if (device.get("queue") is None):
                        break
                    continue
                if message is None:
                    break
                deliver(message)
        except OSError:
            pass
        finally:
            self.close_connection = True
            with LOCK:
                device["subs"].discard(inbox)
            print(f"relay stream closed token={token[:8]}…", flush=True)


if __name__ == "__main__":
    import argparse, ssl
    parser = argparse.ArgumentParser(description="PRIVATE local Web Push fixture; not a production relay")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, args.port), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.minimum_version = ssl.TLSVersion.TLSv1_2
    tls.load_cert_chain(args.cert, args.key)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
