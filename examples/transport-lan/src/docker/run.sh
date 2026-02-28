#!/usr/bin/env bash
set -euo pipefail

# Resolve project root (3 levels up from examples/transport-lan/src/docker/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../../../.."

COMPOSE_FILE="examples/transport-lan/src/docker/docker-compose.yml"

cleanup() {
    echo "==> Cleaning up containers..."
    docker compose -f "$COMPOSE_FILE" down -v
}
trap cleanup EXIT

echo "==> Building fat JAR..."
./gradlew :examples:transport-lan:fatJar --no-daemon

echo "==> Building Docker image and starting nodes..."
docker compose -f "$COMPOSE_FILE" up --build
