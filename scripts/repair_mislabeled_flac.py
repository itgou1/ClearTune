#!/usr/bin/env python3
"""Repair FLAC payloads that were incorrectly named and tagged as MP3 files."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from mutagen.flac import FLAC
from mutagen.id3 import ID3, ID3NoHeaderError


FILES = [
    {
        "source": "林俊杰 - 进阶.mp3",
        "target": "林俊杰 - 进阶.flac",
        "title": "进阶",
        "artist": "林俊杰",
        "album": "进阶",
    },
    {
        "source": "岑宁儿 - 追光者.mp3",
        "target": "岑宁儿 - 追光者.flac",
        "title": "追光者",
        "artist": "岑宁儿",
        "album": "夏至未至 电视剧原声带",
    },
    {
        "source": "屋顶 - 温岚,周杰伦.mp3",
        "target": "屋顶 - 温岚,周杰伦.flac",
        "title": "屋顶",
        "artist": "温岚; 周杰伦",
        "album": "爱回温",
    },
    {
        "source": "赵雷 - 成都.mp3",
        "target": "赵雷 - 成都.flac",
        "title": "成都",
        "artist": "赵雷",
        "album": "成都",
    },
]


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--music-dir", required=True)
    parser.add_argument("--backup-dir", required=True)
    parser.add_argument("--log", required=True)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def id3_values(path: Path) -> dict[str, str]:
    try:
        tags = ID3(path)
    except ID3NoHeaderError:
        return {}
    mapping = {"TIT2": "title", "TPE1": "artist", "TALB": "album"}
    result = {}
    for frame, destination in mapping.items():
        value = tags.get(frame)
        if value:
            result[destination] = str(value).strip()
    return result


def flac_offset(path: Path) -> int:
    with path.open("rb") as handle:
        prefix = handle.read(2 * 1024 * 1024)
    offset = prefix.find(b"fLaC")
    if offset < 0:
        raise RuntimeError("FLAC marker not found in first 2 MiB")
    return offset


def copy_payload(source: Path, destination: Path, offset: int) -> None:
    with source.open("rb") as reader, destination.open("xb") as writer:
        reader.seek(offset)
        shutil.copyfileobj(reader, writer, length=1024 * 1024)
        writer.flush()
        os.fsync(writer.fileno())


def repair_one(entry: dict, music_dir: Path, backup_dir: Path) -> dict:
    source = music_dir / entry["source"]
    target = music_dir / entry["target"]
    temporary = music_dir / f".{entry['target']}.repairing"
    backup = backup_dir / entry["source"]

    if not source.is_file():
        raise FileNotFoundError(source)
    if target.exists():
        raise FileExistsError(target)
    if temporary.exists():
        raise FileExistsError(temporary)

    source_hash = sha256(source)
    backup_dir.mkdir(parents=True, exist_ok=True)
    if backup.exists():
        if sha256(backup) != source_hash:
            raise RuntimeError(f"existing backup hash mismatch: {backup}")
    else:
        shutil.copy2(source, backup)
        if sha256(backup) != source_hash:
            raise RuntimeError(f"backup verification failed: {backup}")

    offset = flac_offset(source)
    metadata = dict(entry)
    metadata.update({key: value for key, value in id3_values(source).items() if value})

    try:
        copy_payload(source, temporary, offset)
        audio = FLAC(temporary)
        if audio.tags is None:
            audio.add_tags()
        audio["TITLE"] = [metadata["title"].strip()]
        audio["ARTIST"] = [part.strip() for part in metadata["artist"].split(";")]
        audio["ALBUM"] = [metadata["album"].strip()]
        audio.save()

        verified = FLAC(temporary)
        if not verified.info or verified.info.length <= 0:
            raise RuntimeError("invalid FLAC duration after repair")
        if not verified.get("title") or not verified.get("artist"):
            raise RuntimeError("required FLAC tags missing after repair")
        ffmpeg = shutil.which("ffmpeg")
        decode_verified = False
        if ffmpeg:
            decode = subprocess.run(
                [ffmpeg, "-v", "error", "-i", str(temporary), "-f", "null", "-"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                text=True,
                timeout=600,
            )
            if decode.returncode != 0:
                raise RuntimeError(f"ffmpeg decode failed: {decode.stderr[-500:]}")
            decode_verified = True

        os.replace(temporary, target)
        source.unlink()
        return {
            "source": str(source),
            "target": str(target),
            "backup": str(backup),
            "source_sha256": source_hash,
            "flac_offset": offset,
            "duration": round(verified.info.length, 3),
            "sample_rate": verified.info.sample_rate,
            "bits_per_sample": verified.info.bits_per_sample,
            "title": verified.get("title", [""])[0],
            "artist": verified.get("artist", []),
            "album": verified.get("album", [""])[0],
            "pictures": len(verified.pictures),
            "decode_verified_in_script": decode_verified,
        }
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise


def main() -> int:
    args = arguments()
    music_dir = Path(args.music_dir).resolve()
    backup_dir = Path(args.backup_dir).resolve()
    log_path = Path(args.log).resolve()
    log_path.parent.mkdir(parents=True, exist_ok=True)

    results = []
    for entry in FILES:
        try:
            result = repair_one(entry, music_dir, backup_dir)
            result["status"] = "repaired"
        except Exception as exc:
            result = {
                "source": str(music_dir / entry["source"]),
                "target": str(music_dir / entry["target"]),
                "status": "failed",
                "error": repr(exc),
            }
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    summary = {
        "repaired": sum(row["status"] == "repaired" for row in results),
        "failed": sum(row["status"] == "failed" for row in results),
        "results": results,
    }
    log_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
