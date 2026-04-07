#!/bin/bash
# flash-pi3.sh — Flash Pi OS Lite 64-bit + AIOPE Gateway for Pi 3B
# Usage: sudo ./flash-pi3.sh /dev/sdX
set -e

DEVICE="${1:?Usage: sudo ./flash-pi3.sh /dev/sdX}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$SCRIPT_DIR/../build/libs/gateway-server-all.jar"
CLOUD_INIT="$SCRIPT_DIR/cloud-init-pi3.yml"
IMAGE_URL="https://downloads.raspberrypi.com/raspios_lite_arm64/images/raspios_lite_arm64-2025-05-13/2025-05-13-raspios-bookworm-arm64-lite.img.xz"
IMAGE_FILE="/tmp/raspios-lite-arm64.img.xz"

echo "=== AIOPE Gateway — Pi 3B Flash Tool ==="
echo "Target:  $DEVICE"
echo "JAR:     $JAR"

[ -f "$JAR" ] || { echo "ERROR: Build the JAR first: ./gradlew shadowJar"; exit 1; }

read -p "This will ERASE $DEVICE. Continue? [y/N] " -n 1 -r
echo
[[ $REPLY =~ ^[Yy]$ ]] || exit 1

# Download Pi OS if needed
if [ ! -f "$IMAGE_FILE" ]; then
    echo "[1/5] Downloading Raspberry Pi OS Lite (arm64)..."
    curl -L -o "$IMAGE_FILE" "$IMAGE_URL"
else
    echo "[1/5] Using cached image"
fi

# Flash
echo "[2/5] Writing image to $DEVICE..."
xzcat "$IMAGE_FILE" | dd of="$DEVICE" bs=4M status=progress conv=fsync
sync
sleep 2

# Re-read partition table
partprobe "$DEVICE" 2>/dev/null || true
sleep 2

# Detect partitions
BOOT="${DEVICE}1"
ROOT="${DEVICE}2"
[ -b "${DEVICE}p1" ] && BOOT="${DEVICE}p1" && ROOT="${DEVICE}p2"

# Mount boot and rootfs
BOOT_MNT=$(mktemp -d)
ROOT_MNT=$(mktemp -d)
mount "$BOOT" "$BOOT_MNT"
mount "$ROOT" "$ROOT_MNT"

# Enable SSH
echo "[3/5] Enabling SSH..."
touch "$BOOT_MNT/ssh"

# Inject cloud-init
echo "[4/5] Injecting cloud-init and gateway JAR..."
cp "$CLOUD_INIT" "$BOOT_MNT/user-data"
echo "" > "$BOOT_MNT/meta-data"

# Copy gateway JAR to rootfs
mkdir -p "$ROOT_MNT/opt/gateway/data"
cp "$JAR" "$ROOT_MNT/opt/gateway/gateway.jar"

# Set ownership (uid 1000 = first user)
chown -R 1000:1000 "$ROOT_MNT/opt/gateway"

# Unmount
echo "[5/5] Finalizing..."
umount "$BOOT_MNT"
umount "$ROOT_MNT"
rmdir "$BOOT_MNT" "$ROOT_MNT"
sync

echo ""
echo "=== Done! ==="
echo ""
echo "Insert SD card into Pi 3B and power on."
echo "First boot takes 3-5 minutes (installs Java, certbot, etc.)"
echo ""
echo "Access:"
echo "  SSH:    ssh aiope@aiope-gw.local  (password: aiope)"
echo "  Portal: http://aiope-gw.local:8082"
echo "  Shell:  http://aiope-gw.local:7681"
echo "  API:    http://aiope-gw.local:8082/v1"
echo "  Key:    aiope-gateway-key"
echo ""
echo "Change the password and API key after first login!"
