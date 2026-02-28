#!/usr/bin/env bash
set -euo pipefail

# Resolve project root (3 levels up from freepath-transport-lan-demo/src/docker/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../.."

COMPOSE_FILE="freepath-transport-lan-demo/src/docker/docker-compose.yml"

cleanup() {
    echo "==> Cleaning up containers..."
    docker compose -f "$COMPOSE_FILE" down -v
}
trap cleanup EXIT

echo "==> Building fat JAR..."
./gradlew :freepath-transport-lan-demo:fatJar --no-daemon

echo "==> Building Docker image and starting nodes..."
docker compose -f "$COMPOSE_FILE" up --build
