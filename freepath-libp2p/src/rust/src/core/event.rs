// freepath-libp2p/src/rust/src/core/event.rs

/// Event kinds:
///   0 = PeerConnected    (peer_id + addr)
///   1 = PeerDisconnected (peer_id)
///   3 = NewListenAddr    (addr stored in peer_id field)
///   4 = PeerIdentified   (peer_id)
///   6 = RequestReceived  (req_id, peer_id=senderId, addr=recipientId, value=payload)
///   7 = ResponseReceived (req_id, peer_id=senderId, addr=recipientId, value=payload)
///   8 = RequestFailed    (req_id, peer_id=senderId, addr=recipientId, value=error bytes)
#[repr(C)]
pub struct RawLibP2pEvent {
    pub kind: u8,
    pub req_id: u64,
    pub peer_id: *mut u8,
    pub peer_id_len: usize,
    pub addr: *mut u8,
    pub addr_len: usize,
    pub value: *mut u8,
    pub value_len: usize,
    pub key: *mut u8,
    pub key_len: usize,
}

impl RawLibP2pEvent {
    pub fn peer_connected(peer_id: String, addr: String) -> *mut Self {
        let pid = peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        let a = addr.into_bytes();
        let a_len = a.len();
        let a_ptr = Box::into_raw(a.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 0,
            req_id: 0,
            peer_id: pid_ptr,
            peer_id_len: pid_len,
            addr: a_ptr,
            addr_len: a_len,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    pub fn peer_disconnected(peer_id: String) -> *mut Self {
        let pid = peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 1,
            req_id: 0,
            peer_id: pid_ptr,
            peer_id_len: pid_len,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    pub fn new_listen_addr(addr: String) -> *mut Self {
        let bytes = addr.into_bytes();
        let len = bytes.len();
        let ptr = Box::into_raw(bytes.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 3,
            req_id: 0,
            peer_id: ptr,
            peer_id_len: len, // addr stored in peer_id field
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    pub fn peer_identified(peer_id: String) -> *mut Self {
        let pid = peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 4,
            req_id: 0,
            peer_id: pid_ptr,
            peer_id_len: pid_len,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// Incoming request from a peer that requires a response.
    /// `req_id` is assigned by the Rust swarm loop; the caller must pass it back via SendResponse.
    /// `sender_id` = libp2p PeerId of the peer who sent the request (stored in peer_id field).
    /// `recipient_id` = local node's PeerId (stored in addr field).
    pub fn request_received(
        req_id: u64,
        sender_id: String,
        recipient_id: String,
        payload: Vec<u8>,
    ) -> *mut Self {
        let sid = sender_id.into_bytes();
        let sid_len = sid.len();
        let sid_ptr = Box::into_raw(sid.into_boxed_slice()) as *mut u8;
        let rid = recipient_id.into_bytes();
        let rid_len = rid.len();
        let rid_ptr = Box::into_raw(rid.into_boxed_slice()) as *mut u8;
        let val_len = payload.len();
        let val_ptr = Box::into_raw(payload.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 6,
            req_id,
            peer_id: sid_ptr,
            peer_id_len: sid_len,
            addr: rid_ptr,
            addr_len: rid_len,
            value: val_ptr,
            value_len: val_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// Response to one of our outgoing requests.
    /// `req_id` matches the value supplied to SendRequest.
    /// `sender_id` = local node's PeerId (original request sender, stored in peer_id field).
    /// `recipient_id` = libp2p PeerId of the peer who responded (stored in addr field).
    pub fn response_received(
        req_id: u64,
        sender_id: String,
        recipient_id: String,
        payload: Vec<u8>,
    ) -> *mut Self {
        let sid = sender_id.into_bytes();
        let sid_len = sid.len();
        let sid_ptr = Box::into_raw(sid.into_boxed_slice()) as *mut u8;
        let rid = recipient_id.into_bytes();
        let rid_len = rid.len();
        let rid_ptr = Box::into_raw(rid.into_boxed_slice()) as *mut u8;
        let val_len = payload.len();
        let val_ptr = Box::into_raw(payload.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 7,
            req_id,
            peer_id: sid_ptr,
            peer_id_len: sid_len,
            addr: rid_ptr,
            addr_len: rid_len,
            value: val_ptr,
            value_len: val_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// Our outgoing request failed (peer disconnected, timeout, etc.).
    /// `req_id` matches the value supplied to SendRequest.
    /// `sender_id` = local node's PeerId (stored in peer_id field).
    /// `recipient_id` = PeerId of the target peer (stored in addr field).
    /// `error` is a human-readable description stored in the value field.
    pub fn request_failed(
        req_id: u64,
        sender_id: String,
        recipient_id: String,
        error: String,
    ) -> *mut Self {
        let sid = sender_id.into_bytes();
        let sid_len = sid.len();
        let sid_ptr = Box::into_raw(sid.into_boxed_slice()) as *mut u8;
        let rid = recipient_id.into_bytes();
        let rid_len = rid.len();
        let rid_ptr = Box::into_raw(rid.into_boxed_slice()) as *mut u8;
        let err = error.into_bytes();
        let err_len = err.len();
        let err_ptr = Box::into_raw(err.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 8,
            req_id,
            peer_id: sid_ptr,
            peer_id_len: sid_len,
            addr: rid_ptr,
            addr_len: rid_len,
            value: err_ptr,
            value_len: err_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }
}
