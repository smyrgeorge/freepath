use std::collections::HashMap;
use std::time::Duration;

use libp2p::{
    autonat, dcutr, identify, identity, noise, ping, relay, rendezvous, request_response, tcp,
    upnp, yamux, Multiaddr, PeerId, SwarmBuilder,
};
use libp2p_swarm::NetworkBehaviour;
use rand::rngs::OsRng;

use crate::core::messaging::{FreepathCodec, FreepathProtocol};

pub fn freepath_namespace() -> rendezvous::Namespace {
    rendezvous::Namespace::from_static("freepath")
}

/// Returns the PeerId component of `addr` if present.
pub fn extract_peer_id(addr: &Multiaddr) -> Option<PeerId> {
    addr.iter().find_map(|p| match p {
        libp2p::multiaddr::Protocol::P2p(id) => Some(id),
        _ => None,
    })
}

/// Returns true if `addr` contains a `/p2p-circuit` component.
pub fn is_circuit_addr(addr: &Multiaddr) -> bool {
    addr.iter()
        .any(|p| matches!(p, libp2p::multiaddr::Protocol::P2pCircuit))
}

/// Build a circuit-listen multiaddr for `relay_id` from the relay's dial address.
/// Strips any existing `/p2p/...` component, then appends `/p2p/<relay>/p2p-circuit`.
pub fn circuit_addr_for(relay_addr: &Multiaddr, relay_id: PeerId) -> Multiaddr {
    let base: Multiaddr = relay_addr
        .iter()
        .filter(|p| !matches!(p, libp2p::multiaddr::Protocol::P2p(_)))
        .collect();
    base.with(libp2p::multiaddr::Protocol::P2p(relay_id))
        .with(libp2p::multiaddr::Protocol::P2pCircuit)
}

pub fn register_with_relay(
    swarm: &mut libp2p::Swarm<Behaviour>,
    relay_id: PeerId,
) -> Result<(), String> {
    swarm
        .behaviour_mut()
        .rendezvous
        .register(freepath_namespace(), relay_id, None)
        .map_err(|e| e.to_string())
}

pub fn discover_from_relay(
    swarm: &mut libp2p::Swarm<Behaviour>,
    relay_id: PeerId,
    cookie: Option<rendezvous::Cookie>,
) {
    swarm
        .behaviour_mut()
        .rendezvous
        .discover(Some(freepath_namespace()), cookie, None, relay_id);
}

/// Dial each configured relay and return a map of relay PeerId → original dial Multiaddr.
/// The map is used later for circuit-address construction.
pub fn dial_relays(
    swarm: &mut libp2p::Swarm<Behaviour>,
    relay_multiaddrs: &[Multiaddr],
) -> HashMap<PeerId, Multiaddr> {
    let mut relay_peer_ids = HashMap::new();
    for addr in relay_multiaddrs {
        if let Some(pid) = extract_peer_id(addr) {
            relay_peer_ids.insert(pid, addr.clone());
        }
        if let Err(e) = swarm.dial(addr.clone()) {
            log::warn!("relay dial({addr}) failed: {e:?}");
        }
    }
    relay_peer_ids
}

/// Re-register with every currently-connected relay. Called on the rendezvous re-register tick.
pub fn tick_register_with_relays(
    swarm: &mut libp2p::Swarm<Behaviour>,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
) {
    for (&relay_id, _) in relay_peer_ids {
        if swarm.is_connected(&relay_id) {
            log::debug!("rendezvous: re-registering with {relay_id}");
            if let Err(e) = register_with_relay(swarm, relay_id) {
                log::warn!("rendezvous: re-register failed: {e}");
            }
        }
    }
}

/// Re-discover from every currently-connected relay. Called on the rendezvous discover tick.
pub fn tick_discover_from_relays(
    swarm: &mut libp2p::Swarm<Behaviour>,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &HashMap<PeerId, rendezvous::Cookie>,
) {
    for (&relay_id, _) in relay_peer_ids {
        if swarm.is_connected(&relay_id) {
            log::debug!("rendezvous: re-discovering from {relay_id}");
            discover_from_relay(swarm, relay_id, rendezvous_cookies.get(&relay_id).cloned());
        }
    }
}

#[derive(NetworkBehaviour)]
#[behaviour(prelude = "libp2p_swarm::derive_prelude")]
pub struct Behaviour {
    pub identify: identify::Behaviour,
    pub ping: ping::Behaviour,
    pub relay_client: relay::client::Behaviour,
    pub messaging: request_response::Behaviour<FreepathCodec>,
    pub rendezvous: rendezvous::client::Behaviour,
    pub autonat: autonat::v2::client::Behaviour,
    pub dcutr: dcutr::Behaviour,
    pub upnp: upnp::tokio::Behaviour,
}

pub fn build_swarm(
    keypair: identity::Keypair,
    peer_id: &str,
) -> Result<libp2p::Swarm<Behaviour>, Box<dyn std::error::Error + Send + Sync>> {
    // Use well-known public DNS resolvers (Quad9, Cloudflare, Google) on all platforms.
    // with_dns() reads /etc/resolv.conf which doesn't exist on iOS and may be unreliable
    // on Android. Merging all three providers gives resilience: if one is unreachable
    // the resolver tries the next.
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

    Ok(SwarmBuilder::with_existing_identity(keypair)
        .with_tokio()
        .with_tcp(
            tcp::Config::default(),
            noise::Config::new,
            yamux::Config::default,
        )?
        .with_quic()
        .with_dns_config(dns_config, libp2p_dns::ResolverOpts::default())
        .with_relay_client(noise::Config::new, yamux::Config::default)?
        .with_behaviour(|key, relay_client| {
            Ok(Behaviour {
                identify: identify::Behaviour::new(
                    identify::Config::new("/freepath/1.0.0".into(), key.public())
                        .with_agent_version(format!("freepath/{peer_id}")),
                ),
                ping: ping::Behaviour::default(),
                relay_client,
                messaging: request_response::Behaviour::with_codec(
                    FreepathCodec,
                    std::iter::once((FreepathProtocol, request_response::ProtocolSupport::Full)),
                    request_response::Config::default(),
                ),
                rendezvous: rendezvous::client::Behaviour::new(key.clone()),
                autonat: autonat::v2::client::Behaviour::new(
                    OsRng,
                    autonat::v2::client::Config::default(),
                ),
                dcutr: dcutr::Behaviour::new(key.public().to_peer_id()),
                upnp: upnp::tokio::Behaviour::default(),
            })
        })?
        .with_swarm_config(|cfg| cfg.with_idle_connection_timeout(Duration::from_secs(60)))
        .build())
}
