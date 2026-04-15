use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use libp2p::{
    identify, identity, noise, ping, relay, rendezvous, request_response, tcp, yamux, Multiaddr,
    PeerId, SwarmBuilder,
};
use libp2p_swarm::{NetworkBehaviour, SwarmEvent};

use crate::core::event::RawLibP2pEvent;
use crate::core::messaging::{FreepathCodec, FreepathProtocol};
use crate::core::utils::{ContactCallback, EventCallback, SwarmCommand, RUNTIME};

pub struct LibP2pNode {
    pub swarm_tx: mpsc::Sender<SwarmCommand>,
}

impl Drop for LibP2pNode {
    fn drop(&mut self) {
        let _ = self.swarm_tx.try_send(SwarmCommand::Stop);
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
}

/// All mutable state owned exclusively by the swarm task.
/// Kept task-local so no Mutex is needed — nothing outside the task touches these.
struct NodeState {
    /// libp2p OutboundRequestId → caller-supplied req_id.
    outbound: HashMap<request_response::OutboundRequestId, u64>,
    /// req_id → ResponseChannel for incoming requests waiting for a response.
    inbound: HashMap<u64, request_response::ResponseChannel<Vec<u8>>>,
    /// InboundRequestId → req_id, used to clean up on InboundFailure.
    inbound_ids: HashMap<request_response::InboundRequestId, u64>,
    /// req_id → InboundRequestId, used for O(1) cleanup when sending a response.
    inbound_req_ids: HashMap<u64, request_response::InboundRequestId>,
    /// Monotonically increasing counter for assigning req_ids to incoming requests.
    req_counter: u64,
}

impl NodeState {
    fn new() -> Self {
        Self {
            outbound: HashMap::new(),
            inbound: HashMap::new(),
            inbound_ids: HashMap::new(),
            inbound_req_ids: HashMap::new(),
            req_counter: 0,
        }
    }

    /// Remove all inbound tracking for a req_id. Call when sending any response.
    fn remove_inbound(
        &mut self,
        req_id: u64,
    ) -> Option<request_response::ResponseChannel<Vec<u8>>> {
        if let Some(inbound_id) = self.inbound_req_ids.remove(&req_id) {
            self.inbound_ids.remove(&inbound_id);
        }
        self.inbound.remove(&req_id)
    }

    /// Remove all inbound tracking for an InboundRequestId. Call on InboundFailure.
    fn remove_inbound_by_id(
        &mut self,
        inbound_id: &request_response::InboundRequestId,
    ) -> Option<u64> {
        if let Some(req_id) = self.inbound_ids.remove(inbound_id) {
            self.inbound.remove(&req_id);
            self.inbound_req_ids.remove(&req_id);
            Some(req_id)
        } else {
            None
        }
    }
}

pub fn start_node(
    node_id: &str,
    sig_key_private_bytes: &[u8],
    listen_addr: &str,
    relay_addrs: &str,
    event_cb: EventCallback,
    contact_cb: ContactCallback,
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

    // Parse relay multiaddrs (may be empty — rendezvous is optional).
    let relay_multiaddrs: Vec<Multiaddr> = relay_addrs
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            s.parse()
                .unwrap_or_else(|e| panic!("invalid relay addr '{s}': {e:?}"))
        })
        .collect();

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

        let swarm_result = (|| -> Result<_, Box<dyn std::error::Error + Send + Sync>> {
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
                            std::iter::once((
                                FreepathProtocol,
                                request_response::ProtocolSupport::Full,
                            )),
                            request_response::Config::default(),
                        ),
                        rendezvous: rendezvous::client::Behaviour::new(key.clone()),
                    })
                })?
                .with_swarm_config(|cfg| cfg.with_idle_connection_timeout(Duration::from_secs(60)))
                .build())
        })();

        let mut swarm = match swarm_result {
            Ok(s) => s,
            Err(e) => {
                log::error!("failed to build swarm: {e:?}");
                return;
            }
        };

        for addr in listen_addrs {
            if let Err(e) = swarm.listen_on(addr.clone()) {
                log::error!("listen_on({addr}) failed: {e:?}");
                return;
            }
        }

        let local_peer_id = swarm.local_peer_id().to_string();
        let mut state = NodeState::new();

        // Extract relay PeerIds from multiaddrs and dial them.
        // Maps relay PeerId → original dial Multiaddr (used for circuit address construction).
        let mut relay_peer_ids: HashMap<PeerId, Multiaddr> = HashMap::new();
        for addr in &relay_multiaddrs {
            if let Some(pid) = addr
                .iter()
                .find_map(|p| match p {
                    libp2p::multiaddr::Protocol::P2p(id) => Some(id),
                    _ => None,
                })
            {
                relay_peer_ids.insert(pid, addr.clone());
            }
            if let Err(e) = swarm.dial(addr.clone()) {
                log::warn!("relay dial({addr}) failed: {e:?}");
            }
        }

        // Rendezvous re-register interval (well before default 2h TTL).
        let mut rendezvous_interval = tokio::time::interval(Duration::from_secs(30 * 60));
        // Skip the immediate first tick — registration happens on identify.
        rendezvous_interval.tick().await;

        // Rendezvous re-discover interval.
        let mut discover_interval = tokio::time::interval(Duration::from_secs(5 * 60));
        discover_interval.tick().await;

        // Cookie for progressive discovery per relay.
        let mut rendezvous_cookies: HashMap<PeerId, rendezvous::Cookie> = HashMap::new();
        // Track relays where we already started a circuit listener to avoid duplicates.
        let mut circuit_listening: std::collections::HashSet<PeerId> = std::collections::HashSet::new();

        loop {
            tokio::select! {
                event = futures::StreamExt::next(&mut swarm) => {
                    if handle_swarm_event(event, &mut swarm, &mut state, &local_peer_id, &event_cb, &contact_cb, &relay_peer_ids, &mut rendezvous_cookies, &mut circuit_listening) {
                        break;
                    }
                }
                Some(cmd) = cmd_rx.recv() => {
                    if handle_command(cmd, &mut swarm, &mut state, &local_peer_id, &event_cb) {
                        break;
                    }
                }
                _ = rendezvous_interval.tick() => {
                    for (&relay_id, _) in &relay_peer_ids {
                        if swarm.is_connected(&relay_id) {
                            log::debug!("rendezvous: re-registering with {relay_id}");
                            if let Err(e) = swarm.behaviour_mut().rendezvous.register(
                                rendezvous::Namespace::from_static("freepath"),
                                relay_id,
                                None,
                            ) {
                                log::warn!("rendezvous: re-register failed: {e}");
                            }
                        }
                    }
                }
                _ = discover_interval.tick() => {
                    for (&relay_id, _) in &relay_peer_ids {
                        if swarm.is_connected(&relay_id) {
                            log::debug!("rendezvous: re-discovering from {relay_id}");
                            swarm.behaviour_mut().rendezvous.discover(
                                Some(rendezvous::Namespace::from_static("freepath")),
                                rendezvous_cookies.get(&relay_id).cloned(),
                                None,
                                relay_id,
                            );
                        }
                    }
                }
            }
        }
    });

    let node = Arc::new(LibP2pNode { swarm_tx: cmd_tx });
    Ok(node)
}

/// Drains all in-flight outbound requests, firing `request_failed` for each.
/// Call on both graceful Stop and unexpected swarm stream termination.
fn drain_outbound(state: &mut NodeState, local_peer_id: &str, event_cb: &EventCallback) {
    for (_, req_id) in state.outbound.drain() {
        let raw = RawLibP2pEvent::request_failed(
            req_id,
            local_peer_id.to_owned(),
            String::new(),
            "node stopped".to_owned(),
        );
        unsafe { (event_cb.fun)(event_cb.ptr, raw) }
    }
}

/// Returns `true` if the swarm loop should stop.
fn handle_swarm_event(
    event: Option<SwarmEvent<BehaviourEvent>>,
    swarm: &mut libp2p::Swarm<Behaviour>,
    state: &mut NodeState,
    local_peer_id: &str,
    event_cb: &EventCallback,
    contact_cb: &ContactCallback,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &mut HashMap<PeerId, rendezvous::Cookie>,
    circuit_listening: &mut std::collections::HashSet<PeerId>,
) -> bool {
    match event {
        Some(SwarmEvent::Behaviour(BehaviourEvent::Messaging(ev))) => {
            handle_messaging(ev, swarm, state, local_peer_id, event_cb);
            false
        }
        Some(SwarmEvent::Behaviour(BehaviourEvent::Rendezvous(ev))) => {
            handle_rendezvous(ev, swarm, event_cb, relay_peer_ids, rendezvous_cookies);
            false
        }
        Some(SwarmEvent::Behaviour(BehaviourEvent::RelayClient(ev))) => {
            log::info!("relay client event: {ev:?}");
            false
        }
        // Only fire peer_disconnected when the last connection to the peer is gone.
        // libp2p can have multiple connections per peer; ConnectionClosed fires per-connection.
        Some(SwarmEvent::ConnectionClosed { peer_id, .. }) => {
            if !swarm.is_connected(&peer_id) {
                let raw = RawLibP2pEvent::peer_disconnected(peer_id.to_string());
                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
            }
            false
        }
        Some(ev) => {
            handle_event(
                ev,
                swarm,
                event_cb,
                contact_cb,
                relay_peer_ids,
                rendezvous_cookies,
                circuit_listening,
            );
            false
        }
        None => {
            // Swarm stream ended unexpectedly — drain in-flight outbound callers
            // so they fail immediately instead of hanging forever.
            drain_outbound(state, local_peer_id, event_cb);
            true
        }
    }
}

fn handle_messaging(
    ev: request_response::Event<Vec<u8>, Vec<u8>>,
    swarm: &mut libp2p::Swarm<Behaviour>,
    state: &mut NodeState,
    local_peer_id: &str,
    event_cb: &EventCallback,
) {
    match ev {
        request_response::Event::Message {
            peer,
            message:
                request_response::Message::Request {
                    request_id,
                    request,
                    channel,
                    ..
                },
            ..
        } => {
            // The first byte is a type discriminator written by the sender:
            //   0x01 = RPC request (hold channel, caller must send response)
            match request.first().copied() {
                Some(0x01) => {
                    // RPC request: assign a req_id and hold the channel.
                    state.req_counter += 1;
                    let req_id = state.req_counter;
                    state.inbound.insert(req_id, channel);
                    state.inbound_ids.insert(request_id, req_id);
                    state.inbound_req_ids.insert(req_id, request_id);
                    let payload = request[1..].to_vec();
                    let raw = RawLibP2pEvent::request_received(
                        req_id,
                        peer.to_string(),
                        local_peer_id.to_owned(),
                        payload,
                    );
                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                }
                _ => {
                    // Unknown or missing discriminator — auto-ack and discard.
                    log::warn!(
                        "received message with unknown type discriminator from {peer}; discarding"
                    );
                    let _ = swarm
                        .behaviour_mut()
                        .messaging
                        .send_response(channel, vec![]);
                }
            }
        }
        request_response::Event::Message {
            peer,
            message:
                request_response::Message::Response {
                    request_id,
                    response,
                },
            ..
        } => {
            if let Some(req_id) = state.outbound.remove(&request_id) {
                // Response discriminator:
                //   0x00 = success  → ResponseReceived
                //   0x01 = failure  → RequestFailed (error message in payload)
                let raw = match response.first().copied() {
                    Some(0x01) => {
                        let error = String::from_utf8_lossy(&response[1..]).into_owned();
                        RawLibP2pEvent::request_failed(
                            req_id,
                            local_peer_id.to_owned(),
                            peer.to_string(),
                            error,
                        )
                    }
                    _ => {
                        // 0x00 = explicit success; anything else treated as success for compatibility.
                        let payload = if response.is_empty() {
                            vec![]
                        } else {
                            response[1..].to_vec()
                        };
                        RawLibP2pEvent::response_received(
                            req_id,
                            local_peer_id.to_owned(),
                            peer.to_string(),
                            payload,
                        )
                    }
                };
                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
            }
        }
        request_response::Event::InboundFailure {
            request_id, error, ..
        } => {
            if let Some(req_id) = state.remove_inbound_by_id(&request_id) {
                log::warn!(
                    "inbound request req_id={req_id} failed before response was sent: {error}"
                );
            }
        }
        request_response::Event::OutboundFailure {
            peer,
            request_id,
            error,
            ..
        } => {
            if let Some(req_id) = state.outbound.remove(&request_id) {
                let raw = RawLibP2pEvent::request_failed(
                    req_id,
                    local_peer_id.to_owned(),
                    peer.to_string(),
                    error.to_string(),
                );
                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
            }
        }
        _ => {}
    }
}

fn handle_rendezvous(
    ev: rendezvous::client::Event,
    swarm: &mut libp2p::Swarm<Behaviour>,
    event_cb: &EventCallback,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &mut HashMap<PeerId, rendezvous::Cookie>,
) {
    match ev {
        rendezvous::client::Event::Registered {
            rendezvous_node,
            ttl,
            namespace,
        } => {
            log::info!(
                "rendezvous: registered in '{}' with {rendezvous_node} (ttl={ttl}s)",
                namespace
            );
            let raw = RawLibP2pEvent::relay_registered(
                rendezvous_node.to_string(),
                namespace.to_string(),
                ttl,
            );
            unsafe { (event_cb.fun)(event_cb.ptr, raw) }
        }
        rendezvous::client::Event::RegisterFailed {
            rendezvous_node,
            namespace,
            error,
        } => {
            log::warn!(
                "rendezvous: registration in '{}' with {rendezvous_node} failed: {error:?}",
                namespace
            );
            let raw = RawLibP2pEvent::relay_registration_failed(
                rendezvous_node.to_string(),
                format!("{error:?}"),
            );
            unsafe { (event_cb.fun)(event_cb.ptr, raw) }
        }
        rendezvous::client::Event::Discovered {
            rendezvous_node,
            registrations,
            cookie,
        } => {
            rendezvous_cookies.insert(rendezvous_node, cookie);
            let local_peer_id = *swarm.local_peer_id();
            for registration in registrations {
                let peer_id = registration.record.peer_id();
                if peer_id == local_peer_id || relay_peer_ids.contains_key(&peer_id) {
                    continue;
                }
                if swarm.is_connected(&peer_id) {
                    continue;
                }
                for addr in registration.record.addresses() {
                    log::debug!("rendezvous: discovered {peer_id} at {addr}, dialing");
                    if let Err(e) = swarm.dial(addr.clone()) {
                        log::warn!("rendezvous: dial {addr} failed: {e:?}");
                    }
                }
            }
        }
        rendezvous::client::Event::DiscoverFailed {
            rendezvous_node,
            namespace,
            error,
        } => {
            log::warn!(
                "rendezvous: discover '{}' from {rendezvous_node} failed: {error:?}",
                namespace.map(|n| n.to_string()).unwrap_or_default()
            );
        }
        rendezvous::client::Event::Expired { peer } => {
            log::debug!("rendezvous: registration expired for {peer}");
        }
    }
}

fn handle_event(
    ev: SwarmEvent<BehaviourEvent>,
    swarm: &mut libp2p::Swarm<Behaviour>,
    event_cb: &EventCallback,
    contact_cb: &ContactCallback,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &mut HashMap<PeerId, rendezvous::Cookie>,
    circuit_listening: &mut std::collections::HashSet<PeerId>,
) {
    match ev {
        SwarmEvent::ConnectionEstablished {
            peer_id, endpoint, ..
        } => {
            log::debug!(
                "Connection established: peer={} addr={}",
                peer_id,
                endpoint.get_remote_address()
            );
        }
        SwarmEvent::NewListenAddr {
            address,
            listener_id,
            ..
        } => {
            log::info!("NewListenAddr: {address} (listener_id={listener_id:?})");
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
        SwarmEvent::ListenerClosed {
            addresses,
            listener_id,
            reason,
            ..
        } => {
            log::warn!("ListenerClosed: listener_id={listener_id:?}, addresses={addresses:?}, reason={reason:?}");
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
                // If this is a relay peer, register and discover via rendezvous.
                if relay_peer_ids.contains_key(&peer_id) {
                    log::info!("rendezvous: identified relay {peer_id}, registering + discovering");
                    // The relay tells us our observed (external) address.
                    // Add it so the rendezvous client has an externally reachable address to advertise.
                    log::debug!(
                        "rendezvous: adding observed external addr {}",
                        info.observed_addr
                    );
                    swarm.add_external_address(info.observed_addr.clone());

                    // Listen on the relay's circuit address to make a reservation.
                    // Use the original dial multiaddr (externally reachable), not the relay's
                    // self-reported listen_addrs which may include localhost/link-local addresses.
                    // Only do this once per relay (identify fires per-connection: TCP + QUIC).
                    if !circuit_listening.contains(&peer_id) {
                        if let Some(relay_addr) = relay_peer_ids.get(&peer_id) {
                            circuit_listening.insert(peer_id);
                            // Strip trailing /p2p/<id> if present (already part of the dial addr),
                            // then append /p2p-circuit.
                            let base: Multiaddr = relay_addr
                                .iter()
                                .filter(|p| !matches!(p, libp2p::multiaddr::Protocol::P2p(_)))
                                .collect();
                            let circuit_addr = base
                                .with(libp2p::multiaddr::Protocol::P2p(peer_id))
                                .with(libp2p::multiaddr::Protocol::P2pCircuit);
                            log::info!("relay: listening on circuit address {circuit_addr}");
                            match swarm.listen_on(circuit_addr) {
                                Ok(id) => log::info!(
                                    "relay: circuit listener started, listener_id={id:?}"
                                ),
                                Err(e) => log::warn!("relay: listen_on circuit failed: {e}"),
                            }
                        }
                    }

                    // Emit RelayConnected event.
                    let raw = RawLibP2pEvent::relay_connected(peer_id.to_string());
                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                    if let Err(e) = swarm.behaviour_mut().rendezvous.register(
                        rendezvous::Namespace::from_static("freepath"),
                        peer_id,
                        None,
                    ) {
                        log::warn!("rendezvous: register failed: {e}");
                        let raw = RawLibP2pEvent::relay_registration_failed(
                            peer_id.to_string(),
                            e.to_string(),
                        );
                        unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                    }
                    swarm.behaviour_mut().rendezvous.discover(
                        Some(rendezvous::Namespace::from_static("freepath")),
                        rendezvous_cookies.get(&peer_id).cloned(),
                        None,
                        peer_id,
                    );
                    return;
                }

                // Fire peer_connected using the peer's first advertised listen address.
                let addr = info
                    .listen_addrs
                    .first()
                    .map(|a| a.to_string())
                    .unwrap_or_default();
                let raw = RawLibP2pEvent::peer_connected(peer_id.to_string(), addr);
                unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                // Fire peer_identified only if this peer is in the caller's contact database.
                let pid_str = peer_id.to_string();
                let pid_bytes = pid_str.as_bytes();
                let is_known = unsafe {
                    (contact_cb.fun)(contact_cb.ptr, pid_bytes.as_ptr(), pid_bytes.len())
                };
                if is_known {
                    let raw = RawLibP2pEvent::peer_identified(peer_id.to_string());
                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                }
            }
        }
        _ => {}
    }
}

/// Returns `true` if the swarm loop should stop.
fn handle_command(
    cmd: SwarmCommand,
    swarm: &mut libp2p::Swarm<Behaviour>,
    state: &mut NodeState,
    local_peer_id: &str,
    event_cb: &EventCallback,
) -> bool {
    match cmd {
        SwarmCommand::Stop => {
            // Notify all in-flight outbound callers so they fail immediately
            // instead of waiting for their timeout.
            drain_outbound(state, local_peer_id, event_cb);
            true
        }
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
            false
        }
        SwarmCommand::SendRequest {
            peer_id,
            req_id,
            payload,
        } => {
            match peer_id.parse::<libp2p::PeerId>() {
                Ok(pid) => {
                    // Prefix with 0x01 so the receiver knows this is an RPC request.
                    let mut framed = Vec::with_capacity(1 + payload.len());
                    framed.push(0x01);
                    framed.extend_from_slice(&payload);
                    let outbound_id = swarm.behaviour_mut().messaging.send_request(&pid, framed);
                    state.outbound.insert(outbound_id, req_id);
                }
                Err(e) => {
                    log::warn!("send_request: invalid PeerId '{peer_id}': {e}");
                    let raw = RawLibP2pEvent::request_failed(
                        req_id,
                        local_peer_id.to_owned(),
                        peer_id,
                        e.to_string(),
                    );
                    unsafe { (event_cb.fun)(event_cb.ptr, raw) }
                }
            }
            false
        }
        SwarmCommand::SendResponse { req_id, payload } => {
            if let Some(channel) = state.remove_inbound(req_id) {
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
            false
        }
        SwarmCommand::SendResponseFailed { req_id, error } => {
            if let Some(channel) = state.remove_inbound(req_id) {
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
            false
        }
    }
}
