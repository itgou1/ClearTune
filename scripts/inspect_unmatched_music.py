#!/usr/bin/env python3
"""Inspect current tags for filenames listed in the final scrape summary."""

import argparse
import json
from pathlib import Path

from mutagen import File


def first(tags, key):
    value = tags.get(key, []) if tags else []
    if isinstance(value, list):
        return str(value[0]) if value else ""
    return str(value or "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", required=True)
    parser.add_argument("--music-dir", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    summary = json.loads(Path(args.summary).read_text(encoding="utf-8"))
    rows = []
    for filename in summary.get("remaining_filenames", []):
        path = Path(args.music_dir) / filename
        raw = File(path)
        easy = File(path, easy=True)
        raw_tags = raw.tags if raw else None
        keys = list(raw_tags.keys()) if raw_tags else []
        has_cover = bool(getattr(raw, "pictures", [])) or any(
            str(key).startswith("APIC") or str(key) == "covr" for key in keys
        )
        has_lyrics = any(
            str(key).startswith(("USLT", "SYLT"))
            or str(key).casefold() in {"lyrics", "unsyncedlyrics", "©lyr"}
            for key in keys
        )
        lrc_path = path.with_suffix(".lrc")
        rows.append(
            {
                "filename": filename,
                "title": first(easy, "title"),
                "artist": first(easy, "artist"),
                "album": first(easy, "album"),
                "duration_seconds": round(float(raw.info.length), 2) if raw and raw.info else None,
                "has_cover": has_cover,
                "has_embedded_lyrics": has_lyrics,
                "has_lrc": lrc_path.exists(),
            }
        )
    Path(args.output).write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
