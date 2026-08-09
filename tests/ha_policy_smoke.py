"""Small policy smoke test intended to run inside a Home Assistant environment."""

import asyncio
from datetime import timedelta
from unittest.mock import patch

from homeassistant.util import dt as dt_util

from custom_components.rowe_pc_blocker.runtime import PcRuntime


async def main() -> None:
    runtime = PcRuntime(
        hass=object(),
        entry_id="test",
        device_id="testpc",
        device_name="Test PC",
        windows_username="child",
        api_key="test-key",
    )

    with patch.object(PcRuntime, "notify", lambda self: None):
        await runtime.async_set_blocked(True)
        assert runtime.blocked
        assert not runtime.effective_blocked

        runtime.block_requested_at = dt_util.utcnow() - timedelta(seconds=31)
        assert runtime.effective_blocked

        # A points reward may spend points only once for this lock. This is
        # deliberately server-side and durable, so retrying a slow unlock does
        # not result in another charge.
        assert await runtime.async_reserve_portal_reward("pc-time")
        assert not await runtime.async_reserve_portal_reward("pc-time")
        await runtime.async_release_portal_reward("pc-time")
        assert await runtime.async_reserve_portal_reward("pc-time")
        await runtime.async_set_blocked(False)
        await runtime.async_set_blocked(True)
        runtime.block_requested_at = dt_util.utcnow() - timedelta(seconds=31)
        assert await runtime.async_reserve_portal_reward("pc-time")

        assert await runtime.async_request_extension()
        assert not runtime.effective_blocked
        assert runtime.extension_until is not None
        assert not await runtime.async_request_extension()

        runtime.extension_used_at = dt_util.utcnow() - timedelta(hours=1, seconds=1)
        runtime.extension_until = None
        assert runtime.extension_available
        assert await runtime.async_request_extension()

    print("initial grace, five-minute extension, and hourly cooldown: OK")


asyncio.run(main())
