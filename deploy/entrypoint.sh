#!/bin/bash
set -e

PORT="${GATEWAY_PORT:-8082}"
TTYD_PORT="${TTYD_PORT:-7681}"
DATA_DIR="${DATA_DIR:-/opt/gateway/data}"

mkdir -p "$DATA_DIR"

# Start ttyd web shell in background
ttyd -p "$TTYD_PORT" -W -t fontSize=14 -t theme='{"background":"#121212","foreground":"#e0e0e0"}' /bin/bash &
echo "[entrypoint] Web shell on port $TTYD_PORT"

# Start gateway
echo "[entrypoint] Starting AIOPE Gateway on port $PORT"
exec java -Xmx256m -jar /opt/gateway/gateway.jar "$PORT" "$DATA_DIR"
