#!/bin/bash
# Blue-Green deploy for AIOPE Gateway
# Run from dev.xnet.ngo — builds jar locally, deploys to ship.xnet.ngo
set -e
cd "$(dirname "$0")"

REMOTE="ubuntu@ship.xnet.ngo"
DATA_DIR="/home/ubuntu/aiope-gateway/data"
BLUE_PORT=8082
RED_PORT=8083

# Detect active color
ACTIVE=$(ssh $REMOTE "cat /home/ubuntu/aiope-gateway/.active-color 2>/dev/null || echo blue")
if [ "$ACTIVE" = "blue" ]; then
  DEPLOY=red; DEPLOY_PORT=$RED_PORT
else
  DEPLOY=blue; DEPLOY_PORT=$BLUE_PORT
fi

echo "=== Blue-Green Deploy ==="
echo "Active: $ACTIVE | Deploying: $DEPLOY (port $DEPLOY_PORT)"

# 1. Build jar locally
echo "[1/6] Building jar..."
./gradlew shadowJar 2>&1 | tail -3

# 2. Copy jar to ship
echo "[2/6] Uploading jar..."
scp build/libs/gateway-server-all.jar $REMOTE:~/aiope-gateway/build/libs/

# 3. Build docker image on ship
echo "[3/6] Building image..."
ssh $REMOTE "cd ~/aiope-gateway && docker build -t aiope-gateway:$DEPLOY ." 2>&1 | tail -3

# 4. Start new container
echo "[4/6] Starting gateway-$DEPLOY on port $DEPLOY_PORT..."
ssh $REMOTE "docker stop gateway-$DEPLOY 2>/dev/null; docker rm gateway-$DEPLOY 2>/dev/null; \
  docker run -d --name gateway-$DEPLOY --network host \
    -e GATEWAY_PORT=$DEPLOY_PORT -e HTTP_PORT=0 \
    -v $DATA_DIR:/opt/gateway/data \
    --restart unless-stopped aiope-gateway:$DEPLOY"

# Wait for health
echo "    Waiting for health..."
for i in $(seq 1 15); do
  if ssh $REMOTE "curl -sf http://localhost:$DEPLOY_PORT/health" >/dev/null 2>&1; then
    echo "    gateway-$DEPLOY is healthy!"
    break
  fi
  sleep 2
done

if ! ssh $REMOTE "curl -sf http://localhost:$DEPLOY_PORT/health" >/dev/null 2>&1; then
  echo "ERROR: gateway-$DEPLOY failed to start"
  ssh $REMOTE "docker logs gateway-$DEPLOY --tail 10"
  exit 1
fi

# 5. Swap Caddy
echo "[5/6] Switching Caddy: port ${ACTIVE == blue && echo $BLUE_PORT || echo $RED_PORT} → $DEPLOY_PORT"
ssh $REMOTE "sudo sed -i 's/reverse_proxy 127.0.0.1:808[0-9]/reverse_proxy 127.0.0.1:$DEPLOY_PORT/' /tmp/caddy-etc/Caddyfile && docker restart caddy"

sleep 3
if ssh $REMOTE "curl -sf https://inf.xnet.ngo/health" >/dev/null 2>&1; then
  echo "[6/6] Verified through Caddy ✅"
  echo "$DEPLOY" | ssh $REMOTE "cat > ~/aiope-gateway/.active-color"
  ssh $REMOTE "docker stop gateway-$ACTIVE 2>/dev/null" || true
  echo ""
  echo "=== Deploy complete ==="
  echo "Live: gateway-$DEPLOY (:$DEPLOY_PORT)"
else
  echo "WARNING: Health check failed through Caddy. Rolling back..."
  ROLLBACK_PORT=$( [ "$ACTIVE" = "blue" ] && echo $BLUE_PORT || echo $RED_PORT )
  ssh $REMOTE "sudo sed -i 's/reverse_proxy 127.0.0.1:$DEPLOY_PORT/reverse_proxy 127.0.0.1:$ROLLBACK_PORT/' /tmp/caddy-etc/Caddyfile && docker restart caddy"
  ssh $REMOTE "docker stop gateway-$DEPLOY 2>/dev/null" || true
  echo "Rolled back to gateway-$ACTIVE (:$ROLLBACK_PORT)"
  exit 1
fi
