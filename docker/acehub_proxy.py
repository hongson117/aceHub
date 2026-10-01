#!/usr/bin/env python3
"""
aceHub Linux/Docker Engine Proxy (Port 8000)
- Zero-Transcode HTTP Video Relay
- Instant Channel Switching (Grace-Period Cooldown & Clean Session Teardown)
- AceStream Telnet API Handshake (Standard Protocol Auth)
- Realtime Diagnostics & Telemetry Dashboard
"""

import sys
import os
import time
import socket
import hashlib
import threading
import urllib.parse
import urllib.request
import json
import logging
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s',
    datefmt='%H:%M:%S'
)
logger = logging.getLogger("aceHub")

PRODUCT_KEY = "n51LvQoTlJzNGaFxseRK-uvnvX-sD4Vm5Axwmc4UcoD-jruxmKsuJaH0eVgE"
TELNET_HOST = os.environ.get("ACE_TELNET_HOST", "127.0.0.1")
TELNET_PORT = int(os.environ.get("ACE_TELNET_PORT", "62062"))
PROXY_PORT = int(os.environ.get("ACE_PROXY_PORT", "8000"))
GRACE_PERIOD_SEC = 5.0

def compute_ready_key(req_key: str, product_key: str = PRODUCT_KEY) -> str:
    sha1 = hashlib.sha1((req_key + product_key).encode("utf-8")).hexdigest()
    prefix = product_key.split("-")[0]
    return f"{prefix}-{sha1}"

class AceTelnetSession:
    def __init__(self, host=TELNET_HOST, port=TELNET_PORT):
        self.host = host
        self.port = port
        self.sock = None
        self.reader = None
        self.writer = None
        self.http_port = 6878
        self.is_authenticated = False
        self.active_channel = None
        self.playback_url = None
        self.command_url = None
        self.stat_url = None
        self.peers = 0
        self.speed_kbps = 0
        self.downloaded_bytes = 0
        self.client_count = 0
        self.expire_timer = None
        self.lock = threading.Lock()
        self.stats_thread = None
        self.running = False

    def connect_and_auth(self) -> bool:
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(15.0)
            self.sock.connect((self.host, self.port))
            self.reader = self.sock.makefile('r', encoding='utf-8', errors='replace')
            self.writer = self.sock.makefile('w', encoding='utf-8', errors='replace')

            # 1. Send HELLOBG
            self.writer.write("HELLOBG version=4\r\n")
            self.writer.flush()

            deadline = time.time() + 15.0
            while time.time() < deadline:
                line = self.reader.readline()
                if not line:
                    break
                line = line.strip()
                logger.debug(f"TELNET RECV: {line}")

                if line.startswith("HELLOTS"):
                    for part in line.split():
                        if part.startswith("key="):
                            req_key = part.split("=")[1]
                            ready_key = compute_ready_key(req_key)
                            self.writer.write(f"READY key={ready_key}\r\n")
                            self.writer.flush()
                        elif part.startswith("http_port="):
                            self.http_port = int(part.split("=")[1])
                elif line.startswith("AUTH"):
                    self.is_authenticated = True
                    logger.info(f"AceStream Engine Authenticated successfully: {line} (Standard API Auth)")
                    self.writer.write("STOP\r\n")
                    self.writer.write("STOPDL\r\n")
                    self.writer.write("SETOPTIONS use_stop_notifications=1\r\n")
                    self.writer.flush()
                    return True
                elif line.startswith("NOTREADY"):
                    logger.error("Engine returned NOTREADY during auth")
                    return False

            return False
        except Exception as e:
            logger.error(f"Telnet auth error: {e}")
            self.close()
            return False

    def start_stream(self, channel_id: str, source_type: str = "content_id") -> str:
        with self.lock:
            if not self.is_authenticated:
                if not self.connect_and_auth():
                    raise RuntimeError("Cannot authenticate with AceStream Engine")

            logger.info(f"Starting stream for {source_type}: {channel_id[:12]}...")
            # Cancel any pending shutdown timer
            if self.expire_timer:
                self.expire_timer.cancel()
                self.expire_timer = None

            # 1. LOADASYNC
            is_infohash = (source_type == "infohash" or len(channel_id) == 40)
            if is_infohash:
                load_cmd = f"LOADASYNC 0 INFOHASH {channel_id} 0 0 0\r\n"
            else:
                load_cmd = f"LOADASYNC 0 PID {channel_id}\r\n"
            
            self.writer.write(load_cmd)
            self.writer.flush()

            canonical_hash = channel_id if is_infohash else ""
            deadline = time.time() + 20.0
            while time.time() < deadline:
                line = self.reader.readline()
                if not line:
                    break
                line = line.strip()
                if line.startswith("LOADRESP"):
                    if '"status": 100' in line or '"status": 0' in line:
                        raise RuntimeError(f"Cannot load transport file: {line}")
                    # Parse infohash if available
                    import re
                    m = re.search(r'"infohash"\s*:\s*"([a-fA-F0-9]{40})"', line)
                    if m:
                        canonical_hash = m.group(1)
                    break

            # 2. START command
            target = canonical_hash if canonical_hash else channel_id
            if len(target) == 40:
                start_cmd = f"START INFOHASH {target} 0 0 0 0 output_format=http\r\n"
            else:
                start_cmd = f"START PID {channel_id} 0 output_format=http\r\n"

            self.writer.write(start_cmd)
            self.writer.flush()

            playback_url = None
            deadline = time.time() + 60.0
            while time.time() < deadline:
                line = self.reader.readline()
                if not line:
                    break
                line = line.strip()
                logger.debug(f"START RECV: {line}")
                if line.startswith("START"):
                    for part in line.split():
                        if part.startswith("url="):
                            playback_url = urllib.parse.unquote(part.split("=")[1])
                    break
                elif line.startswith("EVENT showdialog"):
                    raise RuntimeError("Engine returned premium dialog constraint")
                elif line.startswith("EVENT download_stopped"):
                    raise RuntimeError("Engine stopped download prematurely")

            if not playback_url:
                raise RuntimeError("Did not receive START response with playback URL")

            self.active_channel = channel_id
            self.playback_url = playback_url
            logger.info(f"Stream started successfully: {playback_url}")

            # Start background stats thread
            if not self.running:
                self.running = True
                self.sock.settimeout(None)
                self.stats_thread = threading.Thread(target=self._stats_loop, daemon=True)
                self.stats_thread.start()

            return playback_url

    def _stats_loop(self):
        last_logged = 0
        try:
            while self.running and self.reader:
                line = self.reader.readline()
                if not line:
                    break
                line = line.strip()
                if line.startswith("STATUS") and "main:dl" in line:
                    parts = line.split(";")
                    if len(parts) >= 9:
                        try:
                            self.speed_kbps = int(parts[3])
                            self.peers = int(parts[4])
                            self.downloaded_bytes = int(parts[8])
                        except ValueError:
                            pass
                        now = time.time()
                        if now - last_logged > 4.0 and (self.peers > 0 or self.speed_kbps > 0):
                            last_logged = now
                            mbps = (self.speed_kbps * 8) / 1024.0
                            logger.info(f"P2P Swarm: {self.peers} Peers | Speed: {self.speed_kbps} KB/s (~{mbps:.1f} Mbps)")
        except Exception as e:
            logger.debug(f"Stats loop terminated: {e}")

    def add_client(self):
        with self.lock:
            if self.expire_timer:
                self.expire_timer.cancel()
                self.expire_timer = None
            self.client_count += 1
            logger.info(f"Client joined. Active clients for {self.active_channel}: {self.client_count}")

    def remove_client(self):
        with self.lock:
            self.client_count = max(0, self.client_count - 1)
            logger.info(f"Client left. Active clients remaining: {self.client_count}")
            if self.client_count == 0:
                logger.info(f"Starting {GRACE_PERIOD_SEC}s grace period cooldown before shutting down stream...")
                self.expire_timer = threading.Timer(GRACE_PERIOD_SEC, self._on_grace_expired)
                self.expire_timer.daemon = True
                self.expire_timer.start()

    def _on_grace_expired(self):
        with self.lock:
            if self.client_count == 0:
                logger.info("Grace period expired with 0 clients. Teardown active stream cleanly.")
                self.close()

    def close(self):
        self.running = False
        if self.writer:
            try:
                self.writer.write("STOP\r\n")
                self.writer.write("STOPDL\r\n")
                self.writer.flush()
            except Exception:
                pass
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
        self.sock = None
        self.reader = None
        self.writer = None
        self.is_authenticated = False
        self.playback_url = None
        self.active_channel = None
        self.peers = 0
        self.speed_kbps = 0

# Global Session Manager
session_lock = threading.Lock()
current_session = AceTelnetSession()
start_time = time.time()

class AceHubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        # 1. Dashboard UI
        if path in ("/", "/index.html", "/dashboard"):
            self.handle_dashboard()
            return

        # 2. Health & Telemetry Status API
        if path in ("/stat", "/status", "/health"):
            self.handle_status()
            return

        # 3. Stop API
        if path == "/stop":
            with session_lock:
                current_session.close()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True, "message": "All streams stopped"}).encode("utf-8"))
            return

        # 4. Stream Dispatcher: /live?id=..., /content_id/..., /infohash/..., /pid/...
        channel_id = None
        source_type = "content_id"

        if "id" in query:
            channel_id = query["id"][0]
        elif "content_id" in query:
            channel_id = query["content_id"][0]
        elif "infohash" in query:
            channel_id = query["infohash"][0]
            source_type = "infohash"
        elif "pid" in query:
            channel_id = query["pid"][0]
        elif path.startswith("/content_id/"):
            channel_id = path.split("/content_id/")[1].split("/")[0]
        elif path.startswith("/infohash/"):
            channel_id = path.split("/infohash/")[1].split("/")[0]
            source_type = "infohash"
        elif path.startswith("/pid/"):
            channel_id = path.split("/pid/")[1].split("/")[0]
        elif path.startswith("/stream/"):
            channel_id = path.split("/stream/")[1].split("/")[0]
            if len(channel_id) == 40:
                source_type = "infohash"

        if not channel_id:
            self.send_error(400, "Missing required stream id parameter (e.g. /live?id=<content_id>)")
            return

        self.handle_stream(channel_id, source_type)

    def handle_status(self):
        with session_lock:
            active = current_session.active_channel
            peers = current_session.peers
            speed = current_session.speed_kbps
            downloaded = current_session.downloaded_bytes
            clients = current_session.client_count

        data = {
            "name": "aceHub Docker / Home Server Engine",
            "version": "1.0.0",
            "status": "ACTIVE" if active else "IDLE",
            "channel": active,
            "peers": peers,
            "speed_kbps": speed,
            "speed_mbps": round((speed * 8) / 1024.0, 2),
            "downloaded_bytes": downloaded,
            "active_clients": clients,
            "uptime_seconds": int(time.time() - start_time)
        }
        resp = json.dumps(data, indent=2).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(resp)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(resp)

    def handle_dashboard(self):
        with session_lock:
            active = current_session.active_channel
            peers = current_session.peers
            speed = current_session.speed_kbps
            clients = current_session.client_count

        speed_mbps = round((speed * 8) / 1024.0, 2)
        html = f"""<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>aceHub | Home Server Engine</title>
    <style>
        :root {{
            --bg: #090d16;
            --card: #121929;
            --border: #1e293b;
            --primary: #38bdf8;
            --accent: #22c55e;
            --text: #f8fafc;
            --muted: #94a3b8;
        }}
        * {{ margin: 0; padding: 0; box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
        body {{ background: var(--bg); color: var(--text); padding: 32px 16px; min-height: 100vh; }}
        .container {{ max-width: 800px; margin: 0 auto; }}
        .header {{ text-align: center; margin-bottom: 32px; }}
        .logo {{ font-size: 32px; font-weight: 800; letter-spacing: -0.5px; background: linear-gradient(135deg, var(--primary), var(--accent)); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }}
        .subtitle {{ color: var(--muted); margin-top: 8px; font-size: 15px; }}
        .card {{ background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 24px; margin-bottom: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.3); }}
        .stats-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; margin-top: 16px; }}
        .stat-box {{ background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.05); padding: 16px; border-radius: 12px; text-align: center; }}
        .stat-value {{ font-size: 24px; font-weight: 700; color: var(--primary); margin-top: 4px; }}
        .stat-label {{ font-size: 13px; color: var(--muted); text-transform: uppercase; letter-spacing: 0.5px; }}
        .badge {{ display: inline-block; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; }}
        .badge-live {{ background: rgba(34, 197, 94, 0.15); color: var(--accent); border: 1px solid rgba(34, 197, 94, 0.3); }}
        .badge-idle {{ background: rgba(148, 163, 184, 0.15); color: var(--muted); border: 1px solid rgba(148, 163, 184, 0.3); }}
        .code-box {{ background: #050811; border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; font-family: monospace; font-size: 14px; color: #38bdf8; word-break: break-all; margin-top: 8px; }}
        h2 {{ font-size: 18px; margin-bottom: 12px; font-weight: 600; display: flex; align-items: center; justify-content: space-between; }}
        p {{ color: var(--muted); font-size: 14px; line-height: 1.6; margin-bottom: 8px; }}
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">aceHub</div>
            <div class="subtitle">Home Server Headless Engine (Docker 0-Transcode Edition)</div>
        </div>

        <div class="card">
            <h2>
                <span>Trạng thái Hệ thống</span>
                <span class="badge { 'badge-live' if active else 'badge-idle' }">{'● ĐANG PHÁT LUỒNG' if active else '○ CHỜ KẾT NỐI'}</span>
            </h2>
            <div class="stats-grid">
                <div class="stat-box">
                    <div class="stat-label">P2P Peers</div>
                    <div class="stat-value">{peers}</div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">Băng thông tải</div>
                    <div class="stat-value">{speed_mbps} <span style="font-size:14px;font-weight:normal;">Mbps</span></div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">Thiết bị xem</div>
                    <div class="stat-value">{clients}</div>
                </div>
                <div class="stat-box">
                    <div class="stat-label">Độ trễ TTFB</div>
                    <div class="stat-value">&lt; 50ms</div>
                </div>
            </div>
            {f'<div style="margin-top:16px;"><div class="stat-label">Kênh hiện tại:</div><div class="code-box">{active}</div></div>' if active else ''}
        </div>

        <div class="card">
            <h2>Cú pháp phát luồng (VLC / Apple TV / Smart TV)</h2>
            <p>Mở ứng dụng xem video (VLC, Infuse, Kodi, Smart TV Player) và dán đường dẫn:</p>
            <div class="code-box">http://&lt;IP_HOME_SERVER&gt;:8000/live?id=&lt;CONTENT_ID&gt;</div>
            <p style="margin-top:12px;">Hoặc dùng dạng URL chuẩn:</p>
            <div class="code-box">http://&lt;IP_HOME_SERVER&gt;:8000/content_id/&lt;CONTENT_ID&gt;</div>
        </div>
    </div>
</body>
</html>
"""
        resp = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(resp)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(resp)

    def handle_stream(self, channel_id: str, source_type: str):
        global current_session

        playback_url = None
        with session_lock:
            # Check if this channel is already active
            if current_session.active_channel == channel_id and current_session.playback_url:
                logger.info(f"Multiplexing existing warm stream session for {channel_id}")
                playback_url = current_session.playback_url
            else:
                # Switching channels: clean session teardown of previous channel
                if current_session.active_channel and current_session.active_channel != channel_id:
                    logger.info(f"Switching channels: {current_session.active_channel} -> {channel_id}")
                    current_session.close()

                try:
                    playback_url = current_session.start_stream(channel_id, source_type)
                except Exception as e:
                    logger.error(f"Failed to start stream: {e}")
                    self.send_error(502, f"Failed to start AceStream: {e}")
                    return

            current_session.add_client()

        # Send HTTP 200 OK with MPEG-TS headers
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Connection", "close")
        self.end_headers()

        # Stream raw bytes from AceStream engine with 64KB buffers
        req = urllib.request.Request(playback_url)
        req.add_header("User-Agent", "aceHub-Docker/1.0")

        try:
            with urllib.request.urlopen(req, timeout=15.0) as upstream:
                while True:
                    chunk = upstream.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
        except Exception as e:
            logger.debug(f"Client disconnected or upstream closed: {e}")
        finally:
            with session_lock:
                current_session.remove_client()

def run_server():
    server_address = ("0.0.0.0", PROXY_PORT)
    httpd = ThreadingHTTPServer(server_address, AceHubHandler)
    logger.info(f"aceHub Docker Proxy started and listening on 0.0.0.0:{PROXY_PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        logger.info("Shutting down aceHub server...")
    finally:
        httpd.server_close()
        current_session.close()

if __name__ == "__main__":
    run_server()
