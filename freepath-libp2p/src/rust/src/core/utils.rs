use std::ffi::c_void;
use std::sync::OnceLock;
use tokio::runtime::Runtime;

use crate::core::event::RawLibP2pEvent;

pub static RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub struct EventCallback {
    pub ptr: *mut c_void,
    pub fun: unsafe extern "C" fn(*mut c_void, *mut RawLibP2pEvent),
}
unsafe impl Send for EventCallback {}
unsafe impl Sync for EventCallback {}

/// Synchronous callback invoked from the swarm loop to check whether a peer is a known contact.
/// The function receives the peer_id as a UTF-8 byte slice and returns `true` if the peer is
/// present in the caller's contact database.
pub struct ContactCallback {
    pub ptr: *mut c_void,
    pub fun: unsafe extern "C" fn(*mut c_void, *const u8, usize) -> bool,
}
unsafe impl Send for ContactCallback {}
unsafe impl Sync for ContactCallback {}

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
