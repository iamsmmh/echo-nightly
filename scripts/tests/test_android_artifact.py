"""Tests for the Android artifact resolver used by the release workflows."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent.parent
SPEC = importlib.util.spec_from_file_location(
    "android_artifact", ROOT / "scripts" / "android_artifact.py"
)
android_artifact = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(android_artifact)


class ResolveArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self._original = android_artifact.ROOT
        android_artifact.ROOT = self.root
        self.addCleanup(lambda: setattr(android_artifact, "ROOT", self._original))

    def outputs(self, *relative_names):
        for name in relative_names:
            path = self.root / "app-android" / "build" / "outputs" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"artifact")

    def test_finds_signed_apk(self):
        self.outputs("apk/nightly/app-nightly.apk")
        found = android_artifact.candidates("app-android", "nightly", "apk")
        self.assertEqual(found[0].name, "app-nightly.apk")

    def test_finds_unsigned_apk_when_signing_is_absent(self):
        # AGP appends "-unsigned" when no signing config is applied; the release
        # workflows must not depend on signing secrets being present.
        self.outputs("apk/nightly/app-nightly-unsigned.apk")
        found = android_artifact.candidates("app-android", "nightly", "apk")
        self.assertEqual(found[0].name, "app-nightly-unsigned.apk")

    def test_prefers_signed_output_over_unsigned(self):
        self.outputs(
            "apk/release/app-release-unsigned.apk",
            "apk/release/app-release.apk",
        )
        found = android_artifact.candidates("app-android", "release", "apk")
        self.assertEqual(found[0].name, "app-release.apk")

    def test_finds_bundle(self):
        self.outputs("bundle/stable/app-stable.aab")
        found = android_artifact.candidates("app-android", "stable", "aab")
        self.assertEqual(found[0].name, "app-stable.aab")

    def test_no_match_returns_empty(self):
        self.assertEqual(android_artifact.candidates("app-android", "stable", "aab"), [])

    def test_main_copies_to_destination(self):
        self.outputs("apk/nightly/app-nightly-unsigned.apk")
        code = android_artifact.main(
            [
                "--variant", "nightly",
                "--type", "apk",
                "--destination", "app-android/build/abc1234.apk",
            ]
        )
        self.assertEqual(code, 0)
        self.assertTrue((self.root / "app-android" / "build" / "abc1234.apk").is_file())

    def test_main_fails_when_missing(self):
        self.assertEqual(android_artifact.main(["--variant", "stable", "--type", "aab"]), 1)

    def test_main_optional_tolerates_missing(self):
        code = android_artifact.main(
            ["--variant", "stable", "--type", "aab", "--optional"]
        )
        self.assertEqual(code, 0)


if __name__ == "__main__":
    unittest.main()
