"""Minimal read-only VNC screenshot client.

Only the RFB messages needed to authenticate and request a raw framebuffer are
implemented. There are intentionally no keyboard, pointer, clipboard-write, or
file-transfer methods in this module.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import struct

from cryptography.hazmat.primitives.ciphers import Cipher, modes

try:
    from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
except ImportError:  # pragma: no cover - compatibility with older HA images
    from cryptography.hazmat.primitives.ciphers.algorithms import TripleDES


MAX_FRAMEBUFFER_BYTES = 100 * 1024 * 1024
MAX_DESKTOP_NAME_BYTES = 4096
MAX_CLIPBOARD_BYTES = 1024 * 1024
BLANK_FRAME_RETRY_DELAY_SECONDS = 1.0
MAX_BLANK_FRAME_RETRIES = 2


class VncError(Exception):
    """A safe-to-display VNC protocol error."""


class VncAuthenticationError(VncError):
    """VNC authentication was rejected."""


@dataclass(frozen=True)
class VncFrame:
    """One complete framebuffer in BGRX byte order."""

    width: int
    height: int
    bgra: bytes
    desktop_name: str


async def async_capture_vnc_frame(
    host: str,
    port: int,
    password: str,
    *,
    timeout: float = 25.0,
) -> VncFrame:
    """Connect as a shared viewer and capture one framebuffer."""

    if not host.strip():
        raise VncError("VNC host is not configured")
    if not password:
        raise VncError("VNC viewer password is not configured")

    async def _capture() -> VncFrame:
        reader, writer = await asyncio.open_connection(host, port)
        try:
            width, height, desktop_name = await _handshake(reader, writer, password)
            frame = await _request_framebuffer(
                reader, writer, width, height, desktop_name
            )
            # Some Windows VNC servers send one all-zero initialization frame
            # before desktop capture is ready. A real black screen still has
            # its unused pixel byte populated, whereas this placeholder is
            # entirely zeroed. Retry a bounded number of full-frame requests
            # so the placeholder is never sent to the vision provider.
            for _ in range(MAX_BLANK_FRAME_RETRIES):
                if any(frame.bgra):
                    break
                await asyncio.sleep(BLANK_FRAME_RETRY_DELAY_SECONDS)
                frame = await _request_framebuffer(
                    reader, writer, width, height, desktop_name
                )
            return frame
        finally:
            writer.close()
            await writer.wait_closed()

    try:
        return await asyncio.wait_for(_capture(), timeout=timeout)
    except TimeoutError as err:
        raise VncError("VNC connection or screenshot timed out") from err
    except (ConnectionError, OSError, asyncio.IncompleteReadError) as err:
        raise VncError("Could not connect to the VNC viewer") from err


async def _handshake(reader, writer, password: str) -> tuple[int, int, str]:
    server_version = await reader.readexactly(12)
    if not server_version.startswith(b"RFB ") or not server_version.endswith(b"\n"):
        raise VncError("The configured server did not speak VNC")

    try:
        major = int(server_version[4:7])
        minor = int(server_version[8:11])
    except ValueError as err:
        raise VncError("The VNC server returned an invalid protocol version") from err
    if major != 3 or minor < 3:
        raise VncError("The VNC server uses an unsupported protocol version")

    negotiated_minor = 8 if minor >= 8 else 7 if minor >= 7 else 3
    writer.write(f"RFB 003.{negotiated_minor:03d}\n".encode("ascii"))
    await writer.drain()

    if negotiated_minor == 3:
        security_type = struct.unpack(">I", await reader.readexactly(4))[0]
        if security_type == 0:
            raise VncError(await _read_reason(reader))
        if security_type != 2:
            raise VncError("The VNC server does not offer password authentication")
    else:
        count = (await reader.readexactly(1))[0]
        if count == 0:
            raise VncError(await _read_reason(reader))
        security_types = set(await reader.readexactly(count))
        if 2 not in security_types:
            raise VncError("The VNC server does not offer password authentication")
        writer.write(b"\x02")
        await writer.drain()

    challenge = await reader.readexactly(16)
    writer.write(_encrypt_vnc_challenge(challenge, password))
    await writer.drain()
    result = struct.unpack(">I", await reader.readexactly(4))[0]
    if result != 0:
        if negotiated_minor >= 8:
            await _discard_reason(reader)
        raise VncAuthenticationError("VNC viewer authentication failed")

    # ClientInit shared-flag = 1. This avoids asking the server to disconnect
    # another viewer or the user's current session.
    writer.write(b"\x01")
    await writer.drain()

    width, height = struct.unpack(">HH", await reader.readexactly(4))
    await reader.readexactly(16)  # Server pixel format; replaced below.
    name_length = struct.unpack(">I", await reader.readexactly(4))[0]
    if name_length > MAX_DESKTOP_NAME_BYTES:
        raise VncError("The VNC desktop name was unexpectedly large")
    desktop_name = (await reader.readexactly(name_length)).decode("utf-8", "replace")
    _validate_framebuffer_size(width, height)
    return width, height, desktop_name


async def _request_framebuffer(
    reader,
    writer,
    width: int,
    height: int,
    desktop_name: str,
) -> VncFrame:
    # Request 32-bit little-endian true colour with B, G, R bytes and one unused
    # byte. Advertise only raw encoding so decoding remains small and auditable.
    pixel_format = struct.pack(
        ">BBBBHHHBBBxxx", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0
    )
    writer.write(b"\x00\x00\x00\x00" + pixel_format)
    writer.write(struct.pack(">BBHi", 2, 0, 1, 0))
    writer.write(struct.pack(">BBHHHH", 3, 0, 0, 0, width, height))
    await writer.drain()

    framebuffer = bytearray(width * height * 4)
    while True:
        message_type = (await reader.readexactly(1))[0]
        if message_type == 0:
            await reader.readexactly(1)
            rectangle_count = struct.unpack(">H", await reader.readexactly(2))[0]
            received_rectangle = False
            for _ in range(rectangle_count):
                x, y, rect_width, rect_height, encoding = struct.unpack(
                    ">HHHHi", await reader.readexactly(12)
                )
                if encoding != 0:
                    raise VncError("The VNC server ignored the requested raw encoding")
                if (
                    rect_width == 0
                    or rect_height == 0
                    or x + rect_width > width
                    or y + rect_height > height
                ):
                    raise VncError("The VNC server returned an invalid screen rectangle")
                byte_count = rect_width * rect_height * 4
                if byte_count > MAX_FRAMEBUFFER_BYTES:
                    raise VncError("The VNC screen rectangle was too large")
                rectangle = await reader.readexactly(byte_count)
                source_stride = rect_width * 4
                target_stride = width * 4
                for row in range(rect_height):
                    source_start = row * source_stride
                    target_start = ((y + row) * target_stride) + (x * 4)
                    framebuffer[target_start : target_start + source_stride] = rectangle[
                        source_start : source_start + source_stride
                    ]
                received_rectangle = True
            if received_rectangle:
                return VncFrame(width, height, bytes(framebuffer), desktop_name)
        elif message_type == 1:  # SetColorMapEntries; unused in true-colour mode.
            await reader.readexactly(1)
            _, colour_count = struct.unpack(">HH", await reader.readexactly(4))
            await reader.readexactly(colour_count * 6)
        elif message_type == 2:  # Bell.
            continue
        elif message_type == 3:  # ServerCutText; discard, never write clipboard data.
            await reader.readexactly(3)
            text_length = struct.unpack(">I", await reader.readexactly(4))[0]
            if text_length > MAX_CLIPBOARD_BYTES:
                raise VncError("The VNC server clipboard message was too large")
            await reader.readexactly(text_length)
        else:
            raise VncError("The VNC server returned an unsupported message")


def _encrypt_vnc_challenge(challenge: bytes, password: str) -> bytes:
    if len(challenge) != 16:
        raise VncError("The VNC server returned an invalid authentication challenge")
    try:
        password_bytes = password.encode("latin-1")[:8].ljust(8, b"\x00")
    except UnicodeEncodeError as err:
        raise VncError("The VNC password must contain Latin-1 characters") from err
    key = bytes(_reverse_bits(value) for value in password_bytes)
    # K1=K2=K3 is equivalent to the single DES operation required by classic
    # VNC authentication, while using the non-deprecated 24-byte constructor.
    encryptor = Cipher(TripleDES(key * 3), modes.ECB()).encryptor()
    return encryptor.update(challenge) + encryptor.finalize()


def _reverse_bits(value: int) -> int:
    return int(f"{value:08b}"[::-1], 2)


def _validate_framebuffer_size(width: int, height: int) -> None:
    if width == 0 or height == 0 or width * height * 4 > MAX_FRAMEBUFFER_BYTES:
        raise VncError("The VNC framebuffer dimensions were invalid or too large")


async def _read_reason(reader) -> str:
    length = struct.unpack(">I", await reader.readexactly(4))[0]
    if length > 4096:
        raise VncError("The VNC server returned an oversized error")
    reason = (await reader.readexactly(length)).decode("utf-8", "replace").strip()
    return reason[:255] or "The VNC server rejected the connection"


async def _discard_reason(reader) -> None:
    try:
        await _read_reason(reader)
    except (VncError, asyncio.IncompleteReadError):
        pass
