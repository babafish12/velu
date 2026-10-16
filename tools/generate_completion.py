#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Velu contributors
"""Generate the completion asset; independently authored by Codex, 2026-10-16.

Provenance and recipe (also applies to the generated JSON): newly written
geometry and keyframes for this task, without reading or adapting the old asset
or any external artwork/template. Only its required historical composition name
is retained. No images, fonts, URLs, randomness, timestamps or third-party code.

On an 80 x 80 canvas, draw a blue (#3658D8) circle of diameter 60 with a
6-unit stroke, plus a blue disk of diameter 48 behind a white check. Scale the
blue geometry about (40, 40) from 70% to 100% over frames 0..24 using a smooth
cubic easing curve. The check is the open polyline (-11, 0), (-3, 8), (12, -9),
with a 5-unit white round stroke, fading in over frames 8..24. Hold the final
state through frame 36 (exclusive). At 60 fps this is a 600 ms composition.
Serialize deterministic insertion-order JSON keys (content ty first), compact
separators, UTF-8 and one trailing LF.

Run with --check for a read-only byte comparison, or --write to replace only
app/src/main/assets/complete.json relative to this script's project root.
No default write and no configurable output path are provided.

MIT License (script and generated asset)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

import argparse
import json
from pathlib import Path
import sys


ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/complete.json"
BLUE = [0.21176470588235294, 0.34509803921568627, 0.8470588235294118, 1]


def fixed(value):
    return {"a": 0, "k": value}


def transition(start_frame, end_frame, start, end):
    return {"a": 1, "k": [
        {"t": start_frame, "s": start, "e": end,
         "o": {"x": 0.333333, "y": 0}, "i": {"x": 0.666667, "y": 1}},
        {"t": end_frame, "s": end},
    ]}


def transform(position):
    return {"a": fixed([0, 0, 0]), "p": fixed(position),
            "s": fixed([100, 100, 100]), "r": fixed(0), "o": fixed(100)}


def group(name, *shapes):
    return {"ty": "gr", "nm": name, "it": [
        *shapes, {"ty": "tr", **transform([0, 0])},
    ]}


def ellipse(diameter):
    return {"ty": "el", "d": 1, "p": fixed([0, 0]), "s": fixed([diameter, diameter])}


def stroke(color, width):
    return {"ty": "st", "c": fixed(color), "o": fixed(100),
            "w": fixed(width), "lc": 2, "lj": 2, "ml": 4}


def layer(index, name, shapes):
    return {"ddd": 0, "ind": index, "ty": 4, "nm": name, "sr": 1,
            "ks": transform([40, 40, 0]), "shapes": shapes,
            "ip": 0, "op": 36, "st": 0, "bm": 0}


def generate_bytes():
    """Return the complete deterministic asset without reading or writing files."""
    ring = layer(2, "Ring", [
        group("Outline", ellipse(60), stroke(BLUE, 6)),
        group("Center", ellipse(48), {"ty": "fl", "c": fixed(BLUE), "o": fixed(100), "r": 1}),
    ])
    ring["ks"]["s"] = transition(0, 24, [70, 70, 100], [100, 100, 100])
    check = layer(1, "Check", [group("Tick", {
        "ty": "sh", "ks": fixed({
            "i": [[0, 0], [0, 0], [0, 0]],
            "o": [[0, 0], [0, 0], [0, 0]],
            "v": [[-11, 0], [-3, 8], [12, -9]], "c": False,
        }),
    }, stroke([1, 1, 1, 1], 5))])
    check["ks"]["o"] = transition(8, 24, [0], [100])
    document = {"v": "5.7.4", "nm": "VOCI completion", "ddd": 0,
                "w": 80, "h": 80, "fr": 60, "ip": 0, "op": 36,
                "assets": [], "layers": [check, ring]}
    return (json.dumps(document, sort_keys=False, separators=(",", ":"),
                       ensure_ascii=True, allow_nan=False) + "\n").encode("utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0], allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Compare bytes without writing")
    mode.add_argument("--write", action="store_true", help="Replace the fixed project asset")
    args = parser.parse_args(argv)
    expected = generate_bytes()
    if args.check:
        try:
            actual = ASSET.read_bytes()
        except OSError:
            print("complete.json is missing or unreadable; no files written", file=sys.stderr)
            return 1
        if actual != expected:
            print("complete.json mismatch; no files written", file=sys.stderr)
            return 1
        print("complete.json matches generated bytes")
        return 0
    ASSET.write_bytes(expected)
    print("Wrote app/src/main/assets/complete.json")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
