use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use libp2p::{identity, rendezvous, Multiaddr, PeerId};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::core::handlers::{handle_command, handle_swarm_event, NodeState};
use crate::core::swarm::{
    build_swarm, dial_relays, tick_discover_from_relays, tick_register_with_relays,
};
use crate::core::utils::{parse_multiaddrs, ContactCallback, EventCallback, SwarmCommand, RUNTIME};

pub struct LibP2pNode {
    pub swarm_tx: mpsc::Sender<SwarmCommand>,
}

impl Drop for LibP2pNode {
    fn drop(&mut self) {
        let _ = self.swarm_tx.try_send(SwarmCommand::Stop);
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
    let listen_addrs = parse_multiaddrs(listen_addr, "listen addr");
    if listen_addrs.is_empty() {
        panic!("listen_addr must contain at least one multiaddr");
    }

    // Parse relay multiaddrs (may be empty — rendezvous is optional).
    let relay_multiaddrs = parse_multiaddrs(relay_addrs, "relay addr");

    let runtime = RUNTIME.get_or_init(|| Runtime::new().expect("Tokio runtime init failed"));
    let (cmd_tx, cmd_rx) = mpsc::channel::<SwarmCommand>(64);

    runtime.spawn(run_node_task(
        keypair,
        peer_id,
        listen_addrs,
        relay_multiaddrs,
        event_cb,
        contact_cb,
        cmd_rx,
    ));

    Ok(Arc::new(LibP2pNode { swarm_tx: cmd_tx }))
}

async fn run_node_task(
    keypair: identity::Keypair,
    peer_id: String,
    listen_addrs: Vec<Multiaddr>,
    relay_multiaddrs: Vec<Multiaddr>,
    event_cb: EventCallback,
    contact_cb: ContactCallback,
    mut cmd_rx: mpsc::Receiver<SwarmCommand>,
) {
    let mut swarm = match build_swarm(keypair, &peer_id) {
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
    let relay_peer_ids = dial_relays(&mut swarm, &relay_multiaddrs);

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
    let mut circuit_listening: HashSet<PeerId> = HashSet::new();

    loop {
        tokio::select! {
            event = futures::StreamExt::next(&mut swarm) => {
                let Some(event) = event else {
                    // Swarm stream ended unexpectedly — drain in-flight outbound callers
                    // so they fail immediately instead of hanging forever.
                    state.drain_outbound(&local_peer_id, &event_cb);
                    break;
                };
                handle_swarm_event(
                    event,
                    &mut swarm,
                    &mut state,
                    &local_peer_id,
                    &event_cb,
                    &contact_cb,
                    &relay_peer_ids,
                    &mut rendezvous_cookies,
                    &mut circuit_listening,
                );
            }
            Some(cmd) = cmd_rx.recv() => {
                if handle_command(cmd, &mut swarm, &mut state, &local_peer_id, &event_cb) {
                    break;
                }
            }
            _ = rendezvous_interval.tick() => {
                tick_register_with_relays(&mut swarm, &relay_peer_ids);
            }
            _ = discover_interval.tick() => {
                tick_discover_from_relays(&mut swarm, &relay_peer_ids, &rendezvous_cookies);
            }
        }
    }
}
