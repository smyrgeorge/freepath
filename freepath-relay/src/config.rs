use clap::Parser;
use libp2p::identity;
use std::fs;
use std::path::PathBuf;

#[derive(Parser, Debug)]
#[command(name = "freepath-relay", about = "Freepath rendezvous relay server")]
pub struct Config {
    /// Multiaddr(s) to listen on (can be repeated).
    /// Defaults to TCP + QUIC on all IPv4 and IPv6 interfaces if not specified.
    #[arg(long, default_values = [
        "/ip4/0.0.0.0/tcp/4001",
        "/ip4/0.0.0.0/udp/4001/quic-v1",
        "/ip6/::/tcp/4001",
        "/ip6/::/udp/4001/quic-v1",
    ])]
    pub listen_addr: Vec<String>,

    /// Path to a 32-byte ed25519 private key file.
    /// If the file does not exist, a new key is generated and saved.
    /// If omitted, an ephemeral key is used.
    #[arg(long)]
    pub key_file: Option<PathBuf>,
}

pub fn load_or_generate_keypair(path: Option<&PathBuf>) -> identity::Keypair {
    match path {
        Some(p) if p.exists() => {
            let mut bytes = fs::read(p).expect("failed to read key file");
            assert!(bytes.len() == 32, "key file must be exactly 32 bytes");
            let secret = identity::ed25519::SecretKey::try_from_bytes(&mut bytes)
                .expect("invalid ed25519 secret key");
            let keypair = identity::ed25519::Keypair::from(secret);
            log::info!("Loaded identity from {}", p.display());
            identity::Keypair::from(keypair)
        }
        Some(p) => {
            let keypair = identity::ed25519::Keypair::generate();
            fs::write(p, keypair.secret().as_ref()).expect("failed to write key file");
            log::info!("Generated new identity, saved to {}", p.display());
            identity::Keypair::from(keypair)
        }
        None => {
            log::warn!("No --key-file provided; using ephemeral identity (will change on restart)");
            identity::Keypair::generate_ed25519()
        }
    }
}
