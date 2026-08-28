#!/usr/bin/env python3
"""Probe or apply manually reviewed Music Tag Web cover/lyric decisions."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests


NULL_SENTINEL = "${null}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8002")
    parser.add_argument("--token-file", default="/tmp/mt_token.json")
    parser.add_argument("--decisions", required=True)
    parser.add_argument("--log", required=True)
    parser.add_argument("--backup-dir", required=True)
    parser.add_argument("--apply", action="store_true")
    return parser.parse_args()


class Runner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.config = json.loads(Path(args.decisions).read_text(encoding="utf-8"))
        self.music_dir = Path(self.config["music_dir"])
        token = json.loads(Path(args.token_file).read_text(encoding="utf-8"))["token"]
        self.session = requests.Session()
        self.session.headers.update(
            {"AUTHORIZATION": f"jwt {token}", "Content-Type": "application/json"}
        )
        self.log_path = Path(args.log)
        self.log_path.parent.mkdir(parents=True, exist_ok=True)

    def log(self, event: str, **data: object) -> None:
        record = {
            "time": datetime.now(timezone.utc).isoformat(),
            "event": event,
            **data,
        }
        line = json.dumps(record, ensure_ascii=False)
        with self.log_path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")
        print(line, flush=True)

    def post(self, path: str, payload: dict, timeout: int = 180) -> dict:
        response = self.session.post(
            f"{self.args.base_url}{path}", json=payload, timeout=timeout
        )
        if not response.ok:
            raise RuntimeError(f"HTTP {response.status_code} {path}: {response.text[:500]}")
        data = response.json()
        if not data.get("result"):
            raise RuntimeError(f"API {path}: {data.get('code')} {data.get('message')}")
        return data

    def full_track(self, filename: str) -> dict:
        return self.post(
            "/apimt/music_id3/",
            {"file_path": str(self.music_dir), "file_name": filename},
        )["data"]

    def lyric(self, choice: dict) -> str:
        value = self.post(
            "/apimt/fetch_lyric/",
            {"resource": choice["source"], "song_id": choice["id"]},
        )["data"]
        if not isinstance(value, str) or value.startswith("未找到歌词"):
            return ""
        return value.strip()

    def probe_image(self, url: str) -> dict:
        response = requests.get(url, timeout=30, headers={"User-Agent": "Mozilla/5.0"})
        return {
            "ok": response.ok,
            "status": response.status_code,
            "bytes": len(response.content),
            "content_type": response.headers.get("content-type", ""),
        }

    def update(self, track: dict, lyrics: str, cover_url: str) -> None:
        payload_track = json.loads(json.dumps(track))
        payload_track["file_full_path"] = track.get("path") or str(
            self.music_dir / track["filename"]
        )
        if lyrics:
            payload_track["lyrics"] = lyrics
            payload_track["is_save_lyrics_file"] = True
        if cover_url:
            payload_track["album_img"] = cover_url
            payload_track["artwork"] = cover_url
        for key, value in list(payload_track.items()):
            if value is None or value == "" or value == []:
                payload_track[key] = NULL_SENTINEL
        self.post("/apimt/update_id3/", {"music_id3_info": [payload_track]}, timeout=240)

    def run(self) -> int:
        self.log("start", mode="apply" if self.args.apply else "probe")
        for item in self.config["decisions"]:
            filename = item["filename"]
            try:
                before = self.full_track(filename)
                lyrics = self.lyric(item["lyrics"]) if item.get("lyrics") else ""
                cover_url = item.get("cover", {}).get("url", "")
                image_probe = self.probe_image(cover_url) if cover_url else None
                self.log(
                    "probed",
                    filename=filename,
                    reason=item["reason"],
                    lyric_source=item.get("lyrics", {}).get("source"),
                    lyric_id=item.get("lyrics", {}).get("id"),
                    lyric_length=len(lyrics),
                    lyric_preview=lyrics[:240].replace("\n", " | "),
                    cover_source=item.get("cover", {}).get("source"),
                    cover_id=item.get("cover", {}).get("id"),
                    image_probe=image_probe,
                )
                if not self.args.apply:
                    continue
                if item.get("lyrics") and not lyrics:
                    if not item["lyrics"].get("optional_instrumental"):
                        raise RuntimeError("selected lyric source returned no lyrics")
                if cover_url and not (image_probe and image_probe["ok"] and image_probe["bytes"] > 1000):
                    raise RuntimeError(f"selected cover failed probe: {image_probe}")

                backup_path = Path(self.args.backup_dir) / filename
                backup_path.parent.mkdir(parents=True, exist_ok=True)
                if not backup_path.exists():
                    shutil.copy2(self.music_dir / filename, backup_path)

                self.update(before, lyrics, cover_url)
                after = self.full_track(filename)
                lyric_ok = bool(after.get("lyrics")) if lyrics else not bool(item.get("lyrics"))
                cover_ok = bool(after.get("artwork")) if cover_url else not bool(item.get("cover"))
                self.log(
                    "updated",
                    filename=filename,
                    lyric_requested=bool(lyrics),
                    lyric_verified=lyric_ok,
                    cover_requested=bool(cover_url),
                    cover_verified=cover_ok,
                    backup=str(backup_path),
                )
            except Exception as exc:
                self.log("failed", filename=filename, error=repr(exc))
        self.log("finish")
        return 0


def main() -> int:
    return Runner(parse_args()).run()


if __name__ == "__main__":
    sys.exit(main())
