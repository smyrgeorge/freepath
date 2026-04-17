#[no_mangle]
pub extern "C" fn libp2p_set_log_callback(
    cb: unsafe extern "C" fn(
        level: u8,
        tag: *const u8,
        tag_len: usize,
        msg: *const u8,
        msg_len: usize,
    ),
) {
    crate::logging::init(cb);
}

use crate::core::{ContactCallback, EventCallback, LibP2pNode, RawLibP2pEvent};
use std::ffi::{c_char, c_void, CStr};
use std::sync::Arc;

/// Start a libp2p node. Returns an opaque Arc<LibP2pNode> pointer (never null on success).
/// On failure, returns null.
#[no_mangle]
pub extern "C" fn libp2p_start(
    node_id: *const c_char,
    sig_key_private: *const u8,
    sig_key_len: usize,
    listen_addr: *const c_char,
    relay_addrs: *const c_char,
    event_callback: *mut c_void,
    event_fun: unsafe extern "C" fn(*mut c_void, *mut RawLibP2pEvent),
    contact_callback: *mut c_void,
    contact_fun: unsafe extern "C" fn(*mut c_void, *const u8, usize) -> bool,
) -> *mut c_void {
    // All pointer arguments are required — a null here is a Kotlin/Swift caller bug.
    assert!(!node_id.is_null(), "libp2p_start: node_id must not be null");
    assert!(
        !sig_key_private.is_null(),
        "libp2p_start: sig_key_private must not be null"
    );
    assert!(
        !listen_addr.is_null(),
        "libp2p_start: listen_addr must not be null"
    );
    assert!(
        !relay_addrs.is_null(),
        "libp2p_start: relay_addrs must not be null"
    );

    let nid = unsafe { CStr::from_ptr(node_id) }
        .to_str()
        .expect("libp2p_start: node_id is not valid UTF-8");
    let key_bytes = unsafe { std::slice::from_raw_parts(sig_key_private, sig_key_len) };
    let addr = unsafe { CStr::from_ptr(listen_addr) }
        .to_str()
        .expect("libp2p_start: listen_addr is not valid UTF-8");
    let relays = unsafe { CStr::from_ptr(relay_addrs) }
        .to_str()
        .expect("libp2p_start: relay_addrs is not valid UTF-8");

    let event_cb = EventCallback {
        ptr: event_callback,
        fun: event_fun,
    };
    let contact_cb = ContactCallback {
        ptr: contact_callback,
        fun: contact_fun,
    };
    match crate::core::start_node(nid, key_bytes, addr, relays, event_cb, contact_cb) {
        Ok(arc) => Arc::into_raw(arc) as *mut c_void,
        Err(e) => {
            log::error!("start failed: {e}");
            std::ptr::null_mut()
        }
    }
}

/// Stop the node and release the Arc.
#[no_mangle]
pub extern "C" fn libp2p_stop(node: *mut c_void) {
    // A null node handle is a caller bug — stop() must only be called with the
    // pointer returned by libp2p_start().
    assert!(!node.is_null(), "libp2p_stop: node must not be null");
    let _ = unsafe { Arc::from_raw(node as *const LibP2pNode) };
}

/// Dial a peer by multiaddr string. Non-blocking: queues the command to the swarm.
#[no_mangle]
pub extern "C" fn libp2p_dial(node: *mut c_void, addr: *const c_char) {
    // Both arguments are required — null here is a caller bug.
    assert!(!node.is_null(), "libp2p_dial: node must not be null");
    assert!(!addr.is_null(), "libp2p_dial: addr must not be null");

    let addr_str = unsafe { CStr::from_ptr(addr) }
        .to_str()
        .expect("libp2p_dial: addr is not valid UTF-8")
        .to_owned();
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::Dial(addr_str));
    let _ = Arc::into_raw(arc); // prevent drop — we don't own this Arc
}

/// Send a request to a connected peer and expect a response.
/// `req_id` is a caller-assigned correlation ID; it will be echoed back in the
/// ResponseReceived (kind 7) or RequestFailed (kind 8) event. Non-blocking.
#[no_mangle]
pub extern "C" fn libp2p_send_request(
    node: *mut c_void,
    peer_id: *const c_char,
    req_id: u64,
    payload: *const u8,
    payload_len: usize,
) {
    assert!(
        !node.is_null(),
        "libp2p_send_request: node must not be null"
    );
    assert!(
        !peer_id.is_null(),
        "libp2p_send_request: peer_id must not be null"
    );
    assert!(
        payload_len == 0 || !payload.is_null(),
        "libp2p_send_request: payload must not be null when payload_len > 0"
    );

    let peer_id_str = unsafe { CStr::from_ptr(peer_id) }
        .to_str()
        .expect("libp2p_send_request: peer_id is not valid UTF-8")
        .to_owned();
    let bytes = if payload_len == 0 {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(payload, payload_len).to_vec() }
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendRequest {
            peer_id: peer_id_str,
            req_id,
            payload: bytes,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Send a response to an incoming request identified by `req_id`.
/// `req_id` must be the value delivered with the RequestReceived (kind 6) event.
/// Non-blocking.
#[no_mangle]
pub extern "C" fn libp2p_send_response(
    node: *mut c_void,
    req_id: u64,
    payload: *const u8,
    payload_len: usize,
) {
    assert!(
        !node.is_null(),
        "libp2p_send_response: node must not be null"
    );
    assert!(
        payload_len == 0 || !payload.is_null(),
        "libp2p_send_response: payload must not be null when payload_len > 0"
    );

    let bytes = if payload_len == 0 {
        Vec::new()
    } else {
        unsafe { std::slice::from_raw_parts(payload, payload_len).to_vec() }
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendResponse {
            req_id,
            payload: bytes,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Send an error response to an incoming request identified by `req_id`.
/// The original sender will receive a RequestFailed (kind 8) event with `error` as the description.
/// `req_id` must be the value delivered with the RequestReceived (kind 6) event. Non-blocking.
#[no_mangle]
pub extern "C" fn libp2p_send_response_failed(
    node: *mut c_void,
    req_id: u64,
    error: *const c_char,
) {
    assert!(
        !node.is_null(),
        "libp2p_send_response_failed: node must not be null"
    );
    assert!(
        !error.is_null(),
        "libp2p_send_response_failed: error must not be null"
    );

    let error_str = unsafe { CStr::from_ptr(error) }
        .to_str()
        .expect("libp2p_send_response_failed: error is not valid UTF-8")
        .to_owned();
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendResponseFailed {
            req_id,
            error: error_str,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Free a RawLibP2pEvent dispatched via event_fun. Must be called exactly once per event.
#[no_mangle]
pub extern "C" fn libp2p_event_free(event: *mut RawLibP2pEvent) {
    // A null event pointer is a caller bug — libp2p_event_free must only be called
    // with the pointer received in the event callback.
    assert!(
        !event.is_null(),
        "libp2p_event_free: event must not be null"
    );
    unsafe {
        let e = Box::from_raw(event);
        // All fields are optional — some event kinds (e.g. UpnpGatewayNotFound) carry no data.
        if !e.peer_id.is_null() {
            Vec::from_raw_parts(e.peer_id, e.peer_id_len, e.peer_id_len);
        }
        if !e.addr.is_null() {
            Vec::from_raw_parts(e.addr, e.addr_len, e.addr_len);
        }
        if !e.value.is_null() {
            Vec::from_raw_parts(e.value, e.value_len, e.value_len);
        }
        if !e.key.is_null() {
            Vec::from_raw_parts(e.key, e.key_len, e.key_len);
        }
    }
}
