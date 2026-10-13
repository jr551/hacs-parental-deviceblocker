#!/usr/bin/env python3
"""Minimal Home Assistant WebSocket helper for demo setup."""

from __future__ import annotations

import asyncio
import json
import os
from typing import Any

import aiohttp

HA = os.environ.get("PDB_HA_URL", "http://127.0.0.1:8123")
_USERNAME = os.environ.get("PDB_HA_USERNAME", "")
_PASSWORD = os.environ.get("PDB_HA_PASSWORD", "")


async def ws_call(messages: list[dict[str, Any]]) -> list[Any]:
    results: list[Any] = []
    async with aiohttp.ClientSession() as session:
        async with session.ws_connect(f"{HA.replace('http', 'ws')}/api/websocket") as ws:
            hello = await ws.receive_json()
            assert hello["type"] == "auth_required"
            await ws.send_json({"type": "auth", "access_token": await _token(session)})
            auth = await ws.receive_json()
            assert auth["type"] == "auth_ok"
            msg_id = 1
            for message in messages:
                payload = dict(message)
                payload["id"] = msg_id
                await ws.send_json(payload)
                while True:
                    data = await ws.receive_json()
                    if data.get("id") == msg_id:
                        results.append(data)
                        break
                msg_id += 1
    return results


async def _token(session: aiohttp.ClientSession) -> str:
    async with session.post(
        f"{HA}/auth/login_flow",
        json={"client_id": f"{HA}/", "handler": ["homeassistant", None], "redirect_uri": f"{HA}/"},
    ) as response:
        flow = await response.json()
    step = flow["flow_id"]
    async with session.post(
        f"{HA}/auth/login_flow/{step}",
        json={
            "client_id": f"{HA}/",
            "username": _USERNAME,
            "password": _PASSWORD,
        },
    ) as response:
        result = await response.json()
    if result.get("type") != "create_entry":
        raise RuntimeError(f"login failed: {result}")
    code = result["result"]
    async with session.post(
        f"{HA}/auth/token",
        data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": f"{HA}/",
        },
    ) as response:
        token_payload = await response.json()
    return token_payload["access_token"]


async def ensure_integration() -> None:
    results = await ws_call(
        [
            {
                "type": "config_entries/flow/create",
                "handler": "rowe_pc_blocker",
                "show_advanced_options": False,
            }
        ]
    )
    flow = results[0]["result"]
    flow_id = flow["flow_id"]
    await ws_call(
        [
            {
                "type": "config_entries/flow/submit",
                "flow_id": flow_id,
                "user_input": {
                    "device_id": "demo-child-pc",
                    "device_name": "Demo Child PC",
                    "device_type": "windows",
                    "windows_username": "child",
                    "api_key": "demo-api-key-replace-in-production-32chars",
                },
            }
        ]
    )


if __name__ == "__main__":
    asyncio.run(ensure_integration())
