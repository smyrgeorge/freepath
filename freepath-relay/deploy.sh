#!/usr/bin/env bash
set -euo pipefail

# --- Config ---
REMOTE_HOST="${1:?Usage: ./deploy.sh <user@host>}"

# --- Cross-compile for aarch64-linux ---
echo "==> Building for aarch64-unknown-linux-gnu..."
cargo build --release --target aarch64-unknown-linux-gnu

BINARY="target/aarch64-unknown-linux-gnu/release/freepath-relay"
if [ ! -f "$BINARY" ]; then
    echo "ERROR: binary not found at $BINARY"
    exit 1
fi

# --- Upload ---
echo "==> Uploading binary to $REMOTE_HOST..."
cat "$BINARY" | ssh "$REMOTE_HOST" "sudo tee /usr/local/bin/freepath-relay > /dev/null && sudo chmod 755 /usr/local/bin/freepath-relay"

echo "==> Installing on remote..."
ssh "$REMOTE_HOST" bash -s <<'REMOTE'
set -euo pipefail

# Create user and key dir (idempotent)
sudo useradd --system --no-create-home --shell /usr/sbin/nologin freepath 2>/dev/null || true
sudo mkdir -p /etc/freepath
sudo chown freepath:freepath /etc/freepath
sudo chmod 700 /etc/freepath

# Generate key if missing
if [ ! -f /etc/freepath/relay.key ]; then
    echo "==> Generating new relay identity..."
    sudo openssl rand 32 | sudo -u freepath tee /etc/freepath/relay.key > /dev/null
    sudo chmod 600 /etc/freepath/relay.key
fi
REMOTE

# --- Upload and enable service ---
echo "==> Installing systemd service..."
cat freepath-relay.service | ssh "$REMOTE_HOST" "sudo tee /etc/systemd/system/freepath-relay.service > /dev/null"
ssh "$REMOTE_HOST" bash -s <<'REMOTE'
set -euo pipefail
sudo systemctl daemon-reload
sudo systemctl enable freepath-relay
sudo systemctl restart freepath-relay
echo "==> Done. Checking status..."
sleep 2
sudo systemctl status freepath-relay --no-pager
REMOTE
