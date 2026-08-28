#!/usr/bin/env python3
"""Resumable small-batch scraper for Music Tag Web V1.

Uses the application's own HTTP API, prefers Kuwo, falls back to NetEase,
and only requests fields that are currently missing from each file.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8002")
    parser.add_argument("--token-file", default="/tmp/mt_token.json")
    parser.add_argument("--db", default="/opt/music-tag/data/music_tag.db")
    parser.add_argument("--music-dir", default="/app/media/我的音乐集")
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--cooldown", type=int, default=12)
    parser.add_argument("--passes", type=int, default=2)
    parser.add_argument(
        "--log",
        default="/opt/music-tag/backups/kuwo_batch_20260827.jsonl",
    )
    return parser.parse_args()


class Runner:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        token_data = json.loads(Path(args.token_file).read_text(encoding="utf-8"))
        token = token_data["token"]
        self.session = requests.Session()
        self.session.headers.update(
            {
                "AUTHORIZATION": f"jwt {token}",
                "Content-Type": "application/json",
            }
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
        response.raise_for_status()
        data = response.json()
        if not data.get("result"):
            raise RuntimeError(
                f"API {path} failed: {data.get('code')} {data.get('message')}"
            )
        return data

    def file_entries(self) -> dict[str, dict]:
        data = self.post(
            "/apimt/file_list/",
            {
                "file_path": self.args.music_dir,
                "sorted_fields": [],
                "search_word": "",
                "refresh": False,
            },
        )["data"]
        roots = data.get("file_list_data", [])
        children = roots[0].get("children", []) if roots else []
        return {entry["name"]: entry for entry in children if entry.get("name")}

    def id3_tracks(self) -> list[dict]:
        music_path = Path(self.args.music_dir)
        data = self.post(
            "/apimt/file_id3_list/",
            {
                "file_full_path": str(music_path.parent),
                "select_data": [
                    {
                        "icon": "icon-folder",
                        "name": music_path.name,
                        "title": music_path.name,
                    }
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
        return data.get("list", data if isinstance(data, list) else [])

    def max_audit_id(self) -> int:
        with sqlite3.connect(self.args.db, timeout=30) as connection:
            row = connection.execute("select coalesce(max(id), 0) from task_auditlog").fetchone()
        return int(row[0])

    def audit_after(self, after_id: int) -> dict | None:
        with sqlite3.connect(self.args.db, timeout=30) as connection:
            connection.row_factory = sqlite3.Row
            row = connection.execute(
                """
                select id, action, status, extra, created_at, complete_at
                from task_auditlog
                where id > ? and action = '自动刮削'
                order by id asc
                limit 1
                """,
                (after_id,),
            ).fetchone()
        return dict(row) if row else None

    @staticmethod
    def missing_groups(tracks: list[dict]) -> dict[tuple[str, ...], list[str]]:
        groups: dict[tuple[str, ...], list[str]] = {}
        for track in tracks:
            missing_cover = not bool(track.get("artwork"))
            missing_lyrics = not bool(track.get("lyrics"))
            if not missing_cover and not missing_lyrics:
                continue
            fields: list[str] = []
            if missing_cover:
                fields.append("album_img")
            if missing_lyrics:
                fields.extend(["lyrics", "is_save_lyrics_file"])
            groups.setdefault(tuple(fields), []).append(track["filename"])
        return groups

    def submit_batch(
        self, entries: dict[str, dict], filenames: list[str], fields: tuple[str, ...]
    ) -> dict:
        selected = [dict(entries[name], checked=True) for name in filenames]
        before_id = self.max_audit_id()
        self.post(
            "/apimt/batch_auto_update_id3/",
            {
                "file_full_path": self.args.music_dir,
                "select_data": selected,
                "music_info": {
                    "select_mode": "simple",
                    "source_list": ["kuwo", "netease"],
                    "is_skip_tag": False,
                    "is_folder_album": False,
                    "scrape_rate": 1,
                },
                "modify_list": list(fields),
            },
        )
        # V1 can mark an audit complete before all SQLite writes have released.
        # A quiet period is required or the next batch can fail with DB locked.
        time.sleep(self.args.cooldown)
        return self.audit_after(before_id) or {}

    def run_groups(
        self,
        pass_number: int,
        entries: dict[str, dict],
        groups: dict[tuple[str, ...], list[str]],
        batch_size: int,
    ) -> None:
        batch_number = 0
        for fields, names in groups.items():
            usable = [name for name in names if name in entries]
            missing_entries = sorted(set(names) - set(usable))
            if missing_entries:
                self.log(
                    "missing_file_entries",
                    pass_number=pass_number,
                    filenames=missing_entries,
                )
            for offset in range(0, len(usable), batch_size):
                batch_number += 1
                batch = usable[offset : offset + batch_size]
                try:
                    audit = self.submit_batch(entries, batch, fields)
                    self.log(
                        "batch_complete",
                        pass_number=pass_number,
                        batch=batch_number,
                        fields=list(fields),
                        filenames=batch,
                        audit=audit,
                    )
                except Exception as exc:
                    self.log(
                        "batch_failed",
                        pass_number=pass_number,
                        batch=batch_number,
                        fields=list(fields),
                        filenames=batch,
                        error=repr(exc),
                    )
                    # Let any partially completed V1 work release SQLite before
                    # moving on. A later pass will rescan and retry only misses.
                    time.sleep(max(30, self.args.cooldown))

    def run(self) -> int:
        entries = self.file_entries()
        before_tracks = self.id3_tracks()
        before_groups = self.missing_groups(before_tracks)
        self.log(
            "start",
            readable_tracks=len(before_tracks),
            selected_tracks=sum(len(names) for names in before_groups.values()),
            no_cover=sum(not bool(track.get("artwork")) for track in before_tracks),
            no_lyrics=sum(not bool(track.get("lyrics")) for track in before_tracks),
            sources=["kuwo", "netease"],
            batch_size=self.args.batch_size,
        )

        groups = before_groups
        for pass_number in range(1, self.args.passes + 1):
            pass_batch_size = (
                self.args.batch_size
                if pass_number == 1
                else max(3, self.args.batch_size // 2)
            )
            self.log(
                "pass_start",
                pass_number=pass_number,
                selected_tracks=sum(len(names) for names in groups.values()),
                batch_size=pass_batch_size,
            )
            self.run_groups(pass_number, entries, groups, pass_batch_size)
            if pass_number < self.args.passes:
                tracks = self.id3_tracks()
                groups = self.missing_groups(tracks)
                self.log(
                    "pass_finish",
                    pass_number=pass_number,
                    remaining_tracks=sum(len(names) for names in groups.values()),
                )
                if not groups:
                    break

        after_tracks = self.id3_tracks()
        remaining = self.missing_groups(after_tracks)
        remaining_names = sorted(
            {name for names in remaining.values() for name in names}
        )
        summary = {
            "readable_tracks": len(after_tracks),
            "remaining_any": len(remaining_names),
            "no_cover": sum(not bool(track.get("artwork")) for track in after_tracks),
            "no_lyrics": sum(not bool(track.get("lyrics")) for track in after_tracks),
            "remaining_filenames": remaining_names,
        }
        Path(self.log_path.with_suffix(".summary.json")).write_text(
            json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        self.log("finish", **summary)
        return 0


def main() -> int:
    args = parse_args()
    try:
        return Runner(args).run()
    except Exception as exc:
        print(json.dumps({"event": "fatal", "error": repr(exc)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
