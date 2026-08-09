from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import unittest
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "custom_components" / "rowe_pc_blocker" / "s3_backup.py"
SPEC = importlib.util.spec_from_file_location("parental_device_s3_backup", MODULE_PATH)
S3 = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
import sys

sys.modules[SPEC.name] = S3
SPEC.loader.exec_module(S3)


class S3BackupTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settings = S3.S3BackupSettings(
            endpoint="https://s3.example.test",
            region="us-east-1",
            bucket="family-media",
            access_key="device-writer",
            secret_key="never-return-this-secret",
            prefix="children/child-one",
        )
        self.now = datetime(2026, 8, 9, 12, 0, tzinfo=timezone.utc)

    def test_endpoint_and_bucket_validation_is_strict(self) -> None:
        self.assertTrue(S3.valid_s3_endpoint("https://s3.example.test"))
        for invalid in (
            "http://s3.example.test",
            "https://user:password@s3.example.test",
            "https://s3.example.test/path",
            "https://s3.example.test?token=value",
        ):
            self.assertFalse(S3.valid_s3_endpoint(invalid))
        self.assertTrue(S3.valid_bucket("family-media"))
        self.assertFalse(S3.valid_bucket("Family Media"))
        self.assertFalse(S3.valid_bucket("bad..bucket"))
        self.assertTrue(S3.valid_region("us-east-1"))
        self.assertFalse(S3.valid_region("US East 1"))
        self.assertEqual(S3.clean_prefix("../../"), "")

    def test_object_key_is_confined_to_configured_prefix(self) -> None:
        key = S3.object_key(
            "children/child-one",
            "DCIM/../Camera/",
            "summer/holiday.jpg",
        )
        self.assertTrue(key.startswith("children/child-one/DCIM/Camera/"))
        self.assertTrue(key.endswith("-summer_holiday.jpg"))
        self.assertNotIn("..", key.split("/"))
        collision = S3.object_key(
            "children/child-one",
            "DCIM/../Camera/",
            "summer?holiday.jpg",
        )
        self.assertNotEqual(key, collision)

    def test_presign_is_short_lived_size_bound_and_never_returns_secret(self) -> None:
        key = "children/child-one/DCIM/Camera/photo.jpg"
        signed = S3.presign_put(self.settings, key, 1234, now=self.now)
        parsed = urlsplit(signed["url"])
        query = parse_qs(parsed.query)
        self.assertEqual(signed["headers"], {"Content-Length": "1234"})
        self.assertEqual(query["X-Amz-Expires"], ["900"])
        self.assertEqual(query["X-Amz-SignedHeaders"], ["content-length;host"])
        self.assertNotIn(self.settings.secret_key, signed["url"])
        different = S3.presign_put(self.settings, key, 1235, now=self.now)
        self.assertNotEqual(signed["url"], different["url"])

    def test_presign_rejects_oversize_and_wrong_prefix(self) -> None:
        with self.assertRaises(ValueError):
            S3.presign_put(
                self.settings,
                "children/child-two/photo.jpg",
                1,
                now=self.now,
            )
        with self.assertRaises(ValueError):
            S3.presign_put(
                self.settings,
                "children/child-one/video.mp4",
                S3.MAX_BACKUP_BYTES + 1,
                now=self.now,
            )


if __name__ == "__main__":
    unittest.main()
