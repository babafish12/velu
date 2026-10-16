"""Unit controls for the release manifest helper.

SDK subprocesses are mocked here. These tests do not establish that an APK is
really signed; the actual helper invokes apksigner before emitting metadata.
"""
from pathlib import Path
import importlib.util
import json
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("create_update_manifest", Path(__file__).with_name("create_update_manifest.py"))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "velu.apk"
        self.apk.write_bytes(b"synthetic SDK-mocked fixture, not a signed APK")
        self.badging = "package: name='de.voci' versionCode='2' versionName='0.2.0'\nminSdkVersion:'28'\n"
        self.commands = []

    def run_sdk(self, command, **kwargs):
        self.commands.append(command)
        self.assertTrue(kwargs["check"])
        return SimpleNamespace(stdout=self.badging if command[0] == "aapt2" else "fixture signature result")

    def create(self):
        return module.manifest_for(self.apk, aapt2="aapt2", apksigner="apksigner")

    def test_android_metadata_and_actual_file_hash(self):
        with patch.object(module.subprocess, "run", self.run_sdk):
            result = self.create()
        self.assertEqual(result["applicationId"], "de.voci")
        self.assertEqual(result["versionCode"], 2)
        self.assertEqual(result["versionName"], "0.2.0")
        self.assertEqual(result["minSdk"], 28)
        self.assertEqual(result["apkName"], "velu.apk")
        self.assertEqual(result["sizeBytes"], self.apk.stat().st_size)
        self.assertEqual(result["sha256"], module.hashlib.sha256(self.apk.read_bytes()).hexdigest())
        self.assertEqual(self.commands[0][0:2], ["apksigner", "verify"])
        self.assertEqual(self.commands[1][0:3], ["aapt2", "dump", "badging"])
        json.dumps(result)

    def test_legacy_aapt_sdk_label(self):
        self.badging = self.badging.replace("minSdkVersion", "sdkVersion")
        with patch.object(module.subprocess, "run", self.run_sdk):
            self.assertEqual(self.create()["minSdk"], 28)

    def test_wrong_package_is_rejected(self):
        self.badging = self.badging.replace("de.voci", "different.package")
        with patch.object(module.subprocess, "run", self.run_sdk), self.assertRaises(ValueError):
            self.create()

    def test_signature_verification_failure_is_not_ignored(self):
        with patch.object(module.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["apksigner"])), self.assertRaises(subprocess.CalledProcessError):
            self.create()

    def test_missing_sdk_metadata_is_rejected(self):
        self.badging = self.badging.splitlines()[0]
        with patch.object(module.subprocess, "run", self.run_sdk), self.assertRaises(ValueError):
            self.create()

    def test_same_length_file_mutation_during_inspection_is_rejected(self):
        def mutate(command, **kwargs):
            if command[0] == "aapt2":
                self.apk.write_bytes(b"x" * self.apk.stat().st_size)
            return self.run_sdk(command, **kwargs)
        with patch.object(module.subprocess, "run", mutate), self.assertRaisesRegex(ValueError, "changed"):
            self.create()

    def test_symlink_is_rejected(self):
        original = self.apk
        self.apk = original.with_name("alias.apk")
        self.apk.symlink_to(original)
        with self.assertRaises(ValueError):
            self.create()

    def test_empty_apk_is_rejected(self):
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            self.create()

if __name__ == "__main__":
    unittest.main()
