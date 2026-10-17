"""The chore challenge: a phrase the child types back before the phone unlocks.

A parent sends a phrase from the dashboard; the phone shows it, the child takes
a photo and types the phrase back, and the phone unlocks. The phrase and its
state live on ``PcRuntime`` so they survive a Home Assistant restart; this
module owns the two services that set and clear it, and the rules for what a
parent may type.
"""

from __future__ import annotations

from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import entity_registry as er

from .const import DOMAIN
from .evidence import async_delete_evidence, evidence_file
from .runtime import PcRuntime, runtime_for_device

# The phrase is typed by a child on a phone keyboard, so keep it short.
MAX_CHALLENGE_PHRASE_LENGTH = 200

CHALLENGE_SUFFIX = "_challenge"

SERVICE_SEND = "send_challenge"
SERVICE_CLEAR = "clear_challenge"


def async_register_services(hass: HomeAssistant, send_schema, clear_schema) -> None:
    """Register the challenge services once.

    The schemas come from the integration setup, which is where voluptuous is
    imported, so this module stays loadable without it.
    """
    if hass.data.setdefault(DOMAIN, {}).get("_services_registered"):
        return
    hass.services.async_register(
        DOMAIN, SERVICE_SEND, async_send_challenge, schema=send_schema
    )
    hass.services.async_register(
        DOMAIN, SERVICE_CLEAR, async_clear_challenge, schema=clear_schema
    )
    hass.data[DOMAIN]["_services_registered"] = True


def challenge_runtime(hass: HomeAssistant, entity_id: str) -> PcRuntime:
    """The device runtime behind one challenge sensor entity.

    The sensor's unique id is the device id plus a suffix, and the entity
    registry is what maps the entity id a parent targeted back to it.
    """
    entry = er.async_get(hass).async_get(entity_id)
    unique_id = entry.unique_id if entry is not None else ""
    device_id = (
        unique_id[: -len(CHALLENGE_SUFFIX)]
        if unique_id.endswith(CHALLENGE_SUFFIX)
        else ""
    )
    runtime = runtime_for_device(hass, device_id) if device_id else None
    if runtime is None:
        raise ServiceValidationError(f"{entity_id} is not a challenge sensor.")
    return runtime


def challenge_phrase(value) -> str:
    """The phrase a parent typed, trimmed and bounded."""
    phrase = str(value or "").strip()
    if not phrase:
        raise ServiceValidationError("A challenge phrase is required.")
    if len(phrase) > MAX_CHALLENGE_PHRASE_LENGTH:
        raise ServiceValidationError(
            f"Keep the phrase to {MAX_CHALLENGE_PHRASE_LENGTH} characters or fewer."
        )
    return phrase


def challenge_accept_refusal(
    hass: HomeAssistant, runtime: PcRuntime, challenge_id: str
) -> tuple[int, dict] | None:
    """Why this challenge cannot be accepted, or None when it can.

    The photo comes first: a child cannot clear the lock by typing the phrase
    alone, so an accepted challenge always has evidence behind it.
    """
    if not runtime.challenge_is_current(challenge_id):
        return 404, {"error": "That challenge is no longer active."}
    if evidence_file(hass, runtime.device_id, runtime.challenge_id) is None:
        return 409, {"error": "Take the photo first."}
    return None


def challenge_targets(call: ServiceCall) -> list[str]:
    """The entity ids a challenge service was called for.

    The service schema hands these over as a list; a bare string is accepted
    too so the handler cannot iterate an entity id character by character.
    """
    entity_ids = call.data.get("entity_id") or []
    if isinstance(entity_ids, str):
        return [entity_ids]
    return list(entity_ids)


async def async_send_challenge(call: ServiceCall) -> None:
    """Start a new challenge on every targeted phone."""
    phrase = challenge_phrase(call.data.get("phrase"))
    for entity_id in challenge_targets(call):
        runtime = challenge_runtime(call.hass, entity_id)
        await runtime.async_send_challenge(phrase)


async def async_clear_challenge(call: ServiceCall) -> None:
    """Cancel the challenge on every targeted phone, evidence included."""
    for entity_id in challenge_targets(call):
        runtime = challenge_runtime(call.hass, entity_id)
        await async_delete_evidence(call.hass, runtime.device_id, runtime.challenge_id)
        await runtime.async_clear_challenge()
