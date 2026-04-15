#!/usr/bin/env bash
set -euo pipefail

# --- Config ---
REMOTE_HOST="${1:?Usage: ./vps.sh <user@host>}"

echo "==> Setting up VPS: $REMOTE_HOST..."
ssh "$REMOTE_HOST" bash -s <<'REMOTE'
set -euo pipefail

# --- Firewall ---
echo "==> Configuring firewall..."
sudo ufw allow 4001/tcp
sudo ufw allow 4001/udp
sudo ufw --force enable
sudo ufw status verbose

echo "==> VPS setup complete."
REMOTE
