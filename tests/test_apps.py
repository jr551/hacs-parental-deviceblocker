"""The parent's allowlist and the phone's installed-app report."""

import importlib.util
import sys
import unittest
from pathlib import Path


def _load(name, filename):
    path = Path(__file__).resolve().parents[1] / "custom_components" / "rowe_pc_blocker" / filename
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


APPS = _load("rowe_pc_blocker_apps", "apps.py")


class NormalizeAllowedAppsTests(unittest.TestCase):
    def test_a_parent_edit_is_trimmed_and_deduplicated(self):
        self.assertEqual(
            APPS.normalize_allowed_apps(" com.whatsapp ,com.android.dialer,com.whatsapp "),
            "com.whatsapp, com.android.dialer",
        )

    def test_an_empty_list_is_allowed(self):
        self.assertEqual(APPS.normalize_allowed_apps(""), "")
        self.assertEqual(APPS.normalize_allowed_apps("  ,  "), "")

    def test_a_typo_is_refused_rather_than_dropped(self):
        with self.assertRaises(ValueError):
            APPS.normalize_allowed_apps("com.whatsapp, not a package")
        with self.assertRaises(ValueError):
            APPS.normalize_allowed_apps("com.whatsapp, 1com.bad")
        with self.assertRaises(ValueError):
            APPS.normalize_allowed_apps("com.whatsapp, com.two..dots")

    def test_a_list_that_does_not_fit_the_text_entity_is_refused(self):
        packages = [f"com.example.app{index}" for index in range(40)]
        with self.assertRaises(ValueError):
            APPS.normalize_allowed_apps(", ".join(packages))
        self.assertGreater(len(", ".join(packages)), APPS.MAX_ALLOWED_APPS_LENGTH)

    def test_the_longest_accepted_list_fits_a_text_entity(self):
        normalized = APPS.normalize_allowed_apps("com.whatsapp, com.android.dialer")
        self.assertLessEqual(len(normalized), APPS.MAX_ALLOWED_APPS_LENGTH)


class ParseAllowedAppsTests(unittest.TestCase):
    def test_a_stored_list_becomes_package_names(self):
        self.assertEqual(
            APPS.parse_allowed_apps("com.whatsapp, com.android.dialer"),
            ["com.whatsapp", "com.android.dialer"],
        )

    def test_blanks_duplicates_and_junk_are_dropped(self):
        self.assertEqual(
            APPS.parse_allowed_apps("com.whatsapp,,com.whatsapp, nope!, com.maps"),
            ["com.whatsapp", "com.maps"],
        )

    def test_an_empty_value_is_an_empty_list(self):
        self.assertEqual(APPS.parse_allowed_apps(""), [])
        self.assertEqual(APPS.parse_allowed_apps(None), [])


class ParseInstalledAppsTests(unittest.TestCase):
    def test_the_phone_report_is_bounded_and_cleaned(self):
        apps = APPS.parse_installed_apps(
            {
                "installed_apps": [
                    {"package": " com.whatsapp ", "label": " WhatsApp "},
                    {"package": "com.maps"},
                    {"label": "no package"},
                    "not a record",
                ]
            }
        )
        self.assertEqual(
            apps,
            [
                {"package": "com.whatsapp", "label": "WhatsApp"},
                {"package": "com.maps", "label": ""},
            ],
        )

    def test_a_heartbeat_without_apps_keeps_nothing(self):
        self.assertEqual(APPS.parse_installed_apps({}), [])
        self.assertEqual(APPS.parse_installed_apps({"installed_apps": "nope"}), [])

    def test_a_long_report_is_capped(self):
        report = [
            {"package": f"com.example.app{index}", "label": "App"}
            for index in range(APPS.MAX_INSTALLED_APPS + 50)
        ]
        self.assertEqual(
            len(APPS.parse_installed_apps({"installed_apps": report})),
            APPS.MAX_INSTALLED_APPS,
        )

    def test_long_fields_are_truncated(self):
        apps = APPS.parse_installed_apps(
            {"installed_apps": [{"package": "p" * 500, "label": "l" * 500}]}
        )
        self.assertEqual(len(apps[0]["package"]), APPS.MAX_APP_FIELD_LENGTH)
        self.assertEqual(len(apps[0]["label"]), APPS.MAX_APP_FIELD_LENGTH)


if __name__ == "__main__":
    unittest.main()


class DefaultAllowedAppsTests(unittest.TestCase):
    """The list a parent sees before editing anything."""

    def test_default_list_fits_a_text_entity_state(self):
        joined = ", ".join(APPS.DEFAULT_ALLOWED_APPS)
        self.assertLessEqual(len(joined), APPS.MAX_ALLOWED_APPS_LENGTH)

    def test_default_list_survives_a_round_trip(self):
        joined = ", ".join(APPS.DEFAULT_ALLOWED_APPS)
        self.assertEqual(APPS.parse_allowed_apps(joined), list(APPS.DEFAULT_ALLOWED_APPS))

    def test_default_list_has_no_duplicates(self):
        self.assertEqual(
            len(APPS.DEFAULT_ALLOWED_APPS), len(set(APPS.DEFAULT_ALLOWED_APPS))
        )
