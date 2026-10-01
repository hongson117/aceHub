# ============================================================
# aceHub Docker: Home Server Headless Engine (0-Transcode Edition)
# ============================================================
FROM smarquezp/docker-acestream-ubuntu-home:latest

LABEL maintainer="hongson117" \
      description="aceHub: Home Server 0-Transcode P2P Streaming Engine" \
      version="1.0.0" \
      org.opencontainers.image.source="https://github.com/hongson117/aceHub" \
      org.opencontainers.image.title="aceHub Docker Engine" \
      org.opencontainers.image.description="Unified P2P Live Sports Streamer for Home Server & NAS"

USER root

# Ensure curl and python3 are ready
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl procps \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy aceHub Proxy and Entrypoint
COPY acehub_proxy.py /app/acehub_proxy.py
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh /app/acehub_proxy.py

# Expose aceHub unified port (8000), AceStream HTTP (6878), and P2P Incoming (8621)
EXPOSE 8000 6878 8621/udp 8621/tcp

HEALTHCHECK --interval=20s --timeout=5s --retries=3 \
    CMD curl -f http://127.0.0.1:8000/stat || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
