#!/bin/sh
# AceStream engine healthcheck (REFERENCE). Chạy bên trong container acestream.
# - URL health CẦN XÁC MINH với phiên bản engine (spec mục 5.4, 12).
# - Sau ENGINE_HC_KILL_AFTER lần lỗi liên tiếp: gửi SIGTERM tới PID 1 (docker-init/tini do init: true)
#   -> container exit -> restart policy unless-stopped khởi động lại container.
URL="${ENGINE_HC_URL:-http://127.0.0.1:6878/webui/api/service?method=get_version&format=json}"
KILL_AFTER="${ENGINE_HC_KILL_AFTER:-4}"
STATE=/tmp/acehub_hc_fail

check() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS -m 5 -o /dev/null "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 5 -O /dev/null "$URL"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "import sys,urllib.request;urllib.request.urlopen(sys.argv[1],timeout=5)" "$URL"
  else
    echo "no http client in image" >&2
    return 1
  fi
}

if check; then
  rm -f "$STATE"
  exit 0
fi

n=$(cat "$STATE" 2>/dev/null || echo 0)
n=$((n + 1))
echo "$n" > "$STATE"
echo "engine health failed ($n/$KILL_AFTER)" >&2
if [ "$n" -ge "$KILL_AFTER" ]; then
  rm -f "$STATE"
  kill -TERM 1
fi
exit 1
