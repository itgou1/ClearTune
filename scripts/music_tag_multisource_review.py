#!/usr/bin/env python3
"""Collect Music Tag Web candidates without changing any music files."""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8002")
    parser.add_argument("--token-file", default="/tmp/mt_token.json")
    parser.add_argument("--music-dir", default="/app/media/我的音乐集")
    parser.add_argument("--sources", nargs="+", default=["netease", "migu", "kugou"])
    parser.add_argument("--output", required=True)
    parser.add_argument("--delay", type=float, default=0.5)
    return parser.parse_args()


class Collector:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        token = json.loads(Path(args.token_file).read_text(encoding="utf-8"))["token"]
        self.session = requests.Session()
        self.session.headers.update(
            {"AUTHORIZATION": f"jwt {token}", "Content-Type": "application/json"}
        )

    def post(self, path: str, payload: dict, timeout: int = 120) -> dict:
        response = self.session.post(
            f"{self.args.base_url}{path}", json=payload, timeout=timeout
        )
        if not response.ok:
            raise RuntimeError(f"HTTP {response.status_code}: {response.text[:300]}")
        data = response.json()
        if not data.get("result"):
            raise RuntimeError(f"API error: {data.get('code')} {data.get('message')}")
        return data

    def tracks(self) -> list[dict]:
        music_path = Path(self.args.music_dir)
        data = self.post(
            "/apimt/file_id3_list/",
            {
                "file_full_path": str(music_path.parent),
                "select_data": [
                    {"icon": "icon-folder", "name": music_path.name, "title": music_path.name}
                ],
                "mode": "all",
                "limit": 5000,
                "depth": 1,
                "sorted_fields": [],
                "search_word": "",
                "refresh": True,
                "page": 1,
                "page_size": 5000,
            },
            timeout=300,
        )["data"]
        return data.get("list", [])

    def full_track(self, filename: str) -> dict:
        return self.post(
            "/apimt/music_id3/",
            {"file_path": self.args.music_dir, "file_name": filename},
        )["data"]

    def candidates(self, source: str, track: dict) -> list[dict]:
        data = self.post(
            "/apimt/fetch_id3_by_title/",
            {
                "resource": source,
                "full_path": f"{self.args.music_dir}/{track['filename']}",
                "title": track.get("title") or "",
                "artist": track.get("artist") or "",
                "album": track.get("album") or "",
            },
        )["data"]
        return data if isinstance(data, list) else []

    @staticmethod
    def compact_candidate(candidate: dict) -> dict:
        keep = {
            "id",
            "name",
            "title",
            "artist",
            "album",
            "album_name",
            "album_img",
            "artwork",
            "duration",
            "DURATION",
            "is_lyric",
            "year",
        }
        result = {key: value for key, value in candidate.items() if key in keep}
        result["raw_keys"] = sorted(candidate.keys())
        return result

    def run(self) -> None:
        summaries = [
            item for item in self.tracks() if not item.get("artwork") or not item.get("lyrics")
        ]
        output = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "sources": self.args.sources,
            "music_dir": self.args.music_dir,
            "count": len(summaries),
            "tracks": [],
        }
        print(json.dumps({"event": "start", "count": len(summaries)}, ensure_ascii=False))
        for index, summary in enumerate(summaries, 1):
            track = self.full_track(summary["filename"])
            row = {
                "filename": track.get("filename"),
                "title": track.get("title"),
                "artist": track.get("artist"),
                "album": track.get("album"),
                "duration": track.get("duration"),
                "needs_cover": not bool(track.get("artwork")),
                "needs_lyrics": not bool(track.get("lyrics")),
                "sources": {},
            }
            for source in self.args.sources:
                try:
                    found = self.candidates(source, track)
                    row["sources"][source] = {
                        "candidates": [self.compact_candidate(item) for item in found[:10]]
                    }
                except Exception as exc:
                    row["sources"][source] = {"error": repr(exc), "candidates": []}
                time.sleep(self.args.delay)
            output["tracks"].append(row)
            print(
                json.dumps(
                    {
                        "event": "track",
                        "index": index,
                        "filename": row["filename"],
                        "candidate_counts": {
                            source: len(data["candidates"])
                            for source, data in row["sources"].items()
                        },
                    },
                    ensure_ascii=False,
                ),
                flush=True,
            )
        Path(self.args.output).write_text(
            json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(json.dumps({"event": "finish", "output": self.args.output}, ensure_ascii=False))


def main() -> None:
    Collector(parse_args()).run()


if __name__ == "__main__":
    main()
