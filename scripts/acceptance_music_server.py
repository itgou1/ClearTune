"""Local synthetic OpenSubsonic fixture for manual APK upgrade acceptance. No real music/accounts."""
import argparse
import io
import json
import pathlib
import struct
import threading
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

parser = argparse.ArgumentParser()
parser.add_argument("--port", type=int, default=18764)
parser.add_argument("--output", type=pathlib.Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
online_file = args.output / "server-online"
online_file.write_text("1")
pcm = io.BytesIO()
with wave.open(pcm, "wb") as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(16000)
    wav.writeframes(b"\0" * (16000 * 2 * 120))
audio = pcm.getvalue()
songs = [dict(id=f"upgrade-{i}", title=f"Upgrade Track {i}", artist="Acceptance Artist",
              artistId="upgrade-artist", album="Acceptance Album", albumId="upgrade-album",
              duration=120, suffix="wav", contentType="audio/wav", size=len(audio), track=i + 1,
              created="2026-09-16T00:00:00Z") for i in range(3)]
album = dict(id="upgrade-album", name="Acceptance Album", artist="Acceptance Artist",
             artistId="upgrade-artist", songCount=3, duration=360, created="2026-09-16T00:00:00Z")
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        operation = parsed.path.rsplit("/", 1)[-1].replace(".view", "")
        with lock:
            with (args.output / "server-requests.jsonl").open("a") as log:
                log.write(json.dumps(dict(operation=operation, id=query.get("id"),
                                          maxBitRate=query.get("maxBitRate"), range=self.headers.get("Range"))) + "\n")
        if online_file.read_text().strip() != "1":
            self.close_connection = True
            return
        if operation in ("stream", "download"):
            start = int(self.headers.get("Range", "bytes=0-").split("=")[-1].split("-")[0])
            if start >= len(audio):
                self.send_error(416)
                return
            self.send_response(206 if start else 200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(audio) - start))
            if start:
                self.send_header("Content-Range", f"bytes {start}-{len(audio) - 1}/{len(audio)}")
            self.end_headers()
            try:
                self.wfile.write(audio[start:])
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        result = dict(status="ok", version="1.16.1", type="acceptance-fixture", serverVersion="test",
                      openSubsonic=True, openSubsonicExtensions=[],
                      albumList2=dict(album=[album] if int(query.get("offset", [0])[0]) == 0 else []),
                      artists=dict(index=[dict(name="A", artist=[dict(id="upgrade-artist", name="Acceptance Artist", albumCount=1)])]),
                      artist=dict(id="upgrade-artist", name="Acceptance Artist", album=[album]),
                      album=dict(**album, song=songs), song=songs[0],
                      searchResult3=dict(song=songs if int(query.get("songOffset", [0])[0]) == 0 else []),
                      playlists=dict(playlist=[]), starred2=dict(song=[]), genres=dict(genre=[]),
                      musicFolders=dict(musicFolder=[]), playQueue=dict(entry=[]))
        body = json.dumps({"subsonic-response": result}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
