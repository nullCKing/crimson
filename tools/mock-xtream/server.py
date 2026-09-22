#!/usr/bin/env python3
"""
A mock Xtream Codes server, for testing the app without depending on a real provider.

Serves the endpoints the app uses:

    GET /player_api.php?username=U&password=P                         login
    GET /player_api.php?...&action=get_live_categories
    GET /player_api.php?...&action=get_live_streams[&category_id=ID]
    GET /player_api.php?...&action=get_short_epg&stream_id=ID[&limit=N]
    GET /xmltv.php?username=U&password=P                              XMLTV, gzip on request
    GET /live/U/P/STREAM_ID.ts                                        endless MPEG-TS
    GET /live/U/P/STREAM_ID.m3u8                                      sliding-window HLS
    GET /_truth                                                       ground truth, for tests only

Standard library only, so it runs anywhere Python 3.8 does — including the Windows machine that
runs the emulator.

Test media is generated locally with FFmpeg at first use: a colour-bar pattern with a burned-in
channel number, a timecode and a tone. Nothing is downloaded and nothing is copied from anywhere,
so there is no licensing question about the media this server sends. If FFmpeg is missing, the
media endpoints return 503 and the API still works; `--public-streams` instead redirects playback
to well-known public test streams (see README).

Usage:
    python3 server.py --port 8080
    python3 server.py --port 8080 --channels 5200 --epg-days 3
"""

import argparse
import datetime
import gzip
import io
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import epg as epg_mod  # noqa: E402
import fixtures
import vod as vod_mod  # noqa: E402

USERNAME = "testuser"
PASSWORD = "testpass"

MEDIA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")
SEGMENT_SECONDS = 4
SEGMENT_COUNT = 15  # 60 seconds of loopable media

# Legal public test streams, used only with --public-streams. All are published by their owners
# for developer testing, or are US government works in the public domain.
PUBLIC_STREAMS = [
    # NASA TV: a work of the US government, public domain, and a genuine 24/7 live channel.
    "https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8",
    "https://ntv2.akamaized.net/hls/live/2013923/NASA-NTV2-HLS/master.m3u8",
    # Blender Foundation open movies, Creative Commons Attribution, served by Mux for hls.js tests.
    "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "https://test-streams.mux.dev/tos_ismc/main.m3u8",
    # Apple's own HLS example stream, published for developers.
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
    # Unified Streaming's Tears of Steel demo, also Creative Commons.
    "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
]

_catalog = None
_vod = None
_args = None
_media_ready = threading.Event()
_media_error = None


# --------------------------------------------------------------------------- media


def have_ffmpeg():
    return shutil.which("ffmpeg") is not None


def build_media():
    """
    Generates the looping test media once. Produces:
      media/loop.ts          a 60-second MPEG-TS, concatenated endlessly by the .ts endpoint
      media/seg_NNN.ts       4-second segments, served through a sliding-window HLS playlist
    """
    global _media_error
    try:
        os.makedirs(MEDIA_DIR, exist_ok=True)
        marker = os.path.join(MEDIA_DIR, ".ready")
        if os.path.exists(marker) and os.path.exists(os.path.join(MEDIA_DIR, "film.mp4")):
            _media_ready.set()
            return
        if not have_ffmpeg():
            _media_error = "ffmpeg not found on PATH"
            return

        duration = SEGMENT_SECONDS * SEGMENT_COUNT
        loop = os.path.join(MEDIA_DIR, "loop.ts")
        # Colour bars, a burned-in running timecode, and a 1 kHz tone. 720p keeps the file small
        # while still exercising a real decode path on the emulator.
        subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "smptehdbars=size=1280x720:rate=25:duration=%d" % duration,
            "-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000:duration=%d" % duration,
            "-vf", "drawtext=text='CRIMSON LIVE TEST %{pts\\:hms}':fontcolor=white:fontsize=48:"
                   "box=1:boxcolor=black@0.6:x=(w-text_w)/2:y=h-120",
            "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
            "-g", "50", "-keyint_min", "50", "-sc_threshold", "0", "-b:v", "1500k",
            "-c:a", "aac", "-b:a", "96k", "-ar", "48000",
            "-f", "mpegts", loop,
        ], check=True)

        subprocess.run([
            "ffmpeg", "-y", "-loglevel", "error", "-i", loop,
            "-c", "copy", "-f", "segment",
            "-segment_time", str(SEGMENT_SECONDS),
            "-segment_format", "mpegts",
            os.path.join(MEDIA_DIR, "seg_%03d.ts"),
        ], check=True)

        film = os.path.join(MEDIA_DIR, "film.mp4")
        if not os.path.exists(film):
            # Three minutes, seekable (faststart), so resume and the scrubber can be exercised.
            subprocess.run([
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=180",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=180",
                "-vf", "drawtext=text='CRIMSON FILM %{pts\\:hms}':fontcolor=white:fontsize=44:"
                       "box=1:boxcolor=black@0.6:x=(w-text_w)/2:y=h-110",
                "-c:v", "libx264", "-preset", "veryfast", "-g", "50", "-b:v", "1200k",
                "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", film,
            ], check=True)

        with open(marker, "w") as fh:
            fh.write("ok\n")
        _media_ready.set()
    except Exception as exc:  # noqa: BLE001 - reported to the client, never fatal
        _media_error = str(exc)


def segment_files():
    return sorted(f for f in os.listdir(MEDIA_DIR) if f.startswith("seg_") and f.endswith(".ts"))


# --------------------------------------------------------------------------- API payloads


def server_info(host, port):
    return {
        "url": host,
        "port": str(port),
        "https_port": str(port),
        "server_protocol": "http",
        "rtmp_port": "0",
        "timezone": "UTC",
        "timestamp_now": int(time.time()),
        "time_now": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "process": True,
    }


def user_info():
    expiry = int(time.time()) + 365 * 24 * 3600
    return {
        "username": USERNAME,
        "password": PASSWORD,
        "message": "",
        "auth": 1,
        "status": "Active",
        "exp_date": str(expiry),
        "is_trial": "0",
        "active_cons": "0",
        "created_at": "1600000000",
        "max_connections": "1",
        "allowed_output_formats": ["ts", "m3u8"],
    }


# --------------------------------------------------------------------------- handler


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MockXtream/1.0"

    def log_message(self, fmt, *args):
        if _args.verbose:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # ------------------------------------------------------------------ helpers

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _text(self, text, status=200, content_type="text/plain"):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self, params):
        return (params.get("username", [None])[0] == USERNAME
                and params.get("password", [None])[0] == PASSWORD)

    # ------------------------------------------------------------------ routing

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)
        try:
            if parsed.path in ("/player_api.php", "/panel_api.php"):
                self.handle_api(params)
            elif parsed.path == "/xmltv.php":
                self.handle_xmltv(params)
            elif parsed.path.startswith("/movie/") or parsed.path.startswith("/series/"):
                self.handle_film(parsed.path)
            elif parsed.path.startswith("/live/"):
                self.handle_live(parsed.path)
            elif parsed.path == "/_truth":
                self.handle_truth()
            elif parsed.path == "/":
                self._text("mock-xtream: %d channels, %d categories\n"
                           % (len(_catalog.channels), len(_catalog.categories)))
            else:
                self._text("not found\n", 404)
        except BrokenPipeError:
            pass
        except ConnectionResetError:
            pass

    # ------------------------------------------------------------------ endpoints

    def handle_api(self, params):
        if not self._authorised(params):
            # A real panel answers a bad login with auth 0 and HTTP 200, which is exactly the
            # case the app's "bad credentials show a clear error" path has to handle.
            self._json({"user_info": {"auth": 0, "status": "Disabled",
                                      "message": "Invalid credentials"}})
            return

        action = params.get("action", [None])[0]
        host = self.headers.get("Host", "127.0.0.1").split(":")[0]

        if action is None:
            self._json({"user_info": user_info(),
                        "server_info": server_info(host, _args.port)})
            return

        if action == "get_live_categories":
            self._json([fixtures.public_category_view(c) for c in _catalog.categories])
            return

        if action == "get_live_streams":
            cid = params.get("category_id", [None])[0]
            if cid:
                channels = _catalog.by_category.get(cid, [])
            else:
                channels = _catalog.channels
            self._json([fixtures.public_channel_view(c) for c in channels])
            return

        if action in ("get_short_epg", "get_simple_data_table"):
            sid = params.get("stream_id", [None])[0]
            limit = int(params.get("limit", ["4"])[0])
            channel = _catalog.by_id.get(int(sid)) if sid and sid.isdigit() else None
            if channel is None:
                self._json({"epg_listings": []})
                return
            self._json({"epg_listings": epg_mod.short_epg_for(channel, limit)})
            return

        if action == "get_vod_categories":
            self._json(_vod.movie_categories)
            return

        if action == "get_vod_streams":
            self._json(_vod.vod_streams(params.get("category_id", [None])[0]))
            return

        if action == "get_vod_info":
            self._json(_vod.vod_info(int(params.get("vod_id", ["0"])[0] or 0)))
            return

        if action == "get_series_categories":
            self._json(_vod.series_categories)
            return

        if action == "get_series":
            self._json(_vod.series_list(params.get("category_id", [None])[0]))
            return

        if action == "get_series_info":
            self._json(_vod.series_info(int(params.get("series_id", ["0"])[0] or 0)))
            return

        self._json({"error": "unknown action"}, 400)

    def handle_xmltv(self, params):
        if not self._authorised(params):
            self._text("unauthorised\n", 401)
            return

        wants_gzip = "gzip" in (self.headers.get("Accept-Encoding") or "").lower()
        chunks = epg_mod.stream_xmltv(
            _catalog.channels,
            days_back=_args.epg_days_back,
            days_forward=_args.epg_days,
        )

        self.send_response(200)
        self.send_header("Content-Type", "application/xml")
        if wants_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def write_chunk(data):
            if not data:
                return
            self.wfile.write(b"%X\r\n" % len(data))
            self.wfile.write(data)
            self.wfile.write(b"\r\n")

        if wants_gzip:
            buf = io.BytesIO()
            gz = gzip.GzipFile(fileobj=buf, mode="wb")
            for chunk in chunks:
                gz.write(chunk)
                if buf.tell() > 32768:
                    write_chunk(buf.getvalue())
                    buf.seek(0)
                    buf.truncate()
            gz.close()
            write_chunk(buf.getvalue())
        else:
            for chunk in chunks:
                write_chunk(chunk)
        self.wfile.write(b"0\r\n\r\n")

    def handle_film(self, path):
        """A film or an episode: the same finite MP4, with byte ranges so the player can seek."""
        parts = path.strip("/").split("/")
        if len(parts) != 4 or parts[1] != USERNAME or parts[2] != PASSWORD:
            self._text("forbidden\n", 403)
            return
        film = os.path.join(MEDIA_DIR, "film.mp4")
        if not os.path.exists(film):
            self._text("test media unavailable: %s\n" % (_media_error or "still generating"), 503)
            return
        size = os.path.getsize(film)
        start, end = 0, size - 1
        header = self.headers.get("Range")
        if header and header.startswith("bytes="):
            first, _, last = header[6:].partition("-")
            start = int(first) if first else 0
            end = int(last) if last else size - 1
            end = min(end, size - 1)
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/mp4")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(film, "rb") as fh:
            fh.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = fh.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def handle_live(self, path):
        parts = path.strip("/").split("/")
        if len(parts) != 4 or parts[1] != USERNAME or parts[2] != PASSWORD:
            self._text("forbidden\n", 403)
            return
        target = parts[3]
        stream_id, _, ext = target.rpartition(".")

        if _args.public_streams:
            index = int(stream_id) % len(PUBLIC_STREAMS) if stream_id.isdigit() else 0
            self.send_response(302)
            self.send_header("Location", PUBLIC_STREAMS[index])
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if not _media_ready.is_set():
            self._text("test media unavailable: %s\n" % (_media_error or "still generating"), 503)
            return

        if ext == "m3u8":
            self.serve_hls()
        elif ext == "ts":
            self.serve_ts()
        else:
            self._text("unsupported format\n", 415)

    def serve_hls(self):
        """
        A sliding-window live playlist over the generated segments.
        The window advances with wall-clock time, so the player sees a genuine live stream rather
        than a VOD playlist that ends.
        """
        segs = segment_files()
        if not segs:
            self._text("no segments\n", 503)
            return
        window = 3
        elapsed = int(time.time()) // SEGMENT_SECONDS
        sequence = elapsed
        lines = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:%d" % SEGMENT_SECONDS,
            "#EXT-X-MEDIA-SEQUENCE:%d" % sequence,
        ]
        for i in range(window):
            name = segs[(sequence + i) % len(segs)]
            lines.append("#EXTINF:%.3f," % SEGMENT_SECONDS)
            lines.append("/media/%s" % name)
        self._text("\n".join(lines) + "\n", content_type="application/vnd.apple.mpegurl")

    def serve_ts(self):
        """
        An endless MPEG-TS, the way an Xtream `.ts` live endpoint behaves: the same file written
        to the socket over and over, paced to roughly real time so the client's buffer does not
        run away.
        """
        loop = os.path.join(MEDIA_DIR, "loop.ts")
        if not os.path.exists(loop):
            self._text("no media\n", 503)
            return
        size = os.path.getsize(loop)
        duration = SEGMENT_SECONDS * SEGMENT_COUNT
        bytes_per_second = max(1, size // duration)

        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        deadline = time.time()
        try:
            while True:
                with open(loop, "rb") as fh:
                    while True:
                        block = fh.read(bytes_per_second)
                        if not block:
                            break
                        self.wfile.write(block)
                        deadline += 1.0
                        sleep = deadline - time.time()
                        if sleep > 0:
                            time.sleep(min(sleep, 2.0))
                        else:
                            deadline = time.time()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def handle_truth(self):
        """Ground truth for the test suite. Never part of a real Xtream API."""
        self._json({
            "categories": _catalog.categories,
            "channels": _catalog.channels,
        })


class MediaHandler(Handler):
    pass


def make_handler():
    """Adds the /media/ route for HLS segments."""
    original = Handler.do_GET

    def do_GET(self):  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path.startswith("/media/"):
            name = os.path.basename(parsed.path)
            full = os.path.join(MEDIA_DIR, name)
            if not os.path.exists(full):
                self._text("not found\n", 404)
                return
            with open(full, "rb") as fh:
                body = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        original(self)

    Handler.do_GET = do_GET
    return Handler


def main():
    global _catalog, _args
    parser = argparse.ArgumentParser(description="Mock Xtream Codes server")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--channels", type=int, default=5200,
                        help="approximate channel count to generate")
    parser.add_argument("--epg-days", type=int, default=3, help="days of future guide data")
    parser.add_argument("--epg-days-back", type=int, default=1, help="days of past guide data")
    parser.add_argument("--seed", type=int, default=20260921)
    parser.add_argument("--no-media", action="store_true", help="skip FFmpeg media generation")
    parser.add_argument("--public-streams", action="store_true",
                        help="redirect playback to public test streams instead of local media")
    parser.add_argument("--verbose", action="store_true")
    _args = parser.parse_args()

    global _vod
    _catalog = fixtures.build_catalog(seed=_args.seed, target_channels=_args.channels)
    _vod = vod_mod.VodCatalog()
    print("mock-xtream: %d films and %d series in the on-demand catalogue" % (len(_vod.movies), len(_vod.series)))
    expected = _catalog.expected_kept
    print("mock-xtream: %d channels in %d categories (%d expected to survive the filter)"
          % (len(_catalog.channels), len(_catalog.categories), len(expected)))

    if not _args.no_media and not _args.public_streams:
        threading.Thread(target=build_media, daemon=True).start()
        print("mock-xtream: generating test media with ffmpeg in the background")
    else:
        _media_ready.set()

    handler = make_handler()
    httpd = ThreadingHTTPServer((_args.host, _args.port), handler)
    httpd.daemon_threads = True
    print("mock-xtream: listening on http://%s:%d  (user %s / pass %s)"
          % (_args.host, _args.port, USERNAME, PASSWORD))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nmock-xtream: stopping")


if __name__ == "__main__":
    main()
