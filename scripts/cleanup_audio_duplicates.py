#!/usr/bin/env python3
"""Select and optionally delete lower-quality members of duplicate audio groups."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path


LOSSLESS_CODECS = {"flac", "alac", "ape", "wavpack", "tta", "pcm_s16le", "pcm_s24le", "pcm_s32le"}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", required=True)
    parser.add_argument("--music-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--apply", action="store_true")
    return parser.parse_args()


def run_json(command: list[str], timeout: int = 120) -> dict:
    result = subprocess.run(command, capture_output=True, check=True, timeout=timeout)
    return json.loads(result.stdout)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def probe(path: Path) -> dict:
    data = run_json(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration:format_tags",
            "-show_entries",
            "stream=index,codec_type,codec_name,sample_rate,channels,bits_per_raw_sample,bit_rate:stream_disposition=attached_pic",
            "-of",
            "json",
            str(path),
        ]
    )
    stream = next(
        (item for item in data.get("streams", []) if item.get("codec_type") == "audio"),
        {},
    )
    fmt = data.get("format", {})
    duration = float(fmt.get("duration") or 0)
    packet_result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "packet=size",
            "-of",
            "csv=p=0",
            str(path),
        ],
        capture_output=True,
        check=True,
        text=True,
        timeout=180,
    )
    packet_bytes = sum(
        int(line.strip().split(",", 1)[0])
        for line in packet_result.stdout.splitlines()
        if line.strip().split(",", 1)[0].isdigit()
    )
    packet_bitrate = round(packet_bytes * 8 / duration) if duration > 0 else 0
    tags = fmt.get("tags", {}) or {}
    attached_pictures = sum(
        int(item.get("disposition", {}).get("attached_pic") or 0)
        for item in data.get("streams", [])
    )
    sidecar = path.with_suffix(".lrc")
    codec = str(stream.get("codec_name") or "")
    return {
        "filename": path.name,
        "size": path.stat().st_size,
        "sha256": sha256(path),
        "codec": codec,
        "lossless": codec in LOSSLESS_CODECS,
        "duration": round(duration, 3),
        "audio_bitrate": packet_bitrate,
        "audio_bitrate_kbps": round(packet_bitrate / 1000, 2),
        "reported_stream_bitrate": int(stream.get("bit_rate") or 0),
        "sample_rate": int(stream.get("sample_rate") or 0),
        "channels": int(stream.get("channels") or 0),
        "bits_per_sample": int(stream.get("bits_per_raw_sample") or 0),
        "metadata_fields": len(tags),
        "attached_pictures": attached_pictures,
        "sidecar_lrc": sidecar.is_file(),
        "sidecar_lrc_size": sidecar.stat().st_size if sidecar.is_file() else 0,
    }


def quality_key(item: dict) -> tuple:
    # Bitrate is rounded to the nearest kbps so encoder bookkeeping noise does
    # not override metadata and completeness when both files are effectively 320 kbps.
    bitrate_kbps = round(item["audio_bitrate"] / 1000)
    return (
        int(item["lossless"]),
        bitrate_kbps,
        item["bits_per_sample"],
        item["sample_rate"],
        item["channels"],
        int(item["sidecar_lrc"]),
        item["attached_pictures"],
        item["metadata_fields"],
        item["duration"],
        item["size"],
    )


def build_groups(report: dict) -> tuple[list[list[str]], dict[str, dict]]:
    parent: dict[str, str] = {}
    inventory: dict[str, dict] = {}

    def root(value: str) -> str:
        parent.setdefault(value, value)
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value

    for pair in report.get("candidate_pairs", []):
        if pair.get("confidence") != "exact_recording":
            continue
        left = pair["left"]["filename"]
        right = pair["right"]["filename"]
        inventory[left] = pair["left"]
        inventory[right] = pair["right"]
        left_root = root(left)
        right_root = root(right)
        if left_root != right_root:
            parent[right_root] = left_root

    groups: dict[str, list[str]] = {}
    for filename in inventory:
        groups.setdefault(root(filename), []).append(filename)
    return [sorted(group) for group in groups.values()], inventory


def safe_path(music_dir: Path, filename: str) -> Path:
    if Path(filename).name != filename:
        raise RuntimeError(f"unsafe filename in report: {filename!r}")
    path = (music_dir / filename).resolve()
    if path.parent != music_dir:
        raise RuntimeError(f"path escaped music directory: {path}")
    return path


def main() -> int:
    options = arguments()
    report = json.loads(Path(options.report).read_text(encoding="utf-8"))
    music_dir = Path(options.music_dir).resolve()
    groups, inventory = build_groups(report)

    current: dict[str, dict] = {}
    for index, filename in enumerate(sorted(inventory), 1):
        path = safe_path(music_dir, filename)
        if not path.is_file():
            raise FileNotFoundError(path)
        expected_size = int(inventory[filename]["size"])
        if path.stat().st_size != expected_size:
            raise RuntimeError(f"file changed since fingerprint scan: {path}")
        current[filename] = probe(path)
        if index % 10 == 0 or index == len(inventory):
            print(json.dumps({"event": "probe", "completed": index}, ensure_ascii=False), flush=True)

    decisions = []
    all_keep: set[str] = set()
    all_delete: set[str] = set()
    for group in groups:
        ranked = sorted((current[name] for name in group), key=quality_key, reverse=True)
        keep = ranked[0]
        delete = ranked[1:]
        all_keep.add(keep["filename"])
        all_delete.update(item["filename"] for item in delete)
        decisions.append(
            {
                "keep": keep,
                "delete": delete,
                "reason": (
                    "lossless codec preferred"
                    if keep["lossless"] and any(not item["lossless"] for item in delete)
                    else "highest measured audio bitrate; ties resolved by lyrics, metadata, duration and size"
                ),
            }
        )

    if all_keep & all_delete:
        raise RuntimeError("a file was selected for both keeping and deletion")
    if len(decisions) != 31 or len(all_delete) != 32:
        raise RuntimeError(
            f"unexpected cleanup scope: groups={len(decisions)} delete={len(all_delete)}"
        )

    deleted_bytes = 0
    lyric_moves = []
    if options.apply:
        for decision in decisions:
            keep_path = safe_path(music_dir, decision["keep"]["filename"])
            keep_lrc = keep_path.with_suffix(".lrc")
            for item in decision["delete"]:
                delete_path = safe_path(music_dir, item["filename"])
                delete_lrc = delete_path.with_suffix(".lrc")
                if delete_lrc.is_file() and not keep_lrc.exists():
                    delete_lrc.rename(keep_lrc)
                    lyric_moves.append(
                        {"from": delete_lrc.name, "to": keep_lrc.name, "bytes": keep_lrc.stat().st_size}
                    )
                deleted_bytes += delete_path.stat().st_size
                delete_path.unlink()

    manifest = {
        "mode": "apply" if options.apply else "dry_run",
        "music_dir": str(music_dir),
        "groups": len(decisions),
        "kept_files": len(all_keep),
        "delete_files": len(all_delete),
        "deleted_bytes": deleted_bytes,
        "lyric_moves": lyric_moves,
        "decisions": sorted(decisions, key=lambda row: row["keep"]["filename"]),
    }
    Path(options.output).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {
                "event": "finish",
                "mode": manifest["mode"],
                "groups": manifest["groups"],
                "delete_files": manifest["delete_files"],
                "deleted_bytes": deleted_bytes,
                "output": options.output,
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
