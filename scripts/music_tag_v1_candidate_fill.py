#!/usr/bin/env python3
"""Safely promote high-confidence V1 scrape candidates into missing tags.

The Music Tag Web V1 batch scraper leaves many usable results in "candidate"
state.  This helper uses the application's own search, lyric and update APIs,
but only writes a cover or lyrics when title/artist matching is conservative.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path

import requests


NULL_SENTINEL = "${null}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8002")
    parser.add_argument("--token-file", default="/tmp/mt_token.json")
    parser.add_argument("--music-dir", default="/app/media/我的音乐集")
    parser.add_argument("--sources", nargs="+", default=["kuwo", "netease"])
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--max-updates", type=int, default=0)
    parser.add_argument("--delay", type=float, default=0.8)
    parser.add_argument(
        "--log",
        default="/opt/music-tag/backups/kuwo_candidate_fill_20260827.jsonl",
    )
    return parser.parse_args()


def normalized(value: object) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    text = re.sub(r"\([^)]*(?:live|cover|伴奏|现场|版)[^)]*\)", "", text)
    text = re.sub(r"\[[^]]*(?:live|cover|伴奏|现场|版)[^]]*\]", "", text)
    return "".join(char for char in text if char.isalnum())


def artist_parts(value: object) -> set[str]:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    parts = re.split(r"\s*(?:,|，|、|/|&|＆|;|；|\bfeat\.?\b|\bft\.?\b)\s*", text)
    return {normalized(part) for part in parts if normalized(part)}


def candidate_score(track: dict, candidate: dict) -> tuple[int, dict]:
    title = normalized(track.get("title"))
    candidate_title = normalized(candidate.get("name") or candidate.get("title"))
    exact_title = bool(title and title == candidate_title)

    track_artists = artist_parts(track.get("artist"))
    candidate_artists = artist_parts(candidate.get("artist"))
    artist_match = bool(track_artists & candidate_artists)
    if not artist_match and track_artists and candidate_artists:
        artist_match = any(
            left in right or right in left
            for left in track_artists
            for right in candidate_artists
            if min(len(left), len(right)) >= 3
        )

    album = normalized(track.get("album"))
    candidate_album = normalized(candidate.get("album"))
    album_match = bool(album and candidate_album and album == candidate_album)

    score = (100 if exact_title else 0) + (55 if artist_match else 0)
    score += 20 if album_match else 0

    candidate_duration = candidate.get("DURATION") or candidate.get("duration")
    try:
        duration_delta = abs(float(track.get("duration") or 0) - float(candidate_duration))
        has_duration = True
    except (TypeError, ValueError):
        duration_delta = 999.0
        has_duration = False
    if has_duration and duration_delta <= 8:
        score += 15
    elif has_duration and duration_delta <= 15:
        score += 8

    # Require an exact normalized title plus matching artist.  Album may stand
    # in for artist only when the local artist is empty.
    identity_match = artist_match or (not track_artists and album_match)
    version_match = not has_duration or duration_delta <= 15 or album_match
    accepted = exact_title and identity_match and version_match
    evidence = {
        "score": score,
        "exact_title": exact_title,
        "artist_match": artist_match,
        "album_match": album_match,
        "duration_delta": round(duration_delta, 2) if has_duration else None,
    }
    return (score if accepted else -1), evidence


class Runner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
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

    def post(self, path: str, payload: dict, timeout: int = 90) -> dict:
        response = self.session.post(
            f"{self.args.base_url}{path}", json=payload, timeout=timeout
        )
        if not response.ok:
            raise RuntimeError(
                f"HTTP {response.status_code} {path}: {response.text[:500]}"
            )
        data = response.json()
        if not data.get("result"):
            raise RuntimeError(f"API {path}: {data.get('code')} {data.get('message')}")
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
                "limit": 1000,
                "depth": 1,
                "sorted_fields": [],
                "search_word": "",
                "refresh": True,
                "page": 1,
                "page_size": 1000,
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

    def lyric(self, source: str, song_id: object) -> str:
        value = self.post(
            "/apimt/fetch_lyric/", {"resource": source, "song_id": song_id}
        )["data"]
        if not isinstance(value, str):
            return ""
        if value.startswith("未找到歌词"):
            return ""
        return value.strip()

    def update(self, track: dict, lyrics: str, cover_url: str) -> None:
        payload_track = json.loads(json.dumps(track))
        payload_track["file_full_path"] = track.get("path") or (
            f"{self.args.music_dir}/{track['filename']}"
        )
        if lyrics:
            payload_track["lyrics"] = lyrics
            payload_track["is_save_lyrics_file"] = True
        if cover_url:
            payload_track["album_img"] = cover_url
            payload_track["artwork"] = cover_url

        # Match the V1 frontend's single-file save behavior.
        for key, value in list(payload_track.items()):
            if value is None or value == "" or value == []:
                payload_track[key] = NULL_SENTINEL
        self.post("/apimt/update_id3/", {"music_id3_info": [payload_track]}, timeout=180)

    def run(self) -> int:
        tracks = [
            track
            for track in self.tracks()
            if not bool(track.get("artwork")) or not bool(track.get("lyrics"))
        ]
        if self.args.limit:
            tracks = tracks[: self.args.limit]
        self.log("start", selected_tracks=len(tracks), sources=self.args.sources)

        updates = 0
        lyrics_written = 0
        covers_written = 0
        for index, summary in enumerate(tracks, 1):
            filename = summary["filename"]
            try:
                track = self.full_track(filename)
                need_lyrics = not bool(track.get("lyrics"))
                need_cover = not bool(track.get("artwork"))
                found_lyrics = ""
                found_cover = ""
                lyric_selection = None
                cover_selection = None
                matched = []

                for source in self.args.sources:
                    source_candidates = self.candidates(source, track)
                    ranked = []
                    for candidate in source_candidates:
                        score, evidence = candidate_score(track, candidate)
                        if score >= 0:
                            ranked.append((score, candidate, evidence))
                    ranked.sort(key=lambda item: item[0], reverse=True)
                    for score, candidate, evidence in ranked[:3]:
                        matched.append(
                            {
                                "source": source,
                                "id": candidate.get("id"),
                                "name": candidate.get("name"),
                                "artist": candidate.get("artist"),
                                **evidence,
                            }
                        )
                        if need_cover and not found_cover:
                            found_cover = str(candidate.get("album_img") or "")
                            if found_cover:
                                cover_selection = {
                                    "source": source,
                                    "id": candidate.get("id"),
                                    **evidence,
                                }
                        if need_lyrics and not found_lyrics and candidate.get("is_lyric", True):
                            found_lyrics = self.lyric(source, candidate.get("id"))
                            if found_lyrics:
                                lyric_selection = {
                                    "source": source,
                                    "id": candidate.get("id"),
                                    **evidence,
                                }
                        if (not need_cover or found_cover) and (not need_lyrics or found_lyrics):
                            break
                    if (not need_cover or found_cover) and (not need_lyrics or found_lyrics):
                        break
                    time.sleep(self.args.delay)

                if found_lyrics or found_cover:
                    try:
                        self.update(track, found_lyrics, found_cover)
                    except RuntimeError as exc:
                        # Kuwo occasionally returns a stale album-image URL.
                        # Preserve a usable lyric by retrying without the cover.
                        if found_cover and found_lyrics and "下载专辑封面失败" in str(exc):
                            self.log(
                                "cover_rejected",
                                index=index,
                                filename=filename,
                                cover_url=found_cover,
                                error=str(exc),
                            )
                            found_cover = ""
                            cover_selection = None
                            self.update(track, found_lyrics, "")
                        else:
                            raise
                    verify = self.full_track(filename)
                    lyric_ok = bool(verify.get("lyrics")) if found_lyrics else False
                    cover_ok = bool(verify.get("artwork")) if found_cover else False
                    updates += 1
                    lyrics_written += int(lyric_ok)
                    covers_written += int(cover_ok)
                    self.log(
                        "updated",
                        index=index,
                        filename=filename,
                        lyric_written=lyric_ok,
                        cover_written=cover_ok,
                        lyric_selection=lyric_selection,
                        cover_selection=cover_selection,
                        matched=matched,
                    )
                else:
                    self.log("no_safe_result", index=index, filename=filename, matched=matched)
            except Exception as exc:
                self.log("track_failed", index=index, filename=filename, error=repr(exc))

            if self.args.max_updates and updates >= self.args.max_updates:
                break
            time.sleep(self.args.delay)

        remaining = [
            track
            for track in self.tracks()
            if not bool(track.get("artwork")) or not bool(track.get("lyrics"))
        ]
        summary = {
            "inspected": min(len(tracks), index if tracks else 0),
            "updates": updates,
            "lyrics_written": lyrics_written,
            "covers_written": covers_written,
            "remaining_any": len(remaining),
            "remaining_no_cover": sum(not bool(track.get("artwork")) for track in remaining),
            "remaining_no_lyrics": sum(not bool(track.get("lyrics")) for track in remaining),
        }
        self.log("finish", **summary)
        self.log_path.with_suffix(".summary.json").write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return 0


def main() -> int:
    try:
        return Runner(parse_args()).run()
    except Exception as exc:
        print(json.dumps({"event": "fatal", "error": repr(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
