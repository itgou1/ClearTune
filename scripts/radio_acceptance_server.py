"""Local-only OpenSubsonic/live-audio fixture. Never uses real accounts or radio URLs."""
import io
import json
import math
import pathlib
import struct
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / 'outputs/radio-acceptance'
OUT.mkdir(parents=True, exist_ok=True)
LOCK = threading.Lock()
ACTIVE = set()
EVENTS = []
RATE = 16000
SAMPLE = b''.join(struct.pack('<h', int(500 * math.sin(i * 2 * math.pi * 220 / RATE))) for i in range(RATE))
buffer = io.BytesIO()
with wave.open(buffer, 'wb') as wav:
    wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(RATE)
    wav.writeframes(SAMPLE * 120)
AUDIO = buffer.getvalue()
NAMES = ['音乐之声', '央广经典音乐广播', '中国之声']
SONGS = [dict(id=f'song-{i}', title=['夜光下', '成都', '此刻的旋律'][i], artist='测试歌手',
    albumId=f'album-{i}', album=['夜光下', '成都', '此刻的旋律'][i], artistId='artist', duration=120,
    coverArt=f'cover-{i}', suffix='wav', contentType='audio/wav', playCount=10, created='2026-10-12T00:00:00Z') for i in range(3)]
ALBUMS = [dict(id=f'album-{i}', name=s['album'], artist=s['artist'], artistId='artist', coverArt=f'cover-{i}',
    songCount=1, duration=120, created='2026-10-12T00:00:00Z') for i, s in enumerate(SONGS)]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        parsed=urlparse(self.path); query=parse_qs(parsed.query)
        op=parsed.path.rsplit('/',1)[-1].replace('.view','')
        if parsed.path == '/status':
            with LOCK: payload={'active':sorted(ACTIVE), 'events':list(EVENTS)}
            return self.send_json(payload)
        if parsed.path.startswith('/live/'):
            station=op
            with LOCK: ACTIVE.add(station); EVENTS.append(['open',station,time.time()])
            self.send_response(200);self.send_header('Content-Type','audio/wav');self.end_headers()
            # Huge declared WAV size + paced PCM acts as an endless live source.
            header=bytearray(AUDIO[:44]);struct.pack_into('<I',header,4,0x7ffff024);struct.pack_into('<I',header,40,0x7ffff000)
            try:
                self.wfile.write(header)
                while True:
                    self.wfile.write(SAMPLE);self.wfile.flush();time.sleep(1)
            except (BrokenPipeError,ConnectionResetError,ConnectionAbortedError): pass
            finally:
                with LOCK: ACTIVE.discard(station);EVENTS.append(['close',station,time.time()])
            return
        with LOCK: EVENTS.append([op,query.get('id',[]),time.time()])
        if op=='getCoverArt':
            data=(ROOT/'app/src/main/res/drawable-nodpi/radio_cover_window.webp').read_bytes()
            self.send_response(200);self.send_header('Content-Type','image/webp');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if op=='stream':
            start=int(self.headers.get('Range','bytes=0-').split('=')[-1].split('-')[0])
            data=AUDIO[start:];self.send_response(206 if start else 200);self.send_header('Content-Type','audio/wav')
            self.send_header('Content-Length',str(len(data)))
            if start:self.send_header('Content-Range',f'bytes {start}-{len(AUDIO)-1}/{len(AUDIO)}')
            self.end_headers()
            try:self.wfile.write(data)
            except (BrokenPipeError,ConnectionResetError):pass
            return
        album_id=query.get('id',['album-0'])[0]
        idx=int(album_id[-1]) if album_id[-1:].isdigit() else 0
        result=dict(status='ok',version='1.16.1',type='radio-test-fixture',serverVersion='test',openSubsonic=True,
            openSubsonicExtensions=[],internetRadioStations=dict(internetRadioStation=[dict(id=str(i),name=n,streamUrl=f'http://127.0.0.1:18765/live/{i}') for i,n in enumerate(NAMES)]),
            albumList2=dict(album=ALBUMS if int(query.get('offset',[0])[0])==0 else []),
            artists=dict(index=[dict(name='测试',artist=[dict(id='artist',name='测试歌手',albumCount=3)])]),
            artist=dict(id='artist',name='测试歌手',album=ALBUMS),album=dict(**ALBUMS[idx%3],song=[SONGS[idx%3]]),song=SONGS[idx%3],
            searchResult3=dict(song=SONGS if int(query.get('songOffset',[0])[0])==0 else []),
            playlists=dict(playlist=[]),starred2=dict(song=[]),genres=dict(genre=[]),musicFolders=dict(musicFolder=[]),playQueue=dict(entry=[]))
        self.send_json({'subsonic-response':result})
    def send_json(self,payload):
        data=json.dumps(payload,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)

ThreadingHTTPServer(('127.0.0.1',18765),Handler).serve_forever()
