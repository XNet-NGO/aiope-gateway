#!/bin/bash
# flash.sh — Write Pi OS to SD card and inject cloud-init
# Usage: sudo ./flash.sh /dev/sdX [pi-image.img.xz]
set -e

DEVICE="${1:?Usage: sudo ./flash.sh /dev/sdX [image.img.xz]}"
IMAGE="${2:-https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-rpi-3.21.3-aarch64.img.gz}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== AIOPE Gateway Flash Tool ==="
echo "Target: $DEVICE"
echo "Image:  $IMAGE"
read -p "This will ERASE $DEVICE. Continue? [y/N] " -n 1 -r
echo
[[ $REPLY =~ ^[Yy]$ ]] || exit 1

# Write image
echo "[1/3] Writing image..."
if [[ "$IMAGE" == http* ]]; then
    curl -L "$IMAGE" | gunzip -c | dd of="$DEVICE" bs=4M status=progress conv=fsync
else
    gunzip -c "$IMAGE" | dd of="$DEVICE" bs=4M status=progress conv=fsync
fi
sync

# Mount boot partition and inject cloud-init
echo "[2/3] Injecting cloud-init..."
BOOT=$(mktemp -d)
mount "${DEVICE}1" "$BOOT"
cp "$SCRIPT_DIR/cloud-init.yml" "$BOOT/user-data"
echo "" > "$BOOT/meta-data"
umount "$BOOT"
rmdir "$BOOT"

echo "[3/3] Done!"
echo ""
echo "Insert SD card into Pi and power on."
echo "Gateway will be available at http://<pi-ip>:8082"
echo "Web shell at http://<pi-ip>:7681"
echo "Default API key: aiope-gateway-key"
echo ""
echo "Find the Pi on your network:"
echo "  ping aiope-gateway.local"
echo "  nmap -sn 192.168.1.0/24"
