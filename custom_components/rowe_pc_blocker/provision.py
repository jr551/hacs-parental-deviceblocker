"""Approve a phone's request from Home Assistant.

A parent types the child's name and the Home Assistant address on the phone.
The phone asks to be set up, and Home Assistant holds that request as pending
until a parent approves or rejects it from the dashboard. Only an approved
request hands the phone its device id and API key, so a random internet client
cannot mint keys on its own.

A set-up phone asks the same way when a child wants one blocked app allowed:
the request waits for a parent, and approving it appends the package to that
device's allowlist.

The rules live here as pure functions so they can be tested without a running
Home Assistant. ``pending.py`` owns the storage file and the approve/reject
entry points the dashboard buttons call.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import secrets

ANDROID = "android"

# A request that nobody answers is dropped after half an hour; the phone shows
# the parent a fresh code by asking again.
PENDING_TTL_SECONDS = 30 * 60

STATUS_PENDING = "pending"
STATUS_APPROVED = "approved"
STATUS_REJECTED = "rejected"


@dataclass(frozen=True)
class PhoneEntry:
    """The fields provision needs from one integration entry."""

    device_id: str
    device_name: str
    device_type: str
    windows_username: str
    api_key: str


@dataclass
class PendingRequest:
    """One phone waiting for a parent to approve or reject its setup."""

    request_id: str
    child_name: str
    requested_at: datetime
    status: str = STATUS_PENDING
    device_id: str = ""
    api_key: str = ""

    def is_expired(self, now: datetime, ttl_seconds: int = PENDING_TTL_SECONDS) -> bool:
        return now - self.requested_at >= timedelta(seconds=ttl_seconds)

    def to_dict(self) -> dict:
        return {
            "request_id": self.request_id,
            "child_name": self.child_name,
            "requested_at": self.requested_at.isoformat(),
            "status": self.status,
            "device_id": self.device_id,
            "api_key": self.api_key,
        }

    @classmethod
    def from_dict(cls, data: dict) -> PendingRequest | None:
        """Rebuild one stored request, or None when the record is unusable."""
        try:
            request_id = str(data["request_id"]).strip()
            child_name = str(data["child_name"]).strip()
            requested_at = datetime.fromisoformat(str(data["requested_at"]))
        except (KeyError, TypeError, ValueError):
            return None
        if not request_id or not child_name:
            return None
        if requested_at.tzinfo is None:
            requested_at = requested_at.replace(tzinfo=timezone.utc)
        status = str(data.get("status", STATUS_PENDING))
        if status not in (STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED):
            status = STATUS_PENDING
        return cls(
            request_id=request_id,
            child_name=child_name,
            requested_at=requested_at,
            status=status,
            device_id=str(data.get("device_id", "")),
            api_key=str(data.get("api_key", "")),
        )


@dataclass
class UnblockRequest:
    """One app a blocked phone has asked a parent to allow.

    The phone shows the block screen; the child taps an app and asks a parent
    to allow it. Approving appends the package to the device's allowlist, so
    the phone's next state poll clears the block screen by itself.
    """

    request_id: str
    device_id: str
    package: str
    label: str
    requested_at: datetime

    def is_expired(self, now: datetime, ttl_seconds: int = PENDING_TTL_SECONDS) -> bool:
        return now - self.requested_at >= timedelta(seconds=ttl_seconds)

    def to_dict(self) -> dict:
        return {
            "request_id": self.request_id,
            "device_id": self.device_id,
            "package": self.package,
            "label": self.label,
            "requested_at": self.requested_at.isoformat(),
        }

    @classmethod
    def from_dict(cls, data: dict) -> UnblockRequest | None:
        """Rebuild one stored request, or None when the record is unusable."""
        try:
            request_id = str(data["request_id"]).strip()
            device_id = str(data["device_id"]).strip()
            package = str(data["package"]).strip()
            requested_at = datetime.fromisoformat(str(data["requested_at"]))
        except (KeyError, TypeError, ValueError):
            return None
        if not request_id or not device_id or not package:
            return None
        if requested_at.tzinfo is None:
            requested_at = requested_at.replace(tzinfo=timezone.utc)
        return cls(
            request_id=request_id,
            device_id=device_id,
            package=package,
            label=str(data.get("label", "")),
            requested_at=requested_at,
        )


@dataclass(frozen=True)
class ApprovalDecision:
    """What approving one request should do. ``api_key`` is set only on reuse."""

    action: str
    device_id: str = ""
    child_name: str = ""
    api_key: str = ""
    message: str = ""


def child_key(value: str) -> str:
    return "".join(character for character in (value or "").casefold() if character.isalnum())


def new_request_id() -> str:
    """32 lowercase hex characters, safe to use in an entity id."""
    return secrets.token_hex(16)


def phone_device_id(child_name: str) -> str:
    """Casey -> casey-phone. Spaces and punctuation are dropped."""

    slug = child_key(child_name or "")
    if not slug:
        return ""
    return f"{slug}-phone"


def match_child(child_name: str, children: list):
    """Match a typed name to exactly one chore-manager child."""

    wanted = child_key(child_name or "")
    if not wanted:
        return None
    matches = [child for child in children if child_key(child.name) == wanted]
    if len(matches) != 1:
        return None
    return matches[0]


def decide_approval(child_name: str, entries: list[PhoneEntry]) -> ApprovalDecision:
    """Choose reuse or create for an approved request. Does not generate a key."""

    wanted = child_key(child_name or "")
    if not wanted:
        return ApprovalDecision(
            "unavailable",
            message="That name is not a child in Home Assistant.",
        )

    device_id = phone_device_id(child_name)
    exact = next((entry for entry in entries if entry.device_id == device_id), None)
    if exact is not None and exact.device_type != ANDROID:
        return ApprovalDecision(
            "unavailable",
            device_id=device_id,
            child_name=child_name,
            message="That name is already used by another device in Home Assistant.",
        )
    if exact is not None:
        return ApprovalDecision(
            "reuse",
            device_id=exact.device_id,
            child_name=child_name,
            api_key=exact.api_key,
        )

    same_child = [
        entry
        for entry in entries
        if entry.device_type == ANDROID and child_key(entry.windows_username) == wanted
    ]
    if len(same_child) == 1:
        chosen = same_child[0]
        return ApprovalDecision(
            "reuse",
            device_id=chosen.device_id,
            child_name=child_name,
            api_key=chosen.api_key,
        )
    if len(same_child) > 1:
        return ApprovalDecision(
            "unavailable",
            child_name=child_name,
            message="That child already has more than one phone in Home Assistant.",
        )
    return ApprovalDecision("create", device_id=device_id, child_name=child_name)


def approved_body(request: PendingRequest) -> dict:
    """The credentials an approved phone collects."""

    return {
        "status": STATUS_APPROVED,
        "device_id": request.device_id,
        "api_key": request.api_key,
        "child_name": request.child_name,
    }


def unknown_child_response() -> tuple[int, dict]:
    """Answer a phone whose typed name is not a chore-manager child."""

    return 404, {"error": "That name is not a child in Home Assistant."}


def request_response(request: PendingRequest) -> tuple[int, dict]:
    """Answer a fresh setup request: credentials when already approved, else pending."""

    if request.status == STATUS_APPROVED:
        return 200, approved_body(request)
    return 202, {
        "status": STATUS_PENDING,
        "request_id": request.request_id,
        "child_name": request.child_name,
    }


def collection_response(request: PendingRequest | None) -> tuple[int, dict]:
    """Answer a phone collecting its request. Approved requests stay collectable."""

    if request is None:
        return 404, {"error": "That setup request has expired."}
    if request.status == STATUS_REJECTED:
        return 403, {"status": STATUS_REJECTED, "error": "A parent declined this setup."}
    if request.status == STATUS_APPROVED:
        return 200, approved_body(request)
    return 200, {"status": STATUS_PENDING}


def unblock_pending_body(request: UnblockRequest) -> dict:
    """Answer a phone whose request is now waiting for a parent.

    The phone needs nothing else: it polls its state every minute and sees the
    package in ``allowed_apps`` once a parent approves.
    """

    return {"ok": True, "request_id": request.request_id, "status": STATUS_PENDING}


class PendingProvisionStore:
    """Pending setup requests with serialisation; persistence is injected.

    Every read prunes expired requests, so an expired request can never be
    approved or collected.
    """

    def __init__(self) -> None:
        self._requests: dict[str, PendingRequest] = {}

    def load(self, data: dict | None) -> None:
        self._requests = {}
        for raw in (data or {}).get("requests", []):
            if not isinstance(raw, dict):
                continue
            request = PendingRequest.from_dict(raw)
            if request is not None:
                self._requests[request.request_id] = request

    def dump(self) -> dict:
        return {"requests": [request.to_dict() for request in self._requests.values()]}

    def prune(self, now: datetime) -> list[PendingRequest]:
        """Drop expired requests and return them."""
        expired = [request for request in self._requests.values() if request.is_expired(now)]
        for request in expired:
            self._requests.pop(request.request_id, None)
        return expired

    def create(self, child_name: str, now: datetime) -> PendingRequest:
        request = PendingRequest(
            request_id=new_request_id(),
            child_name=child_name,
            requested_at=now,
        )
        self._requests[request.request_id] = request
        return request

    def get(self, request_id: str, now: datetime) -> PendingRequest | None:
        self.prune(now)
        return self._requests.get(request_id)

    def list(self, now: datetime) -> list[PendingRequest]:
        self.prune(now)
        return list(self._requests.values())

    def pending_for_child(self, child_name: str, now: datetime) -> PendingRequest | None:
        """The request this child is still waiting on, if any."""
        wanted = child_key(child_name)
        self.prune(now)
        return next(
            (
                request
                for request in self._requests.values()
                if request.status == STATUS_PENDING and child_key(request.child_name) == wanted
            ),
            None,
        )

    def approved_for_child(self, child_name: str, now: datetime) -> PendingRequest | None:
        """An approved request whose credentials this child has not collected yet."""
        wanted = child_key(child_name)
        self.prune(now)
        return next(
            (
                request
                for request in self._requests.values()
                if request.status == STATUS_APPROVED and child_key(request.child_name) == wanted
            ),
            None,
        )

    def approve(
        self, request_id: str, now: datetime, device_id: str, api_key: str
    ) -> PendingRequest | None:
        request = self.get(request_id, now)
        if request is None or request.status != STATUS_PENDING:
            return None
        request.status = STATUS_APPROVED
        request.device_id = device_id
        request.api_key = api_key
        return request

    def reject(self, request_id: str, now: datetime) -> PendingRequest | None:
        request = self.get(request_id, now)
        if request is None or request.status != STATUS_PENDING:
            return None
        request.status = STATUS_REJECTED
        return request


class PendingUnblockStore:
    """Apps blocked phones have asked a parent to allow.

    One request per device and package: a phone that asks twice for the same
    app is answered with the request a parent is already looking at. Every read
    prunes expired requests, so an expired request can never be approved.
    """

    def __init__(self) -> None:
        self._requests: dict[str, UnblockRequest] = {}

    def load(self, data: dict | None) -> None:
        self._requests = {}
        for raw in (data or {}).get("unblock_requests", []):
            if not isinstance(raw, dict):
                continue
            request = UnblockRequest.from_dict(raw)
            if request is not None:
                self._requests[request.request_id] = request

    def dump(self) -> dict:
        return {
            "unblock_requests": [
                request.to_dict() for request in self._requests.values()
            ]
        }

    def prune(self, now: datetime) -> list[UnblockRequest]:
        """Drop expired requests and return them."""
        expired = [
            request for request in self._requests.values() if request.is_expired(now)
        ]
        for request in expired:
            self._requests.pop(request.request_id, None)
        return expired

    def create(
        self, device_id: str, package: str, label: str, now: datetime
    ) -> UnblockRequest:
        request = UnblockRequest(
            request_id=new_request_id(),
            device_id=device_id,
            package=package,
            label=label,
            requested_at=now,
        )
        self._requests[request.request_id] = request
        return request

    def get(self, request_id: str, now: datetime) -> UnblockRequest | None:
        self.prune(now)
        return self._requests.get(request_id)

    def list(self, now: datetime) -> list[UnblockRequest]:
        self.prune(now)
        return list(self._requests.values())

    def for_device(self, device_id: str, now: datetime) -> list[UnblockRequest]:
        """The live requests one device is waiting on."""
        self.prune(now)
        return [
            request
            for request in self._requests.values()
            if request.device_id == device_id
        ]

    def pending_for(
        self, device_id: str, package: str, now: datetime
    ) -> UnblockRequest | None:
        """The request already waiting for this device and package, if any."""
        self.prune(now)
        return next(
            (
                request
                for request in self._requests.values()
                if request.device_id == device_id and request.package == package
            ),
            None,
        )

    def remove(self, request_id: str, now: datetime) -> bool:
        """Drop one answered request. True when it was still live."""
        self.prune(now)
        return self._requests.pop(request_id, None) is not None
