# freepath-relay

Rendezvous relay server for the freepath P2P network.
Allows freepath nodes to register their presence and discover each other over the internet.

Only peers with agent version starting with `freepath/` are accepted; all others are disconnected.

## Build

```bash
cargo build --release
```

## Identity

The relay needs a 32-byte ed25519 private key file for a persistent identity. Generate one with:

```bash
openssl rand 32 > relay.key
```

If you pass `--key-file relay.key` and the file does not exist, the server generates a new key and saves it
automatically.

Without `--key-file`, an ephemeral identity is used (PeerId changes on every restart).

## Run

```bash
# Minimal — ephemeral identity, TCP on port 4001
RUST_LOG=info ./target/release/freepath-relay

# Persistent identity (generates key on first run)
RUST_LOG=info ./target/release/freepath-relay --key-file relay.key

# Custom listen address + QUIC
RUST_LOG=info ./target/release/freepath-relay \
  --listen-addr /ip4/0.0.0.0/tcp/4001 \
  --extra-listen-addr /ip4/0.0.0.0/udp/4001/quic-v1 \
  --key-file relay.key
```

The server logs its PeerId on startup — clients need this to connect.

## Deploy (ARM64 Linux)

### Prerequisites (macOS)

```bash
rustup target add aarch64-unknown-linux-gnu
brew install messense/macos-cross-toolchains/aarch64-unknown-linux-gnu
```

Add to `~/.cargo/config.toml`:

```toml
[target.aarch64-unknown-linux-gnu]
linker = "aarch64-unknown-linux-gnu-gcc"
```

### Deploy with systemd

```bash
./deploy.sh root@your-server-ip
```

This will cross-compile, upload the binary, create a `freepath` system user, generate a key if missing, and start the
service.

Check logs:

```bash
ssh root@your-server-ip journalctl -u freepath-relay -f
```

### Deploy with Docker

Build and run directly on the server:

```bash
docker build -t freepath-relay .
docker run -d --name freepath-relay --restart always \
  -p 4001:4001 -p 4001:4001/udp \
  -v /etc/freepath:/etc/freepath \
  freepath-relay --key-file /etc/freepath/relay.key
```
