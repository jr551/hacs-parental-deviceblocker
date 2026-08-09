from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class PublicReleaseTests(unittest.TestCase):
    def test_no_universal_parent_pin_is_shipped(self):
        android = (
            ROOT / "android/app/src/main/java/lol/rowe/blocker/ParentPin.java"
        ).read_text()
        constants = (
            ROOT / "custom_components/rowe_pc_blocker/const.py"
        ).read_text()
        self.assertNotIn("DEFAULT_PIN", android)
        self.assertNotIn("DEFAULT_PARENT_PIN", constants)

    def test_public_branding_is_generic(self):
        manifest = (
            ROOT / "custom_components/rowe_pc_blocker/manifest.json"
        ).read_text()
        android_manifest = (
            ROOT / "android/app/src/main/AndroidManifest.xml"
        ).read_text()
        self.assertIn('"name": "Parental Device Blocker"', manifest)
        self.assertIn('android:label="Parental Device Blocker"', android_manifest)

    def test_android_public_build_does_not_silently_reuse_legacy_signing_key(self):
        script = (ROOT / "scripts/build-android-docker.sh").read_text()
        dockerfile = (ROOT / "android/Dockerfile").read_text()
        self.assertIn("PARENTAL_DEVICE_BLOCKER_REUSE_LEGACY_KEY", script)
        self.assertIn("SIGNING_KEY_ID", script)
        self.assertIn("ARG SIGNING_KEY_ID", dockerfile)
        self.assertNotIn('if [[ -f "$legacy_keystore_dir/android-debug.keystore" ]]', script)

    def test_household_only_deployment_files_are_not_published(self):
        self.assertFalse(
            (ROOT / "deploy/home-assistant/rowe_pc_blocker.yaml").exists()
        )
        self.assertFalse((ROOT / "deploy/android/android-mcp-forward").exists())

    def test_public_tree_has_no_known_household_values(self):
        forbidden = (
            "matthew" + "pc",
            "android" + "testphone",
            "7b0e3e" + "640602",
            "homeassistant" + ".rowe.lol",
            "s3" + ".rowe.lol",
            "john" + " rowe",
            "rowe" + " device blocker",
        )
        ignored = {".git", "artifacts", "bin", "obj", "__pycache__"}
        for path in ROOT.rglob("*"):
            if not path.is_file() or any(part in ignored for part in path.parts):
                continue
            try:
                text = path.read_text(encoding="utf-8").lower()
            except UnicodeDecodeError:
                continue
            for value in forbidden:
                self.assertNotIn(value, text, f"{value!r} found in {path}")


if __name__ == "__main__":
    unittest.main()
