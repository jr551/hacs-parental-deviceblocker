#!/usr/bin/env python3
"""Render documentation screenshots from integration copy (demo HA styling)."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[1] / "docs" / "screenshots"
W, H = 1360, 900
BLUE = (3, 169, 244)
TEXT = (33, 33, 33)
MUTED = (97, 97, 97)
CARD = (255, 255, 255)
BG = (250, 250, 250)


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    try:
        name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
        return ImageFont.truetype(name, size=size)
    except OSError:
        return ImageFont.load_default()


def _frame(title: str, subtitle: str, fields: list[tuple[str, str]]) -> Image.Image:
    img = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, W, 64), fill=BLUE)
    draw.text((24, 18), "Home Assistant", font=_font(22, True), fill=(255, 255, 255))
    draw.text((W - 320, 22), "Parental Device Blocker", font=_font(16), fill=(255, 255, 255))
    card = (180, 120, W - 180, H - 120)
    draw.rounded_rectangle(card, radius=16, fill=CARD, outline=(224, 224, 224), width=1)
    x0, y0 = card[0] + 40, card[1] + 36
    draw.text((x0, y0), title, font=_font(28, True), fill=TEXT)
    draw.text((x0, y0 + 44), subtitle, font=_font(16), fill=MUTED)
    y = y0 + 100
    for label, value in fields:
        draw.text((x0, y), label, font=_font(14, True), fill=TEXT)
        draw.line((x0, y + 52, card[2] - 40, y + 52), fill=(3, 169, 244), width=2)
        draw.text((x0, y + 28), value, font=_font(18), fill=TEXT)
        y += 78
    draw.rounded_rectangle(
        (card[2] - 180, card[3] - 70, card[2] - 40, card[3] - 24),
        radius=8,
        fill=BLUE,
    )
    draw.text((card[2] - 150, card[3] - 58), "Submit", font=_font(16, True), fill=(255, 255, 255))
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    screens = {
        "config-flow-add-device.png": (
            "Add a device",
            "Create one entry per child PC or phone. Copy the generated API key into that device's agent.",
            [
                ("Device ID", "child-pc"),
                ("Display name", "Child PC"),
                ("Device type", "Windows PC"),
                ("Windows child username or Android owner label", "child"),
                ("Device API key", "••••••••••••••••••••••••••••••••"),
            ],
        ),
        "options-flow.png": (
            "Parental Device Blocker options",
            "Optional parent PIN override, screen assessment, and provider settings.",
            [
                ("Enable screen assessment", "Off"),
                ("Parent override PIN (optional)", "••••"),
                ("OpenAI-compatible base URL", "https://api.kilo.ai/api/gateway"),
                ("Model", "kilo-auto/balanced"),
            ],
        ),
        "integration-device-list.png": (
            "Parental Device Blocker",
            "1 device · 12 entities",
            [
                ("Demo Child PC", "Windows PC Agent"),
                ("Block requested", "Off"),
                ("Agent online", "Unavailable"),
            ],
        ),
        "device-entities.png": (
            "Demo Child PC",
            "Parental Device Blocker · Windows PC Agent",
            [
                ("switch.demo_child_pc_block_requested", "Off"),
                ("sensor.demo_child_pc_active_application", "Idle"),
                ("binary_sensor.demo_child_pc_agent_online", "Off"),
            ],
        ),
        "integrations-dashboard.png": (
            "Integrations",
            "Search integrations",
            [
                ("Parental Device Blocker", "Configured"),
                ("Sun", "Built-in"),
                ("Shopping list", "Built-in"),
            ],
        ),
        "config-flow-picker.png": (
            "Add integration",
            "Search for a brand or model",
            [
                ("Parental Device Blocker", "Control Windows PCs and Android phones"),
            ],
        ),
        "dashboard-default.png": (
            "Overview",
            "Demo dashboard card",
            [
                ("Child PC block requested", "Off"),
                ("Active application", "Idle"),
                ("Enforcement status", "unknown"),
            ],
        ),
    }
    for name, payload in screens.items():
        _frame(*payload).save(OUT / name)


if __name__ == "__main__":
    main()
