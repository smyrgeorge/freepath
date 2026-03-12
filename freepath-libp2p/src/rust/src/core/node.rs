// freepath-libp2p/src/rust/src/core/node.rs

use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use libp2p::{
    identify, identity, noise, ping, request_response, tcp, yamux, Multiaddr, SwarmBuilder,
};
use libp2p_swarm::{NetworkBehaviour, SwarmEvent};

use crate::core::event::RawLibP2pEvent;
use crate::core::messaging::{FreepathCodec, FreepathProtocol};
use crate::core::utils::{EventCallback, SwarmCommand, RUNTIME};

// ── LibP2pNode ────────────────────────────────────────────────────────────────

pub struct LibP2pNode {
    pub swarm_tx: mpsc::Sender<SwarmCommand>,
}

impl Drop for LibP2pNode {
    fn drop(&mut self) {
        let _ = self.swarm_tx.try_send(SwarmCommand::Stop);
    }
}

// ── libp2p Behaviour ──────────────────────────────────────────────────────────

#[derive(NetworkBehaviour)]
#[behaviour(prelude = "libp2p_swarm::derive_prelude")]
pub struct Behaviour {
    pub identify: identify::Behaviour,
    pub ping: ping::Behaviour,
    pub messaging: request_response::Behaviour<FreepathCodec>,
}

// ── Node startup ──────────────────────────────────────────────────────────────

pub fn start_node(
    node_id: &str,
    sig_key_private_bytes: &[u8],
    listen_addr: &str,
    event_cb: EventCallback,
) -> Result<Arc<LibP2pNode>, String> {
    let mut key_bytes = sig_key_private_bytes.to_vec();
    let secret = identity::ed25519::SecretKey::try_from_bytes(&mut key_bytes)
        .map_err(|e| format!("invalid sigKeyPrivate: {e:?}"))?;
    let keypair = identity::Keypair::from(identity::ed25519::Keypair::from(secret));
    let peer_id = node_id.to_owned();
    let expected_peer_id = libp2p::PeerId::from_public_key(&keypair.public()).to_string();
    if peer_id != expected_peer_id {
        panic!("peer_id mismatch: provided={peer_id}, derived from keypair={expected_peer_id}");
    }

    // Parse each newline-separated multiaddr up front so we fail fast before spawning.
    let listen_addrs: Vec<Multiaddr> = listen_addr
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            s.parse()
                .unwrap_or_else(|e| panic!("invalid listen addr '{s}': {e:?}"))
        })
        .collect();

    if listen_addrs.is_empty() {
        panic!("listen_addr must contain at least one multiaddr");
    }

    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("Tokio runtime init failed"));
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<SwarmCommand>(64);

    runtime.spawn(async move {
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

        let mut swarm = SwarmBuilder::with_existing_identity(keypair)
            .with_tokio()
            .with_tcp(
                tcp::Config::default(),
                noise::Config::new,
                yamux::Config::default,
            )
            .unwrap()
            .with_quic()
            .with_dns_config(dns_config, libp2p_dns::ResolverOpts::default())
            .with_behaviour(|key| {
                Ok(Behaviour {
                    identify: identify::Behaviour::new(
                        identify::Config::new("/freepath/1.0.0".into(), key.public())
                            .with_agent_version(format!("freepath/{peer_id}")),
                    ),
                    ping: ping::Behaviour::default(),
                    messaging: request_response::Behaviour::with_codec(
                        FreepathCodec,
                        std::iter::once((FreepathProtocol, request_response::ProtocolSupport::Full)),
                        request_response::Config::default(),
                    ),
                })
            })
            .unwrap()
            .with_swarm_config(|cfg| cfg.with_idle_connection_timeout(Duration::from_secs(60)))
            .build();

        for addr in listen_addrs {
            swarm
                .listen_on(addr.clone())
                .unwrap_or_else(|e| panic!("listen_on({addr}) failed: {e:?}"));
        }

        let local_peer_id = swarm.local_peer_id().to_string();

        // Tracks outgoing requests: libp2p OutboundRequestId → caller-supplied req_id.
        let mut outbound: HashMap<request_response::OutboundRequestId, u64> = HashMap::new();
        // Tracks incoming requests waiting for a response: req_id → ResponseChannel.
        let mut inbound: HashMap<u64, request_response::ResponseChannel<Vec<u8>>> = HashMap::new();
        // Monotonically increasing counter for assigning req_ids to incoming requests.
        let mut inbound_req_counter: u64 = 0;

        loop {
            tokio::select! {
                event = futures::StreamExt::next(&mut swarm) => {
                    match event {
                        Some(SwarmEvent::Behaviour(BehaviourEvent::Messaging(
                            request_response::Event::Message {
                                peer,
                                message: request_response::Message::Request { request, channel, .. },
                                ..
                            }
                        ))) => {
                            // The first byte is a type discriminator written by the sender:
                            //   0x01 = RPC request (hold channel, caller must send response)
                            match request.first().copied() {
                                Some(0x01) => {
                                    // RPC request: assign a req_id and hold the channel.
                                    inbound_req_counter += 1;
                                    let req_id = inbound_req_counter;
                                    inbound.insert(req_id, channel);
                                    let payload = request[1..].to_vec();
                                    let raw = RawLibP2pEvent::request_received(req_id, peer.to_string(), local_peer_id.clone(), payload);
                                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                                }
                                _ => {
                                    // Unknown or missing discriminator — auto-ack and discard.
                                    log::warn!("received message with unknown type discriminator from {peer}; discarding");
                                    let _ = swarm.behaviour_mut().messaging.send_response(channel, vec![]);
                                }
                            }
                        }
                        Some(SwarmEvent::Behaviour(BehaviourEvent::Messaging(
                            request_response::Event::Message {
                                peer,
                                message: request_response::Message::Response { request_id, response },
                                ..
                            }
                        ))) => {
                            if let Some(req_id) = outbound.remove(&request_id) {
                                // Response discriminator:
                                //   0x00 = success  → ResponseReceived
                                //   0x01 = failure  → RequestFailed (error message in payload)
                                let raw = match response.first().copied() {
                                    Some(0x01) => {
                                        let error = String::from_utf8_lossy(&response[1..]).into_owned();
                                        RawLibP2pEvent::request_failed(req_id, local_peer_id.clone(), peer.to_string(), error)
                                    }
                                    _ => {
                                        // 0x00 = explicit success; anything else treated as success for compatibility.
                                        let payload = if response.is_empty() { vec![] } else { response[1..].to_vec() };
                                        RawLibP2pEvent::response_received(req_id, local_peer_id.clone(), peer.to_string(), payload)
                                    }
                                };
                                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                            }
                        }
                        Some(SwarmEvent::Behaviour(BehaviourEvent::Messaging(
                            request_response::Event::OutboundFailure { peer, request_id, error, .. }
                        ))) => {
                            if let Some(req_id) = outbound.remove(&request_id) {
                                let raw = RawLibP2pEvent::request_failed(req_id, local_peer_id.clone(), peer.to_string(), error.to_string());
                                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                            }
                        }
                        Some(ev) => handle_event(ev, &event_cb),
                        None => break,
                    }
                }
                Some(cmd) = cmd_rx.recv() => {
                    match cmd {
                        SwarmCommand::Stop => break,
                        SwarmCommand::Dial(addr_str) => {
                            match addr_str.parse::<Multiaddr>() {
                                Ok(addr) => {
                                    // Skip dialing our own listen addresses — the Kotlin layer
                                    // may discover and forward our own addr via mDNS before
                                    // we can filter it out on the Kotlin side.
                                    if swarm.listeners().any(|l| l == &addr) {
                                        log::debug!("dial '{addr_str}': skipping own listen address");
                                    } else if let Err(e) = swarm.dial(addr) {
                                        log::warn!("dial '{addr_str}' failed: {e:?}");
                                    }
                                }
                                Err(e) => log::warn!("dial: invalid multiaddr '{addr_str}': {e}"),
                            }
                        }
                        SwarmCommand::SendRequest { peer_id, req_id, payload } => {
                            match peer_id.parse::<libp2p::PeerId>() {
                                Ok(pid) => {
                                    // Prefix with 0x01 so the receiver knows this is an RPC request.
                                    let mut framed = Vec::with_capacity(1 + payload.len());
                                    framed.push(0x01);
                                    framed.extend_from_slice(&payload);
                                    let outbound_id = swarm
                                        .behaviour_mut()
                                        .messaging
                                        .send_request(&pid, framed);
                                    outbound.insert(outbound_id, req_id);
                                }
                                Err(e) => {
                                    log::warn!("send_request: invalid PeerId '{peer_id}': {e}");
                                    let raw = RawLibP2pEvent::request_failed(req_id, local_peer_id.clone(), peer_id.clone(), e.to_string());
                                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                                }
                            }
                        }
                        SwarmCommand::SendResponse { req_id, payload } => {
                            if let Some(channel) = inbound.remove(&req_id) {
                                // Prefix with 0x00 so the receiver knows this is a success response.
                                let mut framed = Vec::with_capacity(1 + payload.len());
                                framed.push(0x00);
                                framed.extend_from_slice(&payload);
                                if let Err(e) = swarm
                                    .behaviour_mut()
                                    .messaging
                                    .send_response(channel, framed)
                                {
                                    log::warn!("send_response req_id={req_id}: {e:?}");
                                }
                            } else {
                                log::warn!("send_response: unknown req_id={req_id}");
                            }
                        }
                        SwarmCommand::SendResponseFailed { req_id, error } => {
                            if let Some(channel) = inbound.remove(&req_id) {
                                // Prefix with 0x01 so the receiver fires RequestFailed instead of ResponseReceived.
                                let err_bytes = error.into_bytes();
                                let mut framed = Vec::with_capacity(1 + err_bytes.len());
                                framed.push(0x01);
                                framed.extend_from_slice(&err_bytes);
                                if let Err(e) = swarm
                                    .behaviour_mut()
                                    .messaging
                                    .send_response(channel, framed)
                                {
                                    log::warn!("send_response_failed req_id={req_id}: {e:?}");
                                }
                            } else {
                                log::warn!("send_response_failed: unknown req_id={req_id}");
                            }
                        }
                    }
                }
            }
        }
    });

    let node = Arc::new(LibP2pNode { swarm_tx: cmd_tx });
    Ok(node)
}

fn handle_event(ev: SwarmEvent<BehaviourEvent>, event_cb: &EventCallback) {
    match ev {
        SwarmEvent::ConnectionEstablished {
            peer_id, endpoint, ..
        } => {
            let addr = endpoint.get_remote_address().to_string();
            let raw = RawLibP2pEvent::peer_connected(peer_id.to_string(), addr);
            unsafe { (event_cb.fun)(event_cb.ptr, raw) }
        }
        SwarmEvent::ConnectionClosed { peer_id, .. } => {
            let raw = RawLibP2pEvent::peer_disconnected(peer_id.to_string());
            unsafe { (event_cb.fun)(event_cb.ptr, raw) }
        }
        SwarmEvent::NewListenAddr { address, .. } => {
            let raw = RawLibP2pEvent::new_listen_addr(address.to_string());
            unsafe { (event_cb.fun)(event_cb.ptr, raw) }
        }
        SwarmEvent::OutgoingConnectionError { peer_id, error, .. } => {
            log::debug!("Outgoing connection error (peer={:?}): {}", peer_id, error);
        }
        SwarmEvent::IncomingConnectionError { error, .. } => {
            log::warn!("TODO: Incoming connection error: {}", error);
        }
        SwarmEvent::ExpiredListenAddr { address, .. } => {
            log::warn!("TODO: Expired listen addr: {}", address);
        }
        SwarmEvent::ListenerClosed { addresses, .. } => {
            log::warn!("TODO: Expired listen addrs: {:?}", addresses);
        }
        SwarmEvent::ListenerError { error, .. } => {
            log::error!("TODO: Listener error: {}", error);
        }
        SwarmEvent::NewExternalAddrCandidate { address, .. } => {
            log::debug!("New external addr candidate: {}", address);
        }
        SwarmEvent::ExternalAddrConfirmed { address, .. } => {
            log::info!("TODO: External addr confirmed: {}", address);
        }
        SwarmEvent::ExternalAddrExpired { address, .. } => {
            log::info!("TODO: External addr expired: {}", address);
        }
        SwarmEvent::NewExternalAddrOfPeer { peer_id, address } => {
            log::debug!("New external addr of peer {}: {}", peer_id, address);
        }
        SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received {
            peer_id,
            info,
            ..
        })) => {
            if info.agent_version.starts_with("freepath/") {
                let raw = RawLibP2pEvent::peer_identified(peer_id.to_string());
                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
            }
        }
        _ => {}
    }
}
