#!/bin/bash
# Blue-Green deploy for AIOPE Gateway
# Run ON ship.xnet.ngo from ~/aiope-gateway
set -e
cd "$(dirname "$0")"

DATA_DIR="/home/ubuntu/aiope-gateway/data"
BLUE_PORT=8082
RED_PORT=8083

# Detect active color
ACTIVE=$(cat .active-color 2>/dev/null || echo blue)
if [ "$ACTIVE" = "blue" ]; then
  DEPLOY=red; DEPLOY_PORT=$RED_PORT
else
  DEPLOY=blue; DEPLOY_PORT=$BLUE_PORT
fi

echo "=== Blue-Green Deploy ==="
echo "Active: $ACTIVE | Deploying: $DEPLOY (port $DEPLOY_PORT)"

# 1. Pull latest
echo "[1/6] Pulling latest..."
git pull origin cloud-server

# 2. Build jar (use dev container or local gradle)
echo "[2/6] Building jar..."
if command -v java &>/dev/null && [ -f gradlew ]; then
  ./gradlew shadowJar 2>&1 | tail -3
elif docker ps --format '{{.Names}}' | grep -q xnet-dev; then
  docker exec xnet-dev bash -c "cd /workspace/aiope-gateway && ./gradlew shadowJar" 2>&1 | tail -3
else
  echo "ERROR: No java/gradle available. Install JDK or use dev container."
  exit 1
fi

# 3. Build docker image
echo "[3/6] Building image..."
docker build -t aiope-gateway:$DEPLOY . 2>&1 | tail -3

# 4. Start new container
echo "[4/6] Starting gateway-$DEPLOY on port $DEPLOY_PORT..."
docker stop gateway-$DEPLOY 2>/dev/null || true
docker rm gateway-$DEPLOY 2>/dev/null || true
docker run -d --name gateway-$DEPLOY --network host \
  -e GATEWAY_PORT=$DEPLOY_PORT -e HTTP_PORT=0 \
  -v $DATA_DIR:/opt/gateway/data \
  --restart unless-stopped aiope-gateway:$DEPLOY

# Wait for health
echo "    Waiting for health..."
for i in $(seq 1 15); do
  if curl -sf http://localhost:$DEPLOY_PORT/health >/dev/null 2>&1; then
    echo "    gateway-$DEPLOY is healthy!"
    break
  fi
  sleep 2
done

if ! curl -sf http://localhost:$DEPLOY_PORT/health >/dev/null 2>&1; then
  echo "ERROR: gateway-$DEPLOY failed to start"
  docker logs gateway-$DEPLOY --tail 10
  exit 1
fi

# 5. Swap Caddy
echo "[5/6] Switching Caddy to port $DEPLOY_PORT..."
ACTIVE_PORT=$( [ "$ACTIVE" = "blue" ] && echo $BLUE_PORT || echo $RED_PORT )
sed -i "s/reverse_proxy 127.0.0.1:$ACTIVE_PORT/reverse_proxy 127.0.0.1:$DEPLOY_PORT/" /tmp/caddy-etc/Caddyfile
docker restart caddy

sleep 3
if curl -sf https://inf.xnet.ngo/health >/dev/null 2>&1; then
  echo "[6/6] Verified through Caddy ✅"
  echo "$DEPLOY" > .active-color
  docker stop gateway-$ACTIVE 2>/dev/null || true
  echo ""
  echo "=== Deploy complete ==="
  echo "Live: gateway-$DEPLOY (:$DEPLOY_PORT)"
else
  echo "WARNING: Health check failed. Rolling back..."
  sed -i "s/reverse_proxy 127.0.0.1:$DEPLOY_PORT/reverse_proxy 127.0.0.1:$ACTIVE_PORT/" /tmp/caddy-etc/Caddyfile
  docker restart caddy
  docker stop gateway-$DEPLOY 2>/dev/null || true
  echo "Rolled back to gateway-$ACTIVE (:$ACTIVE_PORT)"
  exit 1
fi
