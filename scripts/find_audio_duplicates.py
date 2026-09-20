#!/usr/bin/env python3
"""Find duplicate recordings using FFmpeg's Chromaprint muxer."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import re
import struct
import subprocess
import sys
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path


AUDIO_SUFFIXES = {".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma"}


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--music-dir", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--duration-window", type=float, default=12.0)
    parser.add_argument("--candidate-threshold", type=float, default=0.23)
    return parser.parse_args()


def normalize(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    text = re.sub(r"\([^)]*(?:live|现场|伴奏|翻唱|cover|remix|版)[^)]*\)", "", text)
    return "".join(char for char in text if char.isalnum())


def probe(path: Path) -> dict:
    command = [
        "ffprobe",
        "-v",
        "error",
        "-show_entries",
        "format=duration,bit_rate:format_tags=title,artist,album",
        "-show_entries",
        "stream=index,codec_type,codec_name,sample_rate,channels,bits_per_raw_sample",
        "-of",
        "json",
        str(path),
    ]
    result = subprocess.run(command, capture_output=True, check=True, timeout=60)
    data = json.loads(result.stdout)
    audio_stream = next(
        (stream for stream in data.get("streams", []) if stream.get("codec_type") == "audio"),
        {},
    )
    fmt = data.get("format", {})
    tags = {str(k).casefold(): v for k, v in fmt.get("tags", {}).items()}
    return {
        "duration": float(fmt.get("duration") or 0),
        "bit_rate": int(fmt.get("bit_rate") or 0),
        "codec": audio_stream.get("codec_name", ""),
        "sample_rate": int(audio_stream.get("sample_rate") or 0),
        "channels": int(audio_stream.get("channels") or 0),
        "bits_per_sample": int(audio_stream.get("bits_per_raw_sample") or 0),
        "title": str(tags.get("title") or ""),
        "artist": str(tags.get("artist") or ""),
        "album": str(tags.get("album") or ""),
    }


def fingerprint(path: Path, seconds: int) -> tuple[int, ...]:
    command = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(path),
        "-map",
        "0:a:0",
        "-t",
        str(seconds),
        "-ac",
        "1",
        "-ar",
        "11025",
        "-f",
        "chromaprint",
        "-fp_format",
        "raw",
        "-",
    ]
    result = subprocess.run(command, capture_output=True, check=True, timeout=180)
    raw = result.stdout
    if not raw or len(raw) % 4:
        raise RuntimeError(f"invalid raw fingerprint length: {len(raw)}")
    return struct.unpack(f"<{len(raw) // 4}I", raw)


def inspect(path: Path, seconds: int) -> dict:
    info = probe(path)
    info.update(
        {
            "path": str(path),
            "filename": path.name,
            "size": path.stat().st_size,
            "suffix": path.suffix.casefold(),
            "fingerprint": fingerprint(path, seconds),
        }
    )
    return info


def distance(left: tuple[int, ...], right: tuple[int, ...]) -> tuple[float, int, int]:
    best = (1.0, 0, 0)
    # Chromaprint emits roughly eight words per second. Allow a two-second
    # leading offset and sample every other word for a fast robust comparison.
    for shift in range(-16, 17):
        left_start = max(0, shift)
        right_start = max(0, -shift)
        overlap = min(len(left) - left_start, len(right) - right_start)
        if overlap < 200:
            continue
        limit = min(overlap, 1450)
        total = 0
        count = 0
        for index in range(0, limit, 2):
            total += (left[left_start + index] ^ right[right_start + index]).bit_count()
            count += 1
        score = total / (32 * count)
        if score < best[0]:
            best = (score, shift, overlap)
    return best


def title_value(item: dict) -> str:
    return item["title"] or Path(item["filename"]).stem


def public_item(item: dict) -> dict:
    return {key: value for key, value in item.items() if key != "fingerprint"}


def main() -> int:
    options = args()
    music_dir = Path(options.music_dir)
    paths = sorted(
        path
        for path in music_dir.iterdir()
        if path.is_file() and path.suffix.casefold() in AUDIO_SUFFIXES
    )
    print(json.dumps({"event": "start", "files": len(paths)}, ensure_ascii=False), flush=True)

    tracks = []
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=options.workers) as executor:
        future_paths = {executor.submit(inspect, path, options.seconds): path for path in paths}
        for completed, future in enumerate(concurrent.futures.as_completed(future_paths), 1):
            path = future_paths[future]
            try:
                tracks.append(future.result())
            except Exception as exc:
                errors.append({"path": str(path), "error": repr(exc)})
            if completed % 25 == 0 or completed == len(paths):
                print(
                    json.dumps(
                        {"event": "progress", "completed": completed, "errors": len(errors)},
                        ensure_ascii=False,
                    ),
                    flush=True,
                )

    tracks.sort(key=lambda item: (item["duration"], item["filename"]))
    pairs = []
    comparisons = 0
    for index, left in enumerate(tracks):
        for right in tracks[index + 1 :]:
            duration_delta = right["duration"] - left["duration"]
            if duration_delta > options.duration_window:
                break
            comparisons += 1
            score, shift, overlap = distance(left["fingerprint"], right["fingerprint"])
            left_title = normalize(title_value(left))
            right_title = normalize(title_value(right))
            title_similarity = SequenceMatcher(None, left_title, right_title).ratio()
            artist_match = bool(
                normalize(left["artist"])
                and normalize(left["artist"]) == normalize(right["artist"])
            )
            if score <= options.candidate_threshold:
                confidence = (
                    "exact_recording"
                    if score <= 0.12
                    else "strong_candidate"
                    if score <= 0.17
                    else "review"
                )
                pairs.append(
                    {
                        "confidence": confidence,
                        "fingerprint_distance": round(score, 6),
                        "fingerprint_similarity": round(1 - score, 6),
                        "shift_words": shift,
                        "overlap_words": overlap,
                        "duration_delta": round(abs(duration_delta), 3),
                        "title_similarity": round(title_similarity, 4),
                        "artist_match": artist_match,
                        "left": public_item(left),
                        "right": public_item(right),
                    }
                )

    pairs.sort(key=lambda item: (item["fingerprint_distance"], item["duration_delta"]))
    report = {
        "music_dir": str(music_dir),
        "files_scanned": len(paths),
        "files_fingerprinted": len(tracks),
        "errors": errors,
        "comparisons": comparisons,
        "settings": {
            "fingerprint_seconds": options.seconds,
            "duration_window": options.duration_window,
            "candidate_threshold": options.candidate_threshold,
        },
        "candidate_pairs": pairs,
    }
    Path(options.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(
        json.dumps(
            {"event": "finish", "pairs": len(pairs), "errors": len(errors), "output": options.output},
            ensure_ascii=False,
        ),
        flush=True,
    )
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
