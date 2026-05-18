#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "Building gateway..."
./gradlew shadowJar

echo "Deploying (red/blue swap)..."
ansible-playbook -i inventory.ini deploy-redblue.yml
