mod behaviour;
mod config;

use behaviour::{RelayBehaviour, RelayBehaviourEvent};
use config::{load_or_generate_keypair, Config};

use clap::Parser;
use futures::StreamExt;
use libp2p::{identify, rendezvous, Multiaddr, SwarmBuilder};
use libp2p_swarm::SwarmEvent;
use std::time::Duration;

#[tokio::main]
async fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let config = Config::parse();
    let keypair = load_or_generate_keypair(config.key_file.as_ref());
    let peer_id = keypair.public().to_peer_id();
    log::info!("Relay PeerId: {peer_id}");

    let listen_addr: Multiaddr = config
        .listen_addr
        .parse()
        .expect("invalid --listen-addr multiaddr");

    // DNS resolvers: Quad9 + Cloudflare + Google for resilience.
    let mut dns_config = libp2p_dns::ResolverConfig::new();
    for ns in libp2p_dns::ResolverConfig::quad9().name_servers() {
        dns_config.add_name_server(ns.clone());
    }
    for ns in libp2p_dns::ResolverConfig::cloudflare().name_servers() {
        dns_config.add_name_server(ns.clone());
    }
    for ns in libp2p_dns::ResolverConfig::google().name_servers() {
        dns_config.add_name_server(ns.clone());
    }

    let mut swarm = SwarmBuilder::with_existing_identity(keypair)
        .with_tokio()
        .with_tcp(
            libp2p::tcp::Config::default(),
            libp2p::noise::Config::new,
            libp2p::yamux::Config::default,
        )
        .expect("TCP transport setup failed")
        .with_quic()
        .with_dns_config(dns_config, libp2p_dns::ResolverOpts::default())
        .with_behaviour(|key| Ok(RelayBehaviour::new(key)))
        .expect("behaviour setup failed")
        .with_swarm_config(|cfg| cfg.with_idle_connection_timeout(Duration::from_secs(60)))
        .build();

    swarm
        .listen_on(listen_addr.clone())
        .expect("failed to listen");

    for addr_str in &config.extra_listen_addr {
        let addr: Multiaddr = addr_str.parse().expect("invalid --extra-listen-addr");
        swarm
            .listen_on(addr)
            .expect("failed to listen on extra addr");
    }

    loop {
        match swarm.select_next_some().await {
            SwarmEvent::Behaviour(RelayBehaviourEvent::Identify(identify::Event::Received {
                peer_id,
                info,
                ..
            })) => {
                if !info.agent_version.starts_with("freepath/") {
                    log::warn!(
                        "Rejecting peer {peer_id}: agent_version='{}'",
                        info.agent_version
                    );
                    let _ = swarm.disconnect_peer_id(peer_id);
                } else {
                    log::info!("Identified freepath peer: {peer_id}");
                }
            }

            SwarmEvent::Behaviour(RelayBehaviourEvent::Rendezvous(event)) => {
                handle_rendezvous_event(event);
            }

            SwarmEvent::NewListenAddr { address, .. } => {
                log::info!("Listening on {address}");
            }
            SwarmEvent::ConnectionEstablished {
                peer_id, endpoint, ..
            } => {
                log::debug!(
                    "Connection established: peer={peer_id} addr={}",
                    endpoint.get_remote_address()
                );
            }
            SwarmEvent::ConnectionClosed { peer_id, .. } => {
                log::debug!("Connection closed: peer={peer_id}");
            }
            SwarmEvent::OutgoingConnectionError { peer_id, error, .. } => {
                log::debug!("Outgoing connection error (peer={peer_id:?}): {error}");
            }
            SwarmEvent::IncomingConnectionError { error, .. } => {
                log::warn!("Incoming connection error: {error}");
            }

            _ => {}
        }
    }
}

fn handle_rendezvous_event(event: rendezvous::server::Event) {
    match event {
        rendezvous::server::Event::PeerRegistered { peer, registration } => {
            log::info!(
                "Peer {peer} registered in namespace '{}'",
                registration.namespace
            );
        }
        rendezvous::server::Event::DiscoverServed {
            enquirer,
            registrations,
        } => {
            log::info!(
                "Served {} registration(s) to {enquirer}",
                registrations.len()
            );
        }
        rendezvous::server::Event::PeerNotRegistered {
            peer, namespace, ..
        } => {
            log::info!("Peer {peer} unregistered from namespace '{namespace}'");
        }
        rendezvous::server::Event::RegistrationExpired(registration) => {
            log::info!(
                "Registration expired: peer={}",
                registration.record.peer_id()
            );
        }
        _ => {}
    }
}
