"""Minimal Home Assistant stand-ins for tests that load integration modules.

Home Assistant is not installed in this repository's test environment, so the
integration modules that import it cannot be loaded directly. These stubs give
them just enough of the entity and runtime API to load, and let a test read an
entity's state and attributes.

They are deliberately not a Home Assistant emulator. Anything that depends on
Home Assistant's own behaviour (service calls, the state machine, the entity
registry) is out of scope, with one exception: ``slugify`` and ``object_id``
mirror the documented rule that an entity's object id is derived from its name,
and that a name attached to a device is prefixed with the device name. That is
what pins the entity ids the dashboard and the deploy checklist use.
"""

from __future__ import annotations

import asyncio
import importlib
import inspect
import re
import sys
import tempfile
import types
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

INTEGRATION = Path(__file__).resolve().parents[1] / "custom_components" / "rowe_pc_blocker"
PACKAGE = "rowe_pc_blocker"


def slugify(text: str) -> str:
    """The slugify Home Assistant applies to an entity name.

    Home Assistant delegates to python-slugify with an underscore separator;
    this is the same rule for the ASCII names used here.
    """
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode("utf-8")
    text = re.sub(r"[^\w\s-]", "", text).strip().lower()
    return re.sub(r"[-\s]+", "_", text)


def object_id(entity) -> str:
    """The object id Home Assistant derives from an entity's name.

    ``object_id_base`` is prefixed with the device name when the entity is
    attached to a device and uses the device name for its own name.
    """
    if entity.has_entity_name and entity.device_info:
        return f"{slugify(entity.device_info['name'])}_{slugify(entity.name)}"
    return slugify(entity.name)


class FakeState:
    """A state, as the state machine or ``async_get_last_state`` returns it."""

    def __init__(
        self, state: str, attributes: dict | None = None, entity_id: str = ""
    ) -> None:
        self.state = state
        self.attributes = attributes or {}
        self.entity_id = entity_id


class FakeStates:
    """``hass.states``: only the reads the integration makes."""

    def __init__(self) -> None:
        self.entities: dict[str, FakeState] = {}

    def async_all(self, domain: str | None = None) -> list[FakeState]:
        return [
            state
            for state in self.entities.values()
            if domain is None or state.entity_id.startswith(f"{domain}.")
        ]

    def get(self, entity_id: str) -> FakeState | None:
        return self.entities.get(entity_id)

    def set(self, entity_id: str, state: str, attributes: dict | None = None) -> None:
        self.entities[entity_id] = FakeState(state, attributes, entity_id)


class FakeBus:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict]] = []

    def async_fire(self, event_type: str, event_data: dict | None = None, **kwargs) -> None:
        self.events.append((event_type, dict(event_data or {})))

    def fired(self, event_type: str) -> list[dict]:
        """Every payload fired for one event type, in order."""
        return [data for name, data in self.events if name == event_type]


class FakeRegistryEntry:
    """An entity registry entry, as ``entity_registry.async_get`` returns it."""

    def __init__(self, unique_id: str, entity_id: str = "") -> None:
        self.unique_id = unique_id
        self.entity_id = entity_id


class FakeDeviceEntry:
    """A device registry entry, as the lookups return it."""

    def __init__(
        self,
        device_id: str,
        identifiers: set,
        config_entry_id: str = "entry-1",
        sw_version: str | None = None,
    ) -> None:
        self.id = device_id
        self.identifiers = identifiers
        self.config_entry_id = config_entry_id
        self.sw_version = sw_version


class FakeDeviceRegistry:
    """The device registry calls the integration makes."""

    def __init__(self) -> None:
        self.devices: dict[str, FakeDeviceEntry] = {}
        self.updates: list[tuple[str, dict]] = []

    def add(
        self,
        device_id: str,
        identifiers: set,
        config_entry_id: str = "entry-1",
        sw_version: str | None = None,
    ) -> FakeDeviceEntry:
        entry = FakeDeviceEntry(device_id, identifiers, config_entry_id, sw_version)
        self.devices[device_id] = entry
        return entry

    def async_get_device_by_identifier(self, identifier, config_entry_id):
        for entry in self.devices.values():
            if identifier in entry.identifiers and entry.config_entry_id == config_entry_id:
                return entry
        return None

    def async_get_device(self, identifiers=None, connections=None):
        for entry in self.devices.values():
            if identifiers and set(identifiers) & set(entry.identifiers):
                return entry
        return None

    def async_update_device(self, device_id: str, **changes) -> None:
        self.updates.append((device_id, changes))
        entry = self.devices.get(device_id)
        if entry is not None:
            for key, value in changes.items():
                setattr(entry, key, value)


class FakeEntityRegistry:
    def __init__(self) -> None:
        self.removed: list[str] = []
        self.entities: dict[str, FakeRegistryEntry] = {}

    def add(self, entity_id: str, unique_id: str) -> None:
        """Register an entity the way Home Assistant's registry would."""
        self.entities[entity_id] = FakeRegistryEntry(unique_id, entity_id)

    def async_get(self, entity_id: str) -> FakeRegistryEntry | None:
        return self.entities.get(entity_id)

    def async_remove(self, entity_id: str) -> None:
        self.removed.append(entity_id)


class ServiceCall:
    """A service call, as a handler receives it."""

    def __init__(self, hass, domain: str, service: str, data: dict) -> None:
        self.hass = hass
        self.domain = domain
        self.service = service
        self.data = data


class FakeServices:
    """Registers service handlers and calls them the way Home Assistant does.

    A call merges its target into the data and validates the result against the
    registered schema, which is what makes ``target.entity_id`` reach a handler.
    """

    def __init__(self, hass) -> None:
        self.hass = hass
        self.registered: dict[tuple[str, str], tuple] = {}

    def async_register(self, domain, service, handler, schema=None, **kwargs) -> None:
        self.registered[(domain, service)] = (handler, schema)

    async def async_call(
        self,
        domain,
        service,
        service_data=None,
        blocking=False,
        context=None,
        target=None,
        return_response=False,
    ):
        handler, schema = self.registered[(domain, service)]
        data = dict(service_data or {})
        if target:
            data.update(target)
        if schema is not None:
            data = schema(data)
        result = handler(ServiceCall(self.hass, domain, service, data))
        if inspect.isawaitable(result):
            result = await result
        return result


class FakeConfig:
    """``hass.config``: only the config directory path is used."""

    def __init__(self, config_dir: str) -> None:
        self.config_dir = config_dir

    def path(self, *parts) -> str:
        return str(Path(self.config_dir).joinpath(*parts))


class FakeConfigEntry:
    """A configured device, as ``pending.provision_entries`` reads it."""

    def __init__(
        self,
        data: dict,
        entry_id: str = "entry",
        options: dict | None = None,
        title: str = "Casey's phone",
    ) -> None:
        self.data = data
        self.entry_id = entry_id
        self.options = options or {}
        self.title = title
        self.version = 2
        self.on_unload: list = []

    def async_on_unload(self, func) -> None:
        self.on_unload.append(func)


class FakeFlowManager:
    """Records the config entries the integration asks the flow to create."""

    def __init__(self) -> None:
        self.created: list[dict] = []

    async def async_init(self, domain, context=None, data=None):
        self.created.append(data)
        return {"type": FlowResultType.CREATE_ENTRY}


class FakeConfigEntries:
    def __init__(self) -> None:
        self.flow = FakeFlowManager()
        self.entries: list[FakeConfigEntry] = []

    def async_entries(self, domain):
        return list(self.entries)

    def async_get_entry(self, entry_id):
        for entry in self.entries:
            if entry.entry_id == entry_id:
                return entry
        return None


class FakeHass:
    """The parts of ``HomeAssistant`` the integration touches in tests."""

    def __init__(self, config_dir: str | None = None) -> None:
        self.data: dict = {}
        self.states = FakeStates()
        self.bus = FakeBus()
        self.storage: dict = {}
        self.dispatcher: dict[str, list] = {}
        self.intervals: list = []
        self.entity_registry = FakeEntityRegistry()
        self.device_registry = FakeDeviceRegistry()
        self.config_entries = FakeConfigEntries()
        self.services = FakeServices(self)
        self.notifications: list = []
        self.config = FakeConfig(config_dir or tempfile.mkdtemp(prefix="rowe_test_"))
        self.is_stopping = False
        self.tasks: list = []

    def async_create_task(self, coro):
        self.tasks.append(coro)
        return coro

    async def async_add_executor_job(self, target, *args):
        """Run blocking work inline; tests have no executor."""
        return target(*args)

    async def async_drain(self) -> None:
        """Run the tasks the integration scheduled."""
        while self.tasks:
            pending, self.tasks = self.tasks, []
            await asyncio.gather(*pending)


class Entity:
    """Just enough of Home Assistant's ``Entity`` for state and attribute tests."""

    _attr_has_entity_name = False
    _attr_name = None
    _attr_unique_id = None
    _attr_icon = None
    _attr_device_info = None
    _attr_device_class = None
    _attr_native_max = None
    _attr_should_poll = True

    # Home Assistant keeps these as class attributes and fills them in lazily,
    # so an entity that never calls ``Entity.__init__`` still works.
    _on_remove: list | None = None
    state_writes = 0
    removed = False

    hass = None
    entity_id = None
    platform = None
    registry_entry = None

    def async_on_remove(self, func) -> None:
        if self._on_remove is None:
            self._on_remove = []
        self._on_remove.append(func)

    async def async_added_to_hass(self) -> None:
        pass

    async def async_will_remove_from_hass(self) -> None:
        pass

    def async_write_ha_state(self) -> None:
        self.state_writes += 1

    async def async_remove(self, *, force_remove: bool = False) -> None:
        self.removed = True
        while self._on_remove:
            self._on_remove.pop()()

    @property
    def name(self):
        return self._attr_name

    @property
    def unique_id(self):
        return self._attr_unique_id

    @property
    def has_entity_name(self) -> bool:
        return self._attr_has_entity_name

    @property
    def device_info(self):
        return self._attr_device_info

    @property
    def device_class(self):
        return self._attr_device_class

    @property
    def entity_category(self):
        return self._attr_entity_category

    @property
    def available(self) -> bool:
        return True

    @property
    def extra_state_attributes(self):
        return None


class RestoreEntity(Entity):
    """``RestoreEntity`` stub: set ``restored_state`` to simulate a restart."""

    restored_state: FakeState | None = None

    async def async_get_last_state(self):
        return self.restored_state


class SensorEntity(Entity):
    _attr_options = None
    _attr_state_class = None

    @property
    def options(self):
        return self._attr_options

    @property
    def state_class(self):
        return self._attr_state_class


class TextEntity(Entity):
    pass


class ButtonEntity(Entity):
    pass


class BinarySensorEntity(Entity):
    pass


class SwitchEntity(Entity):
    pass


class TrackerEntity(Entity):
    pass


class SourceType:
    GPS = "gps"


class EntityCategory:
    CONFIG = "config"
    DIAGNOSTIC = "diagnostic"


class SensorDeviceClass:
    ENUM = "enum"


class SensorStateClass:
    MEASUREMENT = "measurement"


class SwitchDeviceClass:
    SWITCH = "switch"


def async_redact_data(data, to_redact):
    """Redact the named keys, as Home Assistant's diagnostics helper does."""
    if isinstance(data, dict):
        return {
            key: "**REDACTED**" if key in to_redact else async_redact_data(value, to_redact)
            for key, value in data.items()
        }
    if isinstance(data, list):
        return [async_redact_data(item, to_redact) for item in data]
    return data


def async_create_notification(hass, message, title=None, notification_id=None) -> None:
    """Record a persistent notification instead of showing one."""
    hass.notifications.append(
        {"message": message, "title": title, "notification_id": notification_id}
    )


class ImageEntity(Entity):
    """``ImageEntity`` stub: keeps the access tokens its image URL is built from."""

    _attr_content_type = "image/jpeg"
    _attr_image_last_updated = None

    def __init__(self, hass=None, verify_ssl: bool = False) -> None:
        self.hass = hass
        self.access_tokens: list[str] = []
        self.async_update_token()

    def async_update_token(self) -> None:
        self.access_tokens.append("test-token")

    @property
    def content_type(self) -> str:
        return self._attr_content_type

    @property
    def image_last_updated(self):
        return self._attr_image_last_updated

    async def async_image(self):
        return None


class BinarySensorDeviceClass:
    CONNECTIVITY = "connectivity"


class DeviceInfo(dict):
    """``DeviceInfo`` is a TypedDict, so a dict is enough."""


class Store:
    """In-memory ``homeassistant.helpers.storage.Store``."""

    def __init__(self, hass, version: int, key: str) -> None:
        self.hass = hass
        self.version = version
        self.key = key

    async def async_load(self):
        return self.hass.storage.get(self.key)

    async def async_save(self, data) -> None:
        self.hass.storage[self.key] = data


class _DtUtil:
    @staticmethod
    def utcnow() -> datetime:
        return datetime.now(timezone.utc)

    @staticmethod
    def utc_from_timestamp(timestamp: float) -> datetime:
        return datetime.fromtimestamp(timestamp, timezone.utc)

    @staticmethod
    def parse_datetime(value: str | None):
        return datetime.fromisoformat(value) if value else None


def utcnow() -> datetime:
    """The same clock the integration sees through ``dt_util``."""
    return _DtUtil.utcnow()


def utc_from_timestamp(timestamp: float) -> datetime:
    """The same conversion the integration sees through ``dt_util``."""
    return _DtUtil.utc_from_timestamp(timestamp)


class FlowResultType:
    CREATE_ENTRY = "create_entry"


class HomeAssistantError(Exception):
    """Base class for Home Assistant errors."""


class ServiceValidationError(HomeAssistantError):
    """A service was called with invalid data."""


def callback(func):
    """``@callback`` marks a function as event-loop safe; it changes nothing here."""
    return func


def async_dispatcher_send(hass, signal: str, *args) -> None:
    for target in list(hass.dispatcher.get(signal, [])):
        target(*args)


def async_dispatcher_connect(hass, signal: str, target):
    hass.dispatcher.setdefault(signal, []).append(target)

    def unsubscribe() -> None:
        hass.dispatcher[signal].remove(target)

    return unsubscribe


def async_track_time_interval(hass, action, interval):
    hass.intervals.append((action, interval))

    def unsubscribe() -> None:
        hass.intervals.remove((action, interval))

    return unsubscribe


class FakeResponse:
    """What ``HomeAssistantView.json`` returns in these tests."""

    def __init__(self, data, status: int = 200) -> None:
        self.data = data
        self.status = status


class HomeAssistantView:
    """``HomeAssistantView`` stub: enough to instantiate and answer.

    The real class turns ``json()`` into an aiohttp response; here the payload
    and status are kept so a test can read them back.
    """

    url = ""
    name = ""
    requires_auth = True

    def json(self, data, status_code: int = 200) -> FakeResponse:
        return FakeResponse(data, status_code)


class FakeRequest:
    """A request as an API view handler receives it."""

    def __init__(
        self,
        hass,
        *,
        body: bytes = b"",
        headers: dict | None = None,
        remote: str = "203.0.113.10",
        device_id: str = "casey-phone",
    ) -> None:
        self.app = {"hass": hass}
        self.headers = dict(headers or {})
        self.remote = remote
        self.match_info = {"device_id": device_id}
        self.content_length = len(body)
        self._body = body

    @property
    def content(self):
        body = self._body

        class _Content:
            async def read(self, limit: int) -> bytes:
                return body[:limit]

        return _Content()


def _install_aiohttp() -> None:
    """Install a minimal aiohttp stand-in when aiohttp is not installed.

    ``api.py`` needs only the view base class and the HTTP error types its
    handlers raise. A stand-in another test module already installed is filled
    in rather than replaced, so import order cannot matter; a real aiohttp is
    left alone.
    """
    web = sys.modules.get("aiohttp.web")
    if web is None:
        try:
            from aiohttp import web  # noqa: F401
        except ImportError:
            web = None
    if web is None:
        aiohttp_module = types.ModuleType("aiohttp")
        web = types.ModuleType("aiohttp.web")
        aiohttp_module.web = web
        sys.modules["aiohttp"] = aiohttp_module
        sys.modules["aiohttp.web"] = web
    elif getattr(web, "__file__", None):
        # The real package: its error classes are the ones to raise.
        return
    # The error classes are replaced even when another test module installed
    # its own: they are raised with keyword arguments here, and a stand-in that
    # rejects those would fail at the raise instead of at the assertion.
    for name, value in _AIOHTTP_WEB_ATTRS.items():
        setattr(web, name, value)


class _StubHTTPError(Exception):
    """Minimal stand-in for an aiohttp HTTP error response.

    The real classes take ``text=`` and the like, so the stub swallows any
    keyword arguments rather than failing on the raise itself.
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args)
        self.kwargs = kwargs


class _StubHTTPBadRequest(_StubHTTPError):
    pass


class _StubHTTPUnauthorized(_StubHTTPError):
    pass


class _StubHTTPForbidden(_StubHTTPError):
    pass


class _StubHTTPRequestEntityTooLarge(_StubHTTPError):
    pass


_AIOHTTP_WEB_ATTRS = {
    "HTTPBadRequest": _StubHTTPBadRequest,
    "HTTPUnauthorized": _StubHTTPUnauthorized,
    "HTTPForbidden": _StubHTTPForbidden,
    "HTTPRequestEntityTooLarge": _StubHTTPRequestEntityTooLarge,
}


def _module(name: str, **attributes) -> types.ModuleType:
    module = types.ModuleType(name)
    for key, value in attributes.items():
        setattr(module, key, value)
    sys.modules[name] = module
    return module


def install() -> None:
    """Install the stubs once per test process."""
    if getattr(sys.modules.get("homeassistant"), "_rowe_stub", False):
        return
    _install_aiohttp()
    homeassistant = _module("homeassistant")
    homeassistant._rowe_stub = True
    _module(
        "homeassistant.core",
        HomeAssistant=type("HomeAssistant", (), {}),
        ServiceCall=ServiceCall,
        callback=callback,
    )
    _module("homeassistant.util")
    _module(
        "homeassistant.util.dt",
        utcnow=_DtUtil.utcnow,
        utc_from_timestamp=_DtUtil.utc_from_timestamp,
        parse_datetime=_DtUtil.parse_datetime,
    )
    _module(
        "homeassistant.exceptions",
        HomeAssistantError=HomeAssistantError,
        ServiceValidationError=ServiceValidationError,
    )
    _module("homeassistant.data_entry_flow", FlowResultType=FlowResultType)
    _module(
        "homeassistant.const",
        EntityCategory=EntityCategory,
    )
    _module("homeassistant.config_entries", ConfigEntry=FakeConfigEntry)
    _module("homeassistant.helpers")
    _module("homeassistant.helpers.entity", Entity=Entity)
    _module(
        "homeassistant.helpers.device_registry",
        DeviceInfo=DeviceInfo,
        async_get=lambda hass: hass.device_registry,
    )
    _module("homeassistant.helpers.restore_state", RestoreEntity=RestoreEntity)
    _module(
        "homeassistant.helpers.dispatcher",
        async_dispatcher_send=async_dispatcher_send,
        async_dispatcher_connect=async_dispatcher_connect,
    )
    _module("homeassistant.helpers.event", async_track_time_interval=async_track_time_interval)
    _module("homeassistant.helpers.storage", Store=Store)
    _module(
        "homeassistant.helpers.entity_registry",
        async_get=lambda hass: hass.entity_registry,
    )
    _module("homeassistant.components")
    _module(
        "homeassistant.components.http",
        HomeAssistantView=HomeAssistantView,
    )
    _module(
        "homeassistant.components.sensor",
        SensorEntity=SensorEntity,
        SensorDeviceClass=SensorDeviceClass,
        SensorStateClass=SensorStateClass,
    )
    _module("homeassistant.components.text", TextEntity=TextEntity)
    _module("homeassistant.components.button", ButtonEntity=ButtonEntity)
    _module("homeassistant.components.image", ImageEntity=ImageEntity)
    _module(
        "homeassistant.components.switch",
        SwitchEntity=SwitchEntity,
        SwitchDeviceClass=SwitchDeviceClass,
    )
    _module(
        "homeassistant.components.device_tracker",
        TrackerEntity=TrackerEntity,
        SourceType=SourceType,
    )
    _module(
        "homeassistant.components.diagnostics",
        async_redact_data=async_redact_data,
    )
    _module(
        "homeassistant.components.persistent_notification",
        async_create=async_create_notification,
    )
    _module(
        "homeassistant.components.binary_sensor",
        BinarySensorEntity=BinarySensorEntity,
        BinarySensorDeviceClass=BinarySensorDeviceClass,
    )


def load(name: str):
    """Import one integration module as ``rowe_pc_blocker.<name>``.

    The package is registered by hand so the modules' relative imports resolve
    without running the integration's ``__init__``, which needs Home Assistant
    itself.
    """
    install()
    if PACKAGE not in sys.modules:
        package = types.ModuleType(PACKAGE)
        package.__path__ = [str(INTEGRATION)]
        sys.modules[PACKAGE] = package
    return importlib.import_module(f"{PACKAGE}.{name}")
