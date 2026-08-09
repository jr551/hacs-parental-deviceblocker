import asyncio
import importlib.util
import struct
import sys
import unittest
from unittest.mock import patch
from pathlib import Path


VNC_PATH = (
    Path(__file__).resolve().parents[1]
    / "custom_components"
    / "rowe_pc_blocker"
    / "vnc.py"
)
SPEC = importlib.util.spec_from_file_location("rowe_pc_blocker_vnc", VNC_PATH)
assert SPEC is not None and SPEC.loader is not None
VNC = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VNC
SPEC.loader.exec_module(VNC)


class VncTests(unittest.IsolatedAsyncioTestCase):
    async def test_authenticated_capture_is_shared_and_read_only(self) -> None:
        password = "viewer"
        challenge = bytes(range(16))
        observations = {}

        async def fake_server(reader, writer) -> None:
            try:
                writer.write(b"RFB 003.008\n")
                await writer.drain()
                observations["version"] = await reader.readexactly(12)
                writer.write(b"\x01\x02")
                await writer.drain()
                observations["security"] = await reader.readexactly(1)
                writer.write(challenge)
                await writer.drain()
                observations["auth"] = await reader.readexactly(16)
                writer.write(struct.pack(">I", 0))
                await writer.drain()
                observations["shared"] = await reader.readexactly(1)

                server_pixel_format = struct.pack(
                    ">BBBBHHHBBBxxx", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0
                )
                name = b"Test desktop"
                writer.write(
                    struct.pack(">HH", 2, 1)
                    + server_pixel_format
                    + struct.pack(">I", len(name))
                    + name
                )
                await writer.drain()

                observations["set_pixel_format"] = await reader.readexactly(20)
                observations["set_encodings"] = await reader.readexactly(8)
                observations["frame_request"] = await reader.readexactly(10)
                pixels = b"\x00\x00\xff\x00" + b"\x00\xff\x00\x00"
                writer.write(
                    b"\x00\x00\x00\x01"
                    + struct.pack(">HHHHi", 0, 0, 2, 1, 0)
                    + pixels
                )
                await writer.drain()
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(fake_server, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            frame = await VNC.async_capture_vnc_frame(
                "127.0.0.1", port, password, timeout=2
            )
        finally:
            server.close()
            await server.wait_closed()

        self.assertEqual((frame.width, frame.height), (2, 1))
        self.assertEqual(frame.desktop_name, "Test desktop")
        self.assertEqual(frame.bgra, b"\x00\x00\xff\x00\x00\xff\x00\x00")
        self.assertEqual(observations["version"], b"RFB 003.008\n")
        self.assertEqual(observations["security"], b"\x02")
        self.assertEqual(
            observations["auth"], VNC._encrypt_vnc_challenge(challenge, password)
        )
        self.assertEqual(observations["shared"], b"\x01")
        self.assertEqual(observations["set_pixel_format"][0], 0)
        self.assertEqual(observations["set_encodings"], struct.pack(">BBHi", 2, 0, 1, 0))
        self.assertEqual(
            observations["frame_request"], struct.pack(">BBHHHH", 3, 0, 0, 0, 2, 1)
        )

    async def test_all_zero_initialization_frame_is_retried(self) -> None:
        password = "viewer"
        observations = {"requests": 0}

        async def fake_server(reader, writer) -> None:
            try:
                writer.write(b"RFB 003.008\n")
                await writer.drain()
                await reader.readexactly(12)
                writer.write(b"\x01\x02")
                await writer.drain()
                await reader.readexactly(1)
                challenge = bytes(range(16))
                writer.write(challenge)
                await writer.drain()
                await reader.readexactly(16)
                writer.write(struct.pack(">I", 0))
                await writer.drain()
                await reader.readexactly(1)

                server_pixel_format = struct.pack(
                    ">BBBBHHHBBBxxx", 32, 24, 0, 1, 255, 255, 255, 16, 8, 0
                )
                name = b"Initializing desktop"
                writer.write(
                    struct.pack(">HH", 2, 1)
                    + server_pixel_format
                    + struct.pack(">I", len(name))
                    + name
                )
                await writer.drain()

                for pixels in (
                    b"\x00\x00\x00\x00" * 2,
                    b"\x00\x00\xff\xff" + b"\x00\xff\x00\xff",
                ):
                    await reader.readexactly(20)  # SetPixelFormat
                    await reader.readexactly(8)  # SetEncodings
                    await reader.readexactly(10)  # FramebufferUpdateRequest
                    observations["requests"] += 1
                    writer.write(
                        b"\x00\x00\x00\x01"
                        + struct.pack(">HHHHi", 0, 0, 2, 1, 0)
                        + pixels
                    )
                    await writer.drain()
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(fake_server, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            with patch.object(VNC, "BLANK_FRAME_RETRY_DELAY_SECONDS", 0):
                frame = await VNC.async_capture_vnc_frame(
                    "127.0.0.1", port, password, timeout=2
                )
        finally:
            server.close()
            await server.wait_closed()

        self.assertEqual(observations["requests"], 2)
        self.assertEqual(
            frame.bgra,
            b"\x00\x00\xff\xff" + b"\x00\xff\x00\xff",
        )

    async def test_wrong_password_has_safe_error(self) -> None:
        async def fake_server(reader, writer) -> None:
            try:
                writer.write(b"RFB 003.008\n")
                await writer.drain()
                await reader.readexactly(12)
                writer.write(b"\x01\x02")
                await writer.drain()
                await reader.readexactly(1)
                writer.write(bytes(16))
                await writer.drain()
                await reader.readexactly(16)
                reason = b"bad password details that must not be surfaced"
                writer.write(struct.pack(">II", 1, len(reason)) + reason)
                await writer.drain()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(fake_server, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            with self.assertRaisesRegex(
                VNC.VncAuthenticationError, "VNC viewer authentication failed"
            ):
                await VNC.async_capture_vnc_frame(
                    "127.0.0.1", port, "wrong", timeout=2
                )
        finally:
            server.close()
            await server.wait_closed()


if __name__ == "__main__":
    unittest.main()
