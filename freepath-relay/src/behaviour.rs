use crate::messaging::{FreepathCodec, FreepathProtocol};
use libp2p::{autonat, identify, identity, ping, relay, rendezvous, request_response};
use libp2p_swarm::NetworkBehaviour;
use rand::rngs::OsRng;

#[derive(NetworkBehaviour)]
#[behaviour(prelude = "libp2p_swarm::derive_prelude")]
pub struct RelayBehaviour {
    pub identify: identify::Behaviour,
    pub ping: ping::Behaviour,
    pub relay: relay::Behaviour,
    pub messaging: request_response::Behaviour<FreepathCodec>,
    pub rendezvous: rendezvous::server::Behaviour,
    pub autonat: autonat::v2::server::Behaviour,
}

impl RelayBehaviour {
    pub fn new(key: &identity::Keypair) -> Self {
        let peer_id = key.public().to_peer_id();
        Self {
            identify: identify::Behaviour::new(
                identify::Config::new("/freepath/1.0.0".into(), key.public())
                    .with_agent_version(format!("freepath/{peer_id}")),
            ),
            ping: ping::Behaviour::default(),
            // TODO: configure relay.
            relay: relay::Behaviour::new(peer_id, relay::Config::default()),
            messaging: request_response::Behaviour::with_codec(
                FreepathCodec,
                std::iter::once((FreepathProtocol, request_response::ProtocolSupport::Full)),
                request_response::Config::default(),
            ),
            rendezvous: rendezvous::server::Behaviour::new(rendezvous::server::Config::default()),
            autonat: autonat::v2::server::Behaviour::new(OsRng),
        }
    }
}
