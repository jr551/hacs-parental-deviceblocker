"""Small, dependency-free S3 presigner for Android media backups."""

from __future__ import annotations

import hashlib
import hmac
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from urllib.parse import quote, urlencode, urlsplit

MAX_BACKUP_BYTES = 20 * 1024 * 1024 * 1024
PRESIGN_SECONDS = 15 * 60
PRESIGN_REQUESTS_PER_MINUTE = 30

_BUCKET = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
_REGION = re.compile(r"^[a-z0-9][a-z0-9-]{0,31}$")


@dataclass(frozen=True)
class S3BackupSettings:
    """Server-held S3 settings for one Android device."""

    endpoint: str
    region: str
    bucket: str
    access_key: str
    secret_key: str
    prefix: str

    @property
    def configured(self) -> bool:
        return bool(
            valid_s3_endpoint(self.endpoint)
            and valid_region(self.region)
            and valid_bucket(self.bucket)
            and self.access_key
            and self.secret_key
            and clean_prefix(self.prefix)
        )

def valid_s3_endpoint(value: str) -> bool:
    """Accept only an HTTPS origin, without embedded credentials or URL extras."""
    try:
        parsed = urlsplit(str(value).strip())
    except ValueError:
        return False
    return bool(
        parsed.scheme == "https"
        and parsed.netloc
        and not parsed.username
        and not parsed.password
        and parsed.path in ("", "/")
        and not parsed.query
        and not parsed.fragment
    )


def valid_bucket(value: str) -> bool:
    """Validate a path-style S3 bucket name."""
    bucket = str(value).strip()
    return bool(_BUCKET.fullmatch(bucket) and ".." not in bucket)


def valid_region(value: str) -> bool:
    return bool(_REGION.fullmatch(str(value).strip()))


def clean_prefix(value: str) -> str:
    """Return a safe configured folder prefix without traversal segments."""
    cleaned = [_clean_part(part) for part in str(value).split("/")]
    return "/".join(part for part in cleaned if part)


def object_key(prefix: str, relative_path: str, display_name: str) -> str:
    """Build a stable, device-scoped key from MediaStore metadata."""
    parts = [_clean_part(part) for part in str(relative_path).split("/")]
    parts = [part for part in parts if part]
    name = _clean_part(display_name) or "media"
    identity = hashlib.sha256(
        f"{relative_path}\n{display_name}".encode("utf-8", "replace")
    ).hexdigest()[:12]
    return "/".join([clean_prefix(prefix), *parts, f"{identity}-{name}"])


def presign_put(
    settings: S3BackupSettings,
    key: str,
    size: int,
    *,
    now: datetime | None = None,
) -> dict[str, object]:
    """Create a short-lived SigV4 path-style PUT URL bound to object size."""
    if not settings.configured:
        raise ValueError("S3 backup is not configured")
    if not 0 < size <= MAX_BACKUP_BYTES:
        raise ValueError("Invalid media size")
    if not key.startswith(f"{clean_prefix(settings.prefix)}/"):
        raise ValueError("Object key is outside the configured prefix")

    instant = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    date = instant.strftime("%Y%m%d")
    timestamp = instant.strftime("%Y%m%dT%H%M%SZ")
    scope = f"{date}/{settings.region}/s3/aws4_request"
    parsed = urlsplit(settings.endpoint)
    host = parsed.netloc.lower()
    canonical_uri = "/" + "/".join(
        quote(part, safe="-_.~") for part in (settings.bucket, *key.split("/"))
    )
    signed_headers = "content-length;host"
    parameters = {
        "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
        "X-Amz-Credential": f"{settings.access_key}/{scope}",
        "X-Amz-Date": timestamp,
        "X-Amz-Expires": str(PRESIGN_SECONDS),
        "X-Amz-SignedHeaders": signed_headers,
    }
    canonical_query = urlencode(
        sorted(parameters.items()), quote_via=quote, safe="-_.~"
    )
    canonical_headers = f"content-length:{size}\nhost:{host}\n"
    canonical_request = "\n".join(
        (
            "PUT",
            canonical_uri,
            canonical_query,
            canonical_headers,
            signed_headers,
            "UNSIGNED-PAYLOAD",
        )
    )
    string_to_sign = "\n".join(
        (
            "AWS4-HMAC-SHA256",
            timestamp,
            scope,
            hashlib.sha256(canonical_request.encode()).hexdigest(),
        )
    )
    signing_key = _signature_key(settings.secret_key, date, settings.region)
    parameters["X-Amz-Signature"] = hmac.new(
        signing_key, string_to_sign.encode(), hashlib.sha256
    ).hexdigest()
    query = urlencode(sorted(parameters.items()), quote_via=quote, safe="-_.~")
    return {
        "url": f"{settings.endpoint.rstrip('/')}{canonical_uri}?{query}",
        "method": "PUT",
        "headers": {"Content-Length": str(size)},
        "expires_in": PRESIGN_SECONDS,
    }


def _clean_part(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value)).strip()
    if text in ("", ".", ".."):
        return ""
    cleaned = "".join(
        character
        if character.isalnum() or character in (" ", ".", "-", "_")
        else "_"
        for character in text
    ).strip(" .")
    return cleaned[:160]


def _signature_key(secret: str, date: str, region: str) -> bytes:
    date_key = hmac.new(f"AWS4{secret}".encode(), date.encode(), hashlib.sha256).digest()
    region_key = hmac.new(date_key, region.encode(), hashlib.sha256).digest()
    service_key = hmac.new(region_key, b"s3", hashlib.sha256).digest()
    return hmac.new(service_key, b"aws4_request", hashlib.sha256).digest()
