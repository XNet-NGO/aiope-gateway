#!/bin/bash
# Blue-Green deploy for AIOPE Gateway
# Run from /workspace/aiope-gateway on the server
set -e

BLUE=aiope-gateway-blue
GREEN=aiope-gateway-green
IMAGE=aiope-gateway:alpine
DATA_VOL=/workspace/aiope-gateway/data
BLUE_PORT=8082
GREEN_PORT=8083

# Detect which is live via Caddy
LIVE_PORT=$(docker exec caddy-l4 cat /config/caddy/autosave.json 2>/dev/null | grep -o 'localhost:[0-9]*' | grep -v 7682 | grep -v 3000 | grep -v 2019 | head -1 | cut -d: -f2)
if [ "$LIVE_PORT" = "$BLUE_PORT" ]; then
  LIVE=$BLUE; IDLE=$GREEN
  LIVE_P=$BLUE_PORT; IDLE_P=$GREEN_PORT
else
  LIVE=$GREEN; IDLE=$BLUE
  LIVE_P=$GREEN_PORT; IDLE_P=$BLUE_PORT
fi

echo "=== Blue-Green Deploy ==="
echo "Live: $LIVE (:$LIVE_P)  →  Deploying to: $IDLE (:$IDLE_P)"

# 1. Pull latest code
echo "[1/5] Pulling latest code..."
git pull origin main

# 2. Build jar
echo "[2/5] Building jar..."
docker exec xnet-dev bash -c "cd /workspace/aiope-gateway && ./gradlew shadowJar" 2>&1 | tail -3

# 3. Build image
echo "[3/5] Building Docker image..."
docker build -f Dockerfile.alpine -t $IMAGE . 2>&1 | tail -2

# 4. Start idle container with new image
echo "[4/5] Starting $IDLE on port $IDLE_P..."
docker stop $IDLE 2>/dev/null || true
docker rm $IDLE 2>/dev/null || true
docker run -d --name $IDLE --network host \
  -e GATEWAY_PORT=$IDLE_P -e HTTP_PORT=0 \
  -v $DATA_VOL:/opt/gateway/data \
  --restart unless-stopped $IMAGE

# Wait for it to be ready
echo "    Waiting for $IDLE to start..."
for i in $(seq 1 10); do
  if curl -sf -o /dev/null -w '%{http_code}' http://localhost:$IDLE_P/v1/data 2>/dev/null | grep -qE '401|200'; then
    echo "    $IDLE is up!"
    break
  fi
  sleep 1
done

if ! curl -sf -o /dev/null -w '%{http_code}' http://localhost:$IDLE_P/v1/data 2>/dev/null | grep -qE '401|200'; then
  echo "ERROR: $IDLE failed to start. Aborting."
  docker logs $IDLE 2>&1 | tail -5
  exit 1
fi

# 5. Switch Caddy
echo "[5/5] Switching Caddy: $LIVE_P → $IDLE_P"
docker exec caddy-l4 sh -c "cat /config/caddy/autosave.json | sed s/$LIVE_P/$IDLE_P/g | tee /etc/caddy/caddy.json > /dev/null && caddy reload --config /etc/caddy/caddy.json"

sleep 2
if curl -s -o /dev/null -w '%{http_code}' https://inf.xnet.ngo/v1/data 2>/dev/null | grep -qE '401|200'; then
  echo ""
  echo "=== Deploy complete ==="
  echo "Live: $IDLE (:$IDLE_P)"
  echo "Idle: $LIVE (:$LIVE_P) — stopping..."
  docker stop $LIVE
else
  echo "WARNING: Health check failed through Caddy. Rolling back..."
  docker exec caddy-l4 sh -c "cat /config/caddy/autosave.json | sed s/$IDLE_P/$LIVE_P/g | tee /etc/caddy/caddy.json > /dev/null && caddy reload --config /etc/caddy/caddy.json"
  echo "Rolled back to $LIVE (:$LIVE_P)"
  exit 1
fi
