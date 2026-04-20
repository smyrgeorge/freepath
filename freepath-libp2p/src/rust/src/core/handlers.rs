use std::collections::{HashMap, HashSet};

use libp2p::{autonat, dcutr, identify, rendezvous, request_response, upnp, Multiaddr, PeerId};
use libp2p_swarm::SwarmEvent;

use crate::core::event::RawLibP2pEvent;
use crate::core::messaging::{frame, REQ_TAG, RESP_ERR, RESP_OK};
use crate::core::swarm::{
    circuit_addr_for, discover_from_relay, is_circuit_addr, register_with_relay, Behaviour,
    BehaviourEvent,
};
use crate::core::utils::{ContactCallback, EventCallback, SwarmCommand};

/// All mutable state owned exclusively by the swarm task.
/// Kept task-local so no Mutex is needed — nothing outside the task touches these.
pub(crate) struct NodeState {
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
    pub(crate) fn new() -> Self {
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

    /// Drains all in-flight outbound requests, firing `request_failed` for each.
    /// Call on both graceful Stop and unexpected swarm stream termination.
    pub(crate) fn drain_outbound(&mut self, local_peer_id: &str, event_cb: &EventCallback) {
        for (_, req_id) in self.outbound.drain() {
            let raw = RawLibP2pEvent::request_failed(
                req_id,
                local_peer_id.to_owned(),
                String::new(),
                "node stopped".to_owned(),
            );
            event_cb.emit(raw);
        }
    }
}

pub(crate) fn handle_swarm_event(
    event: SwarmEvent<BehaviourEvent>,
    swarm: &mut libp2p::Swarm<Behaviour>,
    state: &mut NodeState,
    local_peer_id: &str,
    event_cb: &EventCallback,
    contact_cb: &ContactCallback,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &mut HashMap<PeerId, rendezvous::Cookie>,
    circuit_listening: &mut HashSet<PeerId>,
) {
    match event {
        SwarmEvent::Behaviour(BehaviourEvent::Messaging(ev)) => {
            handle_messaging(ev, swarm, state, local_peer_id, event_cb);
        }
        SwarmEvent::Behaviour(BehaviourEvent::Rendezvous(ev)) => {
            handle_rendezvous(ev, swarm, event_cb, relay_peer_ids, rendezvous_cookies);
        }
        SwarmEvent::Behaviour(BehaviourEvent::Upnp(ev)) => handle_upnp(ev, event_cb),
        SwarmEvent::Behaviour(BehaviourEvent::Dcutr(ev)) => handle_dcutr(ev),
        SwarmEvent::Behaviour(BehaviourEvent::Autonat(ev)) => handle_autonat(ev, event_cb),
        SwarmEvent::ExternalAddrConfirmed { address } => {
            log::info!("ExternalAddrConfirmed: {address}");
        }
        SwarmEvent::ExternalAddrExpired { address } => {
            log::info!("ExternalAddrExpired: {address}");
        }
        // Only fire peer_disconnected when the last connection to the peer is gone.
        // libp2p can have multiple connections per peer; ConnectionClosed fires per-connection.
        SwarmEvent::ConnectionClosed { peer_id, .. } => {
            if !swarm.is_connected(&peer_id) {
                // If this was a relay, clear the circuit-listener marker so the next
                // identify after reconnect re-establishes the circuit reservation.
                if relay_peer_ids.contains_key(&peer_id) {
                    circuit_listening.remove(&peer_id);
                }
                let raw = RawLibP2pEvent::peer_disconnected(peer_id.to_string());
                event_cb.emit(raw);
            }
        }
        ev => {
            handle_event(
                ev,
                swarm,
                event_cb,
                contact_cb,
                relay_peer_ids,
                rendezvous_cookies,
                circuit_listening,
            );
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
            // The first byte is a type discriminator written by the sender.
            match request.first().copied() {
                Some(REQ_TAG) => {
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
                    event_cb.emit(raw);
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
                let raw = match response.first().copied() {
                    Some(RESP_ERR) => {
                        let error = String::from_utf8_lossy(&response[1..]).into_owned();
                        RawLibP2pEvent::request_failed(
                            req_id,
                            local_peer_id.to_owned(),
                            peer.to_string(),
                            error,
                        )
                    }
                    _ => {
                        // RESP_OK or empty — treat as success for compatibility.
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
                event_cb.emit(raw);
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
                event_cb.emit(raw);
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
            event_cb.emit(raw);
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
            event_cb.emit(raw);
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

fn handle_upnp(ev: upnp::Event, event_cb: &EventCallback) {
    match ev {
        upnp::Event::GatewayNotFound => {
            log::info!("upnp: gateway not found");
            event_cb.emit(RawLibP2pEvent::upnp_gateway_not_found());
        }
        upnp::Event::NonRoutableGateway => {
            log::info!("upnp: gateway is not routable");
            event_cb.emit(RawLibP2pEvent::upnp_non_routable_gateway());
        }
        upnp::Event::NewExternalAddr(addr) => {
            log::info!("upnp: new external addr {addr}");
            event_cb.emit(RawLibP2pEvent::upnp_new_external_addr(addr.to_string()));
        }
        upnp::Event::ExpiredExternalAddr(addr) => {
            log::info!("upnp: expired external addr {addr}");
            event_cb.emit(RawLibP2pEvent::upnp_expired_external_addr(addr.to_string()));
        }
    }
}

fn handle_dcutr(ev: dcutr::Event) {
    match ev.result {
        Ok(_) => log::info!(
            "dcutr: direct connection upgrade succeeded with {}",
            ev.remote_peer_id
        ),
        Err(ref e) => log::warn!(
            "dcutr: direct connection upgrade failed with {}: {e:?}",
            ev.remote_peer_id
        ),
    }
}

fn handle_autonat(ev: autonat::v2::client::Event, event_cb: &EventCallback) {
    // Circuit-relayed addresses are not directly reachable — skip them.
    // Autonat still wastes a probe, but we don't surface the noise to UI.
    if is_circuit_addr(&ev.tested_addr) {
        log::debug!("autonat: skipping circuit addr {}", ev.tested_addr);
        return;
    }
    match ev.result {
        Ok(()) => {
            log::info!(
                "autonat: {} verified reachable via {} ({} bytes)",
                ev.tested_addr,
                ev.server,
                ev.bytes_sent
            );
            event_cb.emit(RawLibP2pEvent::autonat_probe_succeeded(
                ev.tested_addr.to_string(),
                ev.server.to_string(),
            ));
        }
        Err(ref e) => {
            event_cb.emit(RawLibP2pEvent::autonat_probe_failed(
                ev.tested_addr.to_string(),
                ev.server.to_string(),
                format!("{e:?}"),
            ));
        }
    }
}

fn handle_event(
    ev: SwarmEvent<BehaviourEvent>,
    swarm: &mut libp2p::Swarm<Behaviour>,
    event_cb: &EventCallback,
    contact_cb: &ContactCallback,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &HashMap<PeerId, rendezvous::Cookie>,
    circuit_listening: &mut HashSet<PeerId>,
) {
    match ev {
        SwarmEvent::NewListenAddr {
            address,
            listener_id,
            ..
        } => {
            log::info!("NewListenAddr: {address} (listener_id={listener_id:?})");
            let raw = RawLibP2pEvent::new_listen_addr(address.to_string());
            event_cb.emit(raw);

            // When a circuit relay address appears, re-register with rendezvous
            // so peers can discover us at the reachable circuit address.
            if is_circuit_addr(&address) {
                log::info!("relay: circuit address ready, re-registering with rendezvous");
                for (&relay_id, _) in relay_peer_ids {
                    if swarm.is_connected(&relay_id) {
                        if let Err(e) = register_with_relay(swarm, relay_id) {
                            log::warn!("rendezvous: re-register after circuit failed: {e}");
                        }
                    }
                }
            }
        }
        SwarmEvent::ListenerClosed {
            addresses,
            listener_id,
            reason,
            ..
        } => {
            log::warn!("ListenerClosed: listener_id={listener_id:?}, addresses={addresses:?}, reason={reason:?}");
        }
        SwarmEvent::Behaviour(BehaviourEvent::Identify(identify::Event::Received {
            peer_id,
            info,
            ..
        })) => {
            if !info.agent_version.starts_with("freepath/") {
                return;
            }
            if relay_peer_ids.contains_key(&peer_id) {
                handle_relay_identified(
                    swarm,
                    peer_id,
                    &info,
                    relay_peer_ids,
                    rendezvous_cookies,
                    circuit_listening,
                    event_cb,
                );
            } else {
                handle_peer_identified(peer_id, &info, event_cb, contact_cb);
            }
        }
        _ => {}
    }
}

/// Handles Identify::Received for a non-relay peer: emits PeerConnected and, if the
/// peer is in the caller's contact database, PeerIdentified.
fn handle_peer_identified(
    peer_id: PeerId,
    info: &identify::Info,
    event_cb: &EventCallback,
    contact_cb: &ContactCallback,
) {
    let addr = info
        .listen_addrs
        .first()
        .map(|a| a.to_string())
        .unwrap_or_default();
    event_cb.emit(RawLibP2pEvent::peer_connected(peer_id.to_string(), addr));
    if contact_cb.is_known(&peer_id) {
        event_cb.emit(RawLibP2pEvent::peer_identified(peer_id.to_string()));
    }
}

/// Handles Identify::Received for a known relay peer: installs the circuit listener
/// (idempotently), emits RelayConnected, registers + discovers via rendezvous.
fn handle_relay_identified(
    swarm: &mut libp2p::Swarm<Behaviour>,
    peer_id: PeerId,
    info: &identify::Info,
    relay_peer_ids: &HashMap<PeerId, Multiaddr>,
    rendezvous_cookies: &HashMap<PeerId, rendezvous::Cookie>,
    circuit_listening: &mut HashSet<PeerId>,
    event_cb: &EventCallback,
) {
    log::info!("rendezvous: identified relay {peer_id}, registering + discovering");
    log::info!("relay {peer_id} advertises protocols: {:?}", info.protocols);
    // Note: identify emits ToSwarm::NewExternalAddrCandidate for info.observed_addr
    // automatically. Do NOT call swarm.add_external_address here — that bypasses
    // the candidate path and prevents autonat v2 from probing.
    // Reachable externals (circuit reservation, autonat-confirmed observed) are
    // promoted to confirmed external addrs automatically by the swarm.

    // Listen on the relay's circuit address to make a reservation.
    // Use the original dial multiaddr (externally reachable), not the relay's
    // self-reported listen_addrs which may include localhost/link-local addresses.
    // Only do this once per relay (identify fires per-connection: TCP + QUIC).
    if !circuit_listening.contains(&peer_id) {
        if let Some(relay_addr) = relay_peer_ids.get(&peer_id) {
            let circuit_addr = circuit_addr_for(relay_addr, peer_id);
            log::info!("relay: listening on circuit address {circuit_addr}");
            match swarm.listen_on(circuit_addr) {
                Ok(id) => {
                    circuit_listening.insert(peer_id);
                    log::info!("relay: circuit listener started, listener_id={id:?}");
                }
                Err(e) => log::warn!("relay: listen_on circuit failed: {e}"),
            }
        }
    }

    let raw = RawLibP2pEvent::relay_connected(peer_id.to_string());
    event_cb.emit(raw);
    if let Err(e) = register_with_relay(swarm, peer_id) {
        log::warn!("rendezvous: register failed: {e}");
        let raw = RawLibP2pEvent::relay_registration_failed(peer_id.to_string(), e);
        event_cb.emit(raw);
    }
    discover_from_relay(swarm, peer_id, rendezvous_cookies.get(&peer_id).cloned());
}

/// Returns `true` if the swarm loop should stop.
pub(crate) fn handle_command(
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
            state.drain_outbound(local_peer_id, event_cb);
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
                    let outbound_id = swarm
                        .behaviour_mut()
                        .messaging
                        .send_request(&pid, frame(REQ_TAG, &payload));
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
                    event_cb.emit(raw);
                }
            }
            false
        }
        SwarmCommand::SendResponse { req_id, payload } => {
            if let Some(channel) = state.remove_inbound(req_id) {
                if let Err(e) = swarm
                    .behaviour_mut()
                    .messaging
                    .send_response(channel, frame(RESP_OK, &payload))
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
                if let Err(e) = swarm
                    .behaviour_mut()
                    .messaging
                    .send_response(channel, frame(RESP_ERR, error.as_bytes()))
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
