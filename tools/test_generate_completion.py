#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Stdlib contract tests for the independently authored completion asset."""

import json
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = ROOT / "tools/generate_completion.py"
ASSET = Path("app/src/main/assets/complete.json")


class CompletionGenerationTest(unittest.TestCase):
    def generator(self):
        self.assertTrue(GENERATOR.is_file(), "Expected RED: generator is missing")
        return runpy.run_path(str(GENERATOR))

    def document(self):
        return json.loads(self.generator()["generate_bytes"]())

    def fixture(self):
        self.generator()
        evidence = ROOT / "evidence/r7-completion-generation"
        evidence.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="cli-fixture-", dir=evidence)
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / "tools").mkdir()
        shutil.copyfile(GENERATOR, root / "tools/generate_completion.py")
        (root / ASSET).parent.mkdir(parents=True)
        return root

    def cli(self, root, *args):
        return subprocess.run(
            [sys.executable, "-B", str(root / "tools/generate_completion.py"), *args],
            cwd=root / "tools", capture_output=True, timeout=10,
        )

    def test_deterministic_bytes(self):
        first = self.generator()["generate_bytes"]()
        self.assertIsInstance(first, bytes)
        self.assertEqual(first, self.generator()["generate_bytes"]())
        self.assertTrue(first.endswith(b"\n"))
        self.assertLess(len(first), 16000)

    def test_content_models_serialize_type_first(self):
        models = []
        def inspect(value):
            if isinstance(value, dict):
                if isinstance(value.get("ty"), str):
                    models.append(value)
                    self.assertEqual("ty", next(iter(value)),
                                     "Lottie ContentModelParser discards fields before ty")
                for child in value.values():
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)
        inspect(self.document())
        self.assertEqual(12, len(models))

    def test_exact_project_asset(self):
        self.assertEqual(self.generator()["generate_bytes"](), (ROOT / ASSET).read_bytes())

    def test_bounded_dimensions_and_duration(self):
        doc = self.document()
        self.assertEqual((80, 80, 60, 0, 36), tuple(doc[k] for k in ("w", "h", "fr", "ip", "op")))
        self.assertEqual(0.6, (doc["op"] - doc["ip"]) / doc["fr"])
        self.assertEqual("VOCI completion", doc["nm"])
        self.assertEqual(0, doc["ddd"])
        self.assertEqual(2, len(doc["layers"]))
        for layer in doc["layers"]:
            self.assertEqual(4, layer["ty"])
            self.assertEqual((0, 36), (layer["ip"], layer["op"]))

    def test_no_external_assets_fonts_or_text(self):
        doc = self.document()
        self.assertEqual([], doc["assets"])

        def inspect(value):
            if isinstance(value, dict):
                self.assertFalse({"fonts", "chars", "refId", "u", "url"} & value.keys())
                for child in value.values():
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)
            elif isinstance(value, str):
                self.assertNotIn("://", value)
                self.assertFalse(value.startswith("data:"))

        inspect(doc)

    def test_visible_drawn_shapes_and_safe_extents(self):
        doc = self.document()
        ring = next(layer for layer in doc["layers"] if layer["nm"] == "Ring")
        check = next(layer for layer in doc["layers"] if layer["nm"] == "Check")
        self.assertEqual([40, 40, 0], ring["ks"]["p"]["k"])
        self.assertEqual(100, ring["ks"]["o"]["k"])
        groups = ring["shapes"]
        self.assertEqual(2, len(groups))
        for group in groups:
            ellipse = next(shape for shape in group["it"] if shape["ty"] == "el")
            paint = next(shape for shape in group["it"] if shape["ty"] in ("st", "fl"))
            diameter = ellipse["s"]["k"]
            self.assertEqual([0, 0], ellipse["p"]["k"])
            self.assertEqual(diameter[0], diameter[1])
            self.assertGreater(diameter[0], 0)
            width = paint.get("w", {"k": 0})["k"]
            self.assertLess(diameter[0] + width, 80)
            self.assertEqual(100, paint["o"]["k"])
            red, green, blue, alpha = paint["c"]["k"]
            self.assertTrue(0 <= red < green < blue <= 1)
            self.assertEqual(1, alpha)
        shapes = check["shapes"][0]["it"]
        path = next(shape for shape in shapes if shape["ty"] == "sh")["ks"]["k"]
        self.assertFalse(path["c"])
        self.assertEqual(3, len({tuple(vertex) for vertex in path["v"]}))
        self.assertTrue(all(abs(coordinate) <= 16 for vertex in path["v"] for coordinate in vertex))
        stroke = next(shape for shape in shapes if shape["ty"] == "st")
        self.assertEqual([1, 1, 1, 1], stroke["c"]["k"])
        self.assertGreaterEqual(stroke["w"]["k"], 4)
        self.assertEqual(100, stroke["o"]["k"])

    def test_growth_reveal_and_settled_end(self):
        doc = self.document()
        ring = next(layer for layer in doc["layers"] if layer["nm"] == "Ring")
        check = next(layer for layer in doc["layers"] if layer["nm"] == "Check")
        growth = ring["ks"]["s"]
        self.assertEqual(1, growth["a"])
        self.assertEqual([70, 70, 100], growth["k"][0]["s"])
        self.assertEqual([100, 100, 100], growth["k"][-1]["s"])
        self.assertEqual(0, growth["k"][0]["t"])
        self.assertLessEqual(growth["k"][-1]["t"], 28)
        reveal = check["ks"]["o"]
        self.assertEqual(1, reveal["a"])
        self.assertEqual([0], reveal["k"][0]["s"])
        self.assertEqual([100], reveal["k"][-1]["s"])
        self.assertLessEqual(reveal["k"][-1]["t"], 28)

    def test_check_matches_without_write(self):
        root = self.fixture()
        asset = root / ASSET
        asset.write_bytes(self.generator()["generate_bytes"]())
        before = asset.stat()
        result = self.cli(root, "--check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before.st_mtime_ns, asset.stat().st_mtime_ns)
        self.assertEqual(before.st_ino, asset.stat().st_ino)

    def test_check_refuses_mismatch_without_write(self):
        root = self.fixture()
        asset = root / ASSET
        sentinel = b'{"deliberately":"different"}\n'
        asset.write_bytes(sentinel)
        before = asset.stat()
        result = self.cli(root, "--check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(b"mismatch", result.stderr.lower())
        self.assertEqual(sentinel, asset.read_bytes())
        self.assertEqual(before.st_mtime_ns, asset.stat().st_mtime_ns)
        self.assertEqual(before.st_ino, asset.stat().st_ino)

    def test_check_missing_does_not_create_asset(self):
        root = self.fixture()
        result = self.cli(root, "--check")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertFalse((root / ASSET).exists())

    def test_only_explicit_write_replaces_fixed_asset(self):
        root = self.fixture()
        asset = root / ASSET
        asset.write_bytes(b"sentinel")
        for args in ((), ("--check", "--write"), ("--write", "--output", "other.json")):
            result = self.cli(root, *args)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertEqual(b"sentinel", asset.read_bytes())
        result = self.cli(root, "--write")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.generator()["generate_bytes"](), asset.read_bytes())
        self.assertEqual({Path("tools/generate_completion.py"), ASSET},
                         {path.relative_to(root) for path in root.rglob("*") if path.is_file()})


if __name__ == "__main__":
    unittest.main()
