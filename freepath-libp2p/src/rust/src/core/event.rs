/// Event kinds:
///   0 = PeerConnected             (peer_id + addr)
///   1 = PeerDisconnected          (peer_id)
///   3 = NewListenAddr             (addr stored in peer_id field)
///   4 = PeerIdentified            (peer_id)
///   6 = RequestReceived           (req_id, peer_id=senderId, addr=recipientId, value=payload)
///   7 = ResponseReceived          (req_id, peer_id=senderId, addr=recipientId, value=payload)
///   8 = RequestFailed             (req_id, peer_id=senderId, addr=recipientId, value=error bytes)
///   9 = RelayConnected            (peer_id=relayPeerId)
///  10 = RelayRegistered           (peer_id=relayPeerId, addr=namespace, value=ttl as decimal string)
///  11 = RelayRegistrationFailed   (peer_id=relayPeerId, value=error string)
///  14 = AutonatProbeFailed        (peer_id=testedAddr, addr=serverPeerId, value=error string)
///  15 = AutonatProbeSucceeded     (peer_id=testedAddr, addr=serverPeerId)
///  16 = UpnpGatewayNotFound       (no payload)
///  17 = UpnpNonRoutableGateway    (no payload)
///  18 = UpnpNewExternalAddr       (peer_id=addr)
///  19 = UpnpExpiredExternalAddr   (peer_id=addr)
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

    /// Relay peer was identified (connection + identify completed).
    /// `relay_peer_id` = PeerId of the relay (stored in peer_id field).
    pub fn relay_connected(relay_peer_id: String) -> *mut Self {
        let pid = relay_peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 9,
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

    /// Successfully registered with a rendezvous relay.
    /// `relay_peer_id` = PeerId of the relay (stored in peer_id field).
    /// `namespace` = rendezvous namespace (stored in addr field).
    /// `ttl` = registration TTL in seconds as a decimal string (stored in value field).
    pub fn relay_registered(relay_peer_id: String, namespace: String, ttl: u64) -> *mut Self {
        let pid = relay_peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        let ns = namespace.into_bytes();
        let ns_len = ns.len();
        let ns_ptr = Box::into_raw(ns.into_boxed_slice()) as *mut u8;
        let ttl_str = ttl.to_string().into_bytes();
        let ttl_len = ttl_str.len();
        let ttl_ptr = Box::into_raw(ttl_str.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 10,
            req_id: 0,
            peer_id: pid_ptr,
            peer_id_len: pid_len,
            addr: ns_ptr,
            addr_len: ns_len,
            value: ttl_ptr,
            value_len: ttl_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// Failed to register with a rendezvous relay.
    /// `relay_peer_id` = PeerId of the relay (stored in peer_id field).
    /// `error` = human-readable error string (stored in value field).
    pub fn relay_registration_failed(relay_peer_id: String, error: String) -> *mut Self {
        let pid = relay_peer_id.into_bytes();
        let pid_len = pid.len();
        let pid_ptr = Box::into_raw(pid.into_boxed_slice()) as *mut u8;
        let err = error.into_bytes();
        let err_len = err.len();
        let err_ptr = Box::into_raw(err.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 11,
            req_id: 0,
            peer_id: pid_ptr,
            peer_id_len: pid_len,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: err_ptr,
            value_len: err_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// AutoNAT v2 probe succeeded — the tested addr was verified reachable via the server.
    /// `tested_addr` in peer_id field, `server` peer id in addr field.
    pub fn autonat_probe_succeeded(tested_addr: String, server: String) -> *mut Self {
        let t = tested_addr.into_bytes();
        let t_len = t.len();
        let t_ptr = Box::into_raw(t.into_boxed_slice()) as *mut u8;
        let s = server.into_bytes();
        let s_len = s.len();
        let s_ptr = Box::into_raw(s.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 15,
            req_id: 0,
            peer_id: t_ptr,
            peer_id_len: t_len,
            addr: s_ptr,
            addr_len: s_len,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// AutoNAT v2 probe failed — the tested addr was not reachable via the server.
    /// `tested_addr` in peer_id field, `server` peer id in addr field, `error` in value field.
    pub fn autonat_probe_failed(tested_addr: String, server: String, error: String) -> *mut Self {
        let t = tested_addr.into_bytes();
        let t_len = t.len();
        let t_ptr = Box::into_raw(t.into_boxed_slice()) as *mut u8;
        let s = server.into_bytes();
        let s_len = s.len();
        let s_ptr = Box::into_raw(s.into_boxed_slice()) as *mut u8;
        let err = error.into_bytes();
        let err_len = err.len();
        let err_ptr = Box::into_raw(err.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 14,
            req_id: 0,
            peer_id: t_ptr,
            peer_id_len: t_len,
            addr: s_ptr,
            addr_len: s_len,
            value: err_ptr,
            value_len: err_len,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// UPnP: no IGD gateway found on the local network.
    pub fn upnp_gateway_not_found() -> *mut Self {
        Box::into_raw(Box::new(Self {
            kind: 16,
            req_id: 0,
            peer_id: std::ptr::null_mut(),
            peer_id_len: 0,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// UPnP: gateway is not routable (carrier-grade NAT or private IP).
    pub fn upnp_non_routable_gateway() -> *mut Self {
        Box::into_raw(Box::new(Self {
            kind: 17,
            req_id: 0,
            peer_id: std::ptr::null_mut(),
            peer_id_len: 0,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// UPnP: new external address mapped on the gateway.
    pub fn upnp_new_external_addr(addr: String) -> *mut Self {
        let bytes = addr.into_bytes();
        let len = bytes.len();
        let ptr = Box::into_raw(bytes.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 18,
            req_id: 0,
            peer_id: ptr,
            peer_id_len: len,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
            key: std::ptr::null_mut(),
            key_len: 0,
        }))
    }

    /// UPnP: mapped external address expired / was removed.
    pub fn upnp_expired_external_addr(addr: String) -> *mut Self {
        let bytes = addr.into_bytes();
        let len = bytes.len();
        let ptr = Box::into_raw(bytes.into_boxed_slice()) as *mut u8;
        Box::into_raw(Box::new(Self {
            kind: 19,
            req_id: 0,
            peer_id: ptr,
            peer_id_len: len,
            addr: std::ptr::null_mut(),
            addr_len: 0,
            value: std::ptr::null_mut(),
            value_len: 0,
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
