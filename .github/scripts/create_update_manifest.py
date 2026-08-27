#!/usr/bin/env python3
"""Create the update manifest consumed by ClearTune clients."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()

    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")
    if args.version_code <= 0:
        parser.error("versionCode must be positive")
    if args.tag.removeprefix("v") != args.version_name:
        parser.error("tag must match versionName (for example v1.0.0)")

    manifest = {
        "schemaVersion": 1,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkAssetName": args.apk.name,
        "sha256": sha256(args.apk),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
