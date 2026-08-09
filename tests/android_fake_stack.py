#!/usr/bin/env python3
"""Small fake Home Assistant and S3 surface for Android emulator testing.

This intentionally uses a public test key and stores only synthetic uploads.
Change the active policy by writing ``unblocked``, ``blocked``, ``malformed``
or ``error`` to the mode file passed on the command line.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import ssl
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse


class FakeStackHandler(BaseHTTPRequestHandler):
    server_version = "DeviceBlockerFakeStack/1"

    def log_message(self, format: str, *args: object) -> None:
        return

    def _json(self, status: int, body: dict[str, object]) -> None:
        encoded = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def _authorized(self) -> bool:
        return self.headers.get("X-Device-Blocker-Key") == self.server.device_key

    def _record(self, event: dict[str, object]) -> None:
        with self.server.events.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(event, sort_keys=True) + "\n")

    def _endpoint(self) -> str | None:
        prefix = f"/api/rowe_pc_blocker/{self.server.device_id}/"
        path = urlparse(self.path).path
        return path[len(prefix) :] if path.startswith(prefix) else None

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path.startswith("/control/"):
            mode = path.rsplit("/", 1)[-1]
            if mode not in {"unblocked", "blocked", "malformed", "error"}:
                self._json(400, {"error": "invalid mode"})
                return
            self.server.mode_file.write_text(mode + "\n", encoding="utf-8")
            self._json(200, {"mode": mode})
            return
        endpoint = self._endpoint()
        if endpoint is None or not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        mode = self.server.mode_file.read_text(encoding="utf-8").strip()
        if endpoint == "state":
            if mode == "error":
                self._json(503, {"error": "synthetic failure"})
            elif mode == "malformed":
                self._json(200, {"device_id": self.server.device_id, "blocked": "yes"})
            else:
                blocked = mode == "blocked"
                self._json(
                    200,
                    {
                        "device_id": self.server.device_id,
                        "blocked": blocked,
                        "block_requested": blocked,
                        "extension_available": False,
                        "message": "Synthetic lab policy",
                        "enforce_at": "2026-08-09T10:00:00Z" if blocked else None,
                        "extension_until": None,
                    },
                )
            return
        if endpoint == "backup/config":
            self._json(
                200,
                {
                    "enabled": True,
                    "initial_sync_wifi_only": True,
                    "requires_external_power": True,
                    "max_file_bytes": 10 * 1024 * 1024,
                    "configuration_id": "synthetic-destination-v1",
                },
            )
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        endpoint = self._endpoint()
        if endpoint is None or not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        try:
            payload = json.loads(body or b"{}")
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid json"})
            return
        if endpoint == "backup/presign":
            size = int(payload.get("size", -1))
            name = hashlib.sha256(
                str(payload.get("relative_path", "")).encode()
                + b"/"
                + str(payload.get("display_name", "")).encode()
            ).hexdigest()[:20]
            self._record({"event": "presign", "name": name, "size": size})
            self._json(
                200,
                {
                    "url": f"https://10.0.2.2:{self.server.server_port}/upload/{name}",
                    "headers": {"Content-Length": str(size)},
                },
            )
            return
        if endpoint in {"backup/status", "activity"}:
            self._record({"event": endpoint, "payload": payload})
            self._json(200, {"ok": True})
            return
        self._json(404, {"error": "not found"})

    def do_PUT(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if not path.startswith("/upload/"):
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "-1"))
        remaining = max(length, 0)
        chunks: list[bytes] = []
        while remaining:
            chunk = self.rfile.read(min(64 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
            if self.server.upload_chunk_delay_ms:
                time.sleep(self.server.upload_chunk_delay_ms / 1000)
        body = b"".join(chunks)
        if length < 0 or len(body) != length:
            self._record(
                {
                    "event": "upload_incomplete",
                    "expected_size": length,
                    "received_size": len(body),
                }
            )
            self._json(400, {"error": "length mismatch"})
            return
        name = path.rsplit("/", 1)[-1]
        destination = self.server.uploads / name
        destination.write_bytes(body)
        self._record(
            {
                "event": "upload",
                "name": name,
                "size": length,
                "sha256": hashlib.sha256(body).hexdigest(),
            }
        )
        self._json(200, {"ok": True})


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18443)
    parser.add_argument("--cert", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--device-id", default="fake-android")
    parser.add_argument("--device-key", default="public-lab-test-key")
    parser.add_argument("--upload-chunk-delay-ms", type=int, default=0)
    args = parser.parse_args()

    args.state_dir.mkdir(parents=True, exist_ok=True)
    uploads = args.state_dir / "uploads"
    uploads.mkdir(exist_ok=True)
    mode_file = args.state_dir / "mode"
    mode_file.write_text("unblocked\n", encoding="utf-8")
    events = args.state_dir / "events.jsonl"
    events.write_text("", encoding="utf-8")

    server = ThreadingHTTPServer(("127.0.0.1", args.port), FakeStackHandler)
    server.device_id = args.device_id
    server.device_key = args.device_key
    server.mode_file = mode_file
    server.events = events
    server.uploads = uploads
    server.upload_chunk_delay_ms = max(0, args.upload_chunk_delay_ms)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
