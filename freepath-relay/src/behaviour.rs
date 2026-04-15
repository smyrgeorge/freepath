use crate::messaging::{FreepathCodec, FreepathProtocol};
use libp2p::{identify, identity, ping, rendezvous, request_response};
use libp2p_swarm::NetworkBehaviour;

#[derive(NetworkBehaviour)]
#[behaviour(prelude = "libp2p_swarm::derive_prelude")]
pub struct RelayBehaviour {
    pub identify: identify::Behaviour,
    pub ping: ping::Behaviour,
    pub messaging: request_response::Behaviour<FreepathCodec>,
    pub rendezvous: rendezvous::server::Behaviour,
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
            messaging: request_response::Behaviour::with_codec(
                FreepathCodec,
                std::iter::once((FreepathProtocol, request_response::ProtocolSupport::Full)),
                request_response::Config::default(),
            ),
            rendezvous: rendezvous::server::Behaviour::new(rendezvous::server::Config::default()),
        }
    }
}
