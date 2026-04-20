use std::ffi::c_void;
use std::sync::OnceLock;
use tokio::runtime::Runtime;

use libp2p::Multiaddr;

use crate::core::event::RawLibP2pEvent;

pub static RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub struct EventCallback {
    pub ptr: *mut c_void,
    pub fun: unsafe extern "C" fn(*mut c_void, *mut RawLibP2pEvent),
}
unsafe impl Send for EventCallback {}
unsafe impl Sync for EventCallback {}

impl EventCallback {
    pub fn emit(&self, event: *mut RawLibP2pEvent) {
        unsafe { (self.fun)(self.ptr, event) }
    }
}

/// Synchronous callback invoked from the swarm loop to check whether a peer is a known contact.
/// The function receives the peer_id as a UTF-8 byte slice and returns `true` if the peer is
/// present in the caller's contact database.
pub struct ContactCallback {
    pub ptr: *mut c_void,
    pub fun: unsafe extern "C" fn(*mut c_void, *const u8, usize) -> bool,
}
unsafe impl Send for ContactCallback {}
unsafe impl Sync for ContactCallback {}

impl ContactCallback {
    pub fn is_known(&self, peer_id: &libp2p::PeerId) -> bool {
        let pid_str = peer_id.to_string();
        let bytes = pid_str.as_bytes();
        unsafe { (self.fun)(self.ptr, bytes.as_ptr(), bytes.len()) }
    }
}

/// Parse a newline-separated list of multiaddrs. Panics with `label` on invalid input.
pub fn parse_multiaddrs(input: &str, label: &str) -> Vec<Multiaddr> {
    input
        .lines()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            s.parse()
                .unwrap_or_else(|e| panic!("invalid {label} '{s}': {e:?}"))
        })
        .collect()
}

pub enum SwarmCommand {
    Stop,
    /// Dial a peer by multiaddr string (parsed inside the swarm loop).
    Dial(String),
    /// Send a request and expect a response.
    /// `req_id` is a caller-assigned correlation ID that will be echoed back in
    /// ResponseReceived / RequestFailed events so the caller can match them.
    SendRequest {
        peer_id: String,
        req_id: u64,
        payload: Vec<u8>,
    },
    /// Send a successful response to an incoming request identified by `req_id`.
    /// `req_id` must be the value delivered with the RequestReceived event.
    SendResponse {
        req_id: u64,
        payload: Vec<u8>,
    },
    /// Send an error response to an incoming request identified by `req_id`.
    /// The sender will receive a RequestFailed event with `error` as the description.
    SendResponseFailed {
        req_id: u64,
        error: String,
    },
}
