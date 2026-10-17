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
    """A restored state, as ``RestoreEntity.async_get_last_state`` returns it."""

    def __init__(self, state: str, attributes: dict | None = None) -> None:
        self.state = state
        self.attributes = attributes or {}


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

    def __init__(self, data: dict, entry_id: str = "entry", options: dict | None = None) -> None:
        self.data = data
        self.entry_id = entry_id
        self.options = options or {}
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


class FakeHass:
    """The parts of ``HomeAssistant`` the integration touches in tests."""

    def __init__(self, config_dir: str | None = None) -> None:
        self.data: dict = {}
        self.bus = FakeBus()
        self.storage: dict = {}
        self.dispatcher: dict[str, list] = {}
        self.intervals: list = []
        self.entity_registry = FakeEntityRegistry()
        self.config_entries = FakeConfigEntries()
        self.services = FakeServices(self)
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
    pass


class TextEntity(Entity):
    pass


class ButtonEntity(Entity):
    pass


class BinarySensorEntity(Entity):
    pass


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
    _module("homeassistant.helpers")
    _module("homeassistant.helpers.entity", Entity=Entity)
    _module("homeassistant.helpers.device_registry", DeviceInfo=DeviceInfo)
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
    _module("homeassistant.components.sensor", SensorEntity=SensorEntity)
    _module("homeassistant.components.text", TextEntity=TextEntity)
    _module("homeassistant.components.button", ButtonEntity=ButtonEntity)
    _module("homeassistant.components.image", ImageEntity=ImageEntity)
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
