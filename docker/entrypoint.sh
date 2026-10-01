#!/bin/bash
set -e

echo "=========================================================="
echo " Starting aceHub Docker Engine (0-Transcode Home Server)"
echo "=========================================================="

# Start AceStream Engine in background
echo "[aceHub] Launching AceStream Linux Engine..."
if [ -f "/opt/acestream/start-engine" ]; then
    /opt/acestream/start-engine --client-console --bind-all --live-cache-type memory --live-buffer 25 --max-peers 60 &
elif [ -f "/srv/ace/start-engine" ]; then
    /srv/ace/start-engine --client-console --bind-all --live-cache-type memory --live-buffer 25 --max-peers 60 &
elif command -v acestreamengine >/dev/null 2>&1; then
    acestreamengine --client-console --bind-all --live-cache-type memory --live-buffer 25 --max-peers 60 &
else
    echo "[aceHub] ERROR: Could not find AceStream Engine binary!"
    exit 1
fi
ACE_PID=$!

# Wait for AceStream Telnet API (port 62062) to be ready
echo "[aceHub] Waiting for AceStream Engine API on port 62062..."
for i in $(seq 1 30); do
    if python3 -c "import socket; s = socket.socket(); s.settimeout(1); s.connect(('127.0.0.1', 62062)); s.close()" 2>/dev/null; then
        echo "[aceHub] AceStream Engine is UP and ready on port 62062!"
        break
    fi
    sleep 1
done

# Trap exit signals to gracefully shut down child processes
cleanup() {
    echo "[aceHub] Received shutdown signal. Terminating child processes..."
    kill -TERM "$PROXY_PID" 2>/dev/null || true
    kill -TERM "$ACE_PID" 2>/dev/null || true
    wait "$PROXY_PID" 2>/dev/null || true
    wait "$ACE_PID" 2>/dev/null || true
    echo "[aceHub] All processes cleanly stopped. Bye!"
    exit 0
}
trap cleanup SIGTERM SIGINT

# Start aceHub Python Proxy on port 8000
echo "[aceHub] Launching aceHub Proxy Server on port 8000..."
python3 /app/acehub_proxy.py &
PROXY_PID=$!

echo "=========================================================="
echo " aceHub is RUNNING!"
echo " • Dashboard: http://<IP>:8000/"
echo " • Stream:    http://<IP>:8000/live?id=<CONTENT_ID>"
echo " • Status:    http://<IP>:8000/stat"
echo "=========================================================="

# Keep script running and wait for either process to terminate
wait -n "$ACE_PID" "$PROXY_PID"
cleanup
