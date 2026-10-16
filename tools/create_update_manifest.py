#!/usr/bin/env python3
"""Create Velu update metadata from a verified APK, never from guessed versions."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

MAX_APK_BYTES = 128 * 1024 * 1024

def sdk_tool(name: str, explicit: str | None) -> str:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise ValueError(f"Missing {name} executable")
        return str(path)
    found = shutil.which(name)
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidates = []
        for directory in (Path(sdk) / "build-tools").glob("*"):
            if re.fullmatch(r"\d+\.\d+\.\d+", directory.name) and (directory / name).is_file():
                candidates.append((tuple(map(int, directory.name.split("."))), directory / name))
        if candidates:
            return str(max(candidates)[1])
    raise ValueError(f"Set ANDROID_HOME or provide --{name}")

def manifest_for(apk: Path, *, aapt2: str, apksigner: str) -> dict:
    if not apk.is_file() or apk.is_symlink():
        raise ValueError("APK must be a regular, non-symlink file")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", apk.name):
        raise ValueError("APK asset name must be a simple .apk filename")
    size = apk.stat().st_size
    if not 0 < size <= MAX_APK_BYTES:
        raise ValueError("APK size exceeds updater bounds")
    with apk.open("rb") as stream:
        initial_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    subprocess.run([apksigner, "verify", "--verbose", str(apk)], check=True,
                   capture_output=True, text=True, timeout=60)
    badging = subprocess.run([aapt2, "dump", "badging", str(apk)], check=True,
                            capture_output=True, text=True, timeout=60).stdout
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.MULTILINE)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'$", badging, re.MULTILINE)
    if package is None or sdk is None:
        raise ValueError("APK does not expose valid Android version metadata")
    app_id, version_code, version_name = package.groups()
    if app_id != "de.voci" or not 0 < int(version_code) <= 2100000000:
        raise ValueError("Wrong applicationId or invalid versionCode")
    if not version_name or len(version_name) > 64 or any(ord(c) < 32 for c in version_name):
        raise ValueError("Invalid versionName")
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if apk.stat().st_size != size or digest != initial_digest:
        raise ValueError("APK changed during metadata creation")
    return {
        "schemaVersion": 1,
        "applicationId": app_id,
        "versionCode": int(version_code),
        "versionName": version_name,
        "apkName": apk.name,
        "sha256": digest,
        "sizeBytes": size,
        "minSdk": int(sdk.group(1)),
    }

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--aapt2")
    parser.add_argument("--apksigner")
    args = parser.parse_args()
    result = manifest_for(args.apk, aapt2=sdk_tool("aapt2", args.aapt2),
                          apksigner=sdk_tool("apksigner", args.apksigner))
    # Refuse an existing destination rather than silently replacing a reviewed manifest.
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
