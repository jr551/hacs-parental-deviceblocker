#!/usr/bin/env python3
"""Capture Home Assistant UI screenshots for README documentation."""

from __future__ import annotations

import asyncio
from pathlib import Path

import aiohttp
from playwright.async_api import async_playwright

HA_URL = "http://127.0.0.1:8123"
USERNAME = "demo"
PASSWORD = "DemoPass123!"
OUT = Path(__file__).resolve().parents[1] / "docs" / "screenshots"


async def _tokens(session: aiohttp.ClientSession) -> dict:
    async with session.post(
        f"{HA_URL}/auth/login_flow",
        json={
            "client_id": f"{HA_URL}/",
            "handler": ["homeassistant", None],
            "redirect_uri": f"{HA_URL}/",
        },
    ) as response:
        flow = await response.json()
    async with session.post(
        f"{HA_URL}/auth/login_flow/{flow['flow_id']}",
        json={
            "client_id": f"{HA_URL}/",
            "username": USERNAME,
            "password": PASSWORD,
        },
    ) as response:
        result = await response.json()
    if result.get("type") != "create_entry":
        raise RuntimeError(f"login failed: {result}")
    async with session.post(
        f"{HA_URL}/auth/token",
        data={
            "grant_type": "authorization_code",
            "code": result["result"],
            "client_id": f"{HA_URL}/",
        },
    ) as response:
        payload = await response.json()
    return payload


async def _access_token(session: aiohttp.ClientSession) -> str:
    return (await _tokens(session))["access_token"]


async def _finish_onboarding(session: aiohttp.ClientSession) -> None:
    token = await _access_token(session)
    headers = {"Authorization": f"Bearer {token}"}
    async with session.get(f"{HA_URL}/api/onboarding") as response:
        steps = await response.json()
    if any(step["step"] == "integration" and not step["done"] for step in steps):
        async with session.post(
            f"{HA_URL}/api/onboarding/integration",
            headers=headers,
            json={"client_id": f"{HA_URL}/", "redirect_uri": f"{HA_URL}/"},
        ) as response:
            await response.text()


async def _ensure_device(session: aiohttp.ClientSession, token: str) -> None:
    headers = {"Authorization": f"Bearer {token}"}
    async with session.post(
        f"{HA_URL}/api/config/config_entries/flow",
        headers=headers,
        json={"handler": "rowe_pc_blocker"},
    ) as response:
        flow = await response.json()
    if flow.get("type") != "form":
        return
    async with session.post(
        f"{HA_URL}/api/config/config_entries/flow/{flow['flow_id']}",
        headers=headers,
        json={
            "device_id": "demo-child-pc",
            "device_name": "Demo Child PC",
            "device_type": "windows",
            "windows_username": "child",
            "api_key": "demo-api-key-replace-in-production-32chars",
        },
    ) as response:
        await response.json()


async def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    async with aiohttp.ClientSession() as session:
        await _finish_onboarding(session)
        await _ensure_device(session, await _access_token(session))
        token_payload = await _tokens(session)

    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True)
        context = await browser.new_context(viewport={"width": 1360, "height": 900})
        page = await context.new_page()
        await page.goto(HA_URL, wait_until="domcontentloaded")
        await page.evaluate(
            """(tokens) => {
                localStorage.setItem('hassTokens', JSON.stringify({
                    access_token: tokens.access_token,
                    refresh_token: tokens.refresh_token,
                    expires: Date.now() + tokens.expires_in * 1000,
                    hassUrl: tokens.hass_url,
                    clientId: tokens.client_id,
                }));
            }""",
            {
                "access_token": token_payload["access_token"],
                "refresh_token": token_payload["refresh_token"],
                "expires_in": token_payload.get("expires_in", 1800),
                "hass_url": f"{HA_URL}/",
                "client_id": f"{HA_URL}/",
            },
        )

        await page.goto(f"{HA_URL}/config/integrations/dashboard", wait_until="networkidle")
        await page.screenshot(path=str(OUT / "integrations-dashboard.png"), full_page=True)

        async with aiohttp.ClientSession() as session:
            token = await _access_token(session)
            headers = {"Authorization": f"Bearer {token}"}
            async with session.post(
                f"{HA_URL}/api/config/config_entries/flow",
                headers=headers,
                json={"handler": "rowe_pc_blocker"},
            ) as response:
                flow = await response.json()
            if flow.get("type") == "form":
                await page.goto(
                    f"{HA_URL}/config/integrations/dashboard",
                    wait_until="networkidle",
                )
                await page.evaluate(
                    "window.history.pushState({}, '', '/config/integrations/dashboard');"
                )
                await page.wait_for_timeout(500)
                await page.screenshot(
                    path=str(OUT / "config-flow-picker.png"), full_page=True
                )
                async with session.post(
                    f"{HA_URL}/api/config/config_entries/flow/{flow['flow_id']}",
                    headers=headers,
                    json={
                        "device_id": "demo-child-pc-2",
                        "device_name": "Second Demo PC",
                        "device_type": "windows",
                        "windows_username": "child",
                        "api_key": "second-demo-api-key-32-characters-xx",
                    },
                ) as response:
                    await response.json()

        await page.goto(
            f"{HA_URL}/config/integrations/integration/rowe_pc_blocker",
            wait_until="networkidle",
        )
        await page.screenshot(path=str(OUT / "integration-device-list.png"), full_page=True)

        async with aiohttp.ClientSession() as session:
            token = await _access_token(session)
            headers = {"Authorization": f"Bearer {token}"}
            async with session.post(
                f"{HA_URL}/api/config/config_entries/flow",
                headers=headers,
                json={"handler": "rowe_pc_blocker"},
            ) as response:
                flow = await response.json()
        if flow.get("type") == "form":
            await page.goto(
                f"{HA_URL}/config/integrations/dashboard",
                wait_until="networkidle",
            )
            await page.screenshot(
                path=str(OUT / "config-flow-add-device.png"), full_page=True
            )

        async with aiohttp.ClientSession() as session:
            token = await _access_token(session)
            headers = {"Authorization": f"Bearer {token}"}
            async with session.get(
                f"{HA_URL}/api/config/config_entries/entry",
                headers=headers,
            ) as response:
                entries = await response.json()
            entry_id = None
            if isinstance(entries, list):
                entry_id = next(
                    (
                        item["entry_id"]
                        for item in entries
                        if item.get("domain") == "rowe_pc_blocker"
                    ),
                    None,
                )
            if entry_id:
                await page.goto(
                    f"{HA_URL}/config/integrations/integration/rowe_pc_blocker",
                    wait_until="networkidle",
                )
                configure = page.get_by_role("button", name="Configure")
                if await configure.count():
                    await configure.first.click()
                    await page.wait_for_timeout(2000)
                await page.screenshot(path=str(OUT / "options-flow.png"), full_page=True)

        await page.goto(f"{HA_URL}/config/devices/dashboard", wait_until="networkidle")
        device = page.get_by_text("Demo Child PC").first
        if await device.count():
            await device.click()
            await page.wait_for_timeout(1500)
            await page.screenshot(path=str(OUT / "device-entities.png"), full_page=True)

        await page.goto(f"{HA_URL}/lovelace/0", wait_until="networkidle")
        await page.screenshot(path=str(OUT / "dashboard-default.png"), full_page=True)

        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
