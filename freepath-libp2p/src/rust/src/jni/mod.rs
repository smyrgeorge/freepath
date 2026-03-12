// freepath-libp2p/src/rust/src/jni/mod.rs
//! JNI exports for JVM and Android.

#![cfg(not(target_os = "ios"))]

use crate::core::{EventCallback, LibP2pNode, RawLibP2pEvent};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong, JNI_VERSION_1_6};
use jni::{jni_sig, jni_str, EnvUnowned, JavaVM};
use std::ffi::c_void;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};

static JVM: OnceLock<JavaVM> = OnceLock::new();
// Cached GlobalRef raw pointers for classes that must be found while the app classloader
// is active (i.e. during JNI_OnLoad). Tokio worker threads attach with the bootstrap
// classloader on Android, so FindClass fails for app classes from those threads.
static CALLBACK_CLASS_PTR: AtomicUsize = AtomicUsize::new(0);
static LOGGER_CLASS_PTR: AtomicUsize = AtomicUsize::new(0);

#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _reserved: *mut c_void) -> jint {
    let jvm = unsafe { JavaVM::from_raw(vm) };
    let _ = JVM.set(jvm);
    // Cache class GlobalRefs now, while we have the app classloader.
    if let Some(jvm) = JVM.get() {
        let _ = jvm.attach_current_thread(|env| -> jni::errors::Result<()> {
            let cls = env.find_class(jni_str!(
                "io/github/smyrgeorge/freepath/libp2p/Libp2pCallback"
            ))?;
            let global = env.new_global_ref(&cls)?;
            CALLBACK_CLASS_PTR.store(global.as_raw() as usize, Ordering::Release);
            std::mem::forget(global); // intentionally leaked — lives for process lifetime

            let cls = env.find_class(jni_str!(
                "io/github/smyrgeorge/freepath/libp2p/Libp2pLogger"
            ))?;
            let global = env.new_global_ref(&cls)?;
            LOGGER_CLASS_PTR.store(global.as_raw() as usize, Ordering::Release);
            std::mem::forget(global); // intentionally leaked — lives for process lifetime
            Ok(())
        });
    }
    crate::logging::init(jni_log_cb);
    JNI_VERSION_1_6
}

unsafe extern "C" fn jni_log_cb(
    level: u8,
    tag: *const u8,
    tag_len: usize,
    msg: *const u8,
    msg_len: usize,
) {
    let tag = std::str::from_utf8(std::slice::from_raw_parts(tag, tag_len)).unwrap_or("?");
    let msg = std::str::from_utf8(std::slice::from_raw_parts(msg, msg_len)).unwrap_or("?");
    deliver_log(level, tag, msg);
}

fn deliver_log(level: u8, tag: &str, msg: &str) {
    let ptr = LOGGER_CLASS_PTR.load(Ordering::Acquire);
    if ptr == 0 {
        return;
    }
    let Some(jvm) = JVM.get() else { return };
    let _ = jvm.attach_current_thread(|env| -> jni::errors::Result<()> {
        // Use cached GlobalRef to avoid FindClass from background threads (Android classloader issue).
        // Safety: JClass<'_> is #[repr(transparent)] over jobject; ptr is a valid GlobalRef.
        let cls: JClass<'_> = unsafe { std::mem::transmute(ptr as jni::sys::jobject) };
        let tag_str = env.new_string(tag)?;
        let msg_str = env.new_string(msg)?;
        env.call_static_method(
            &cls,
            jni_str!("onLog"),
            jni_sig!("(ILjava/lang/String;Ljava/lang/String;)V"),
            &[(level as jint).into(), (&tag_str).into(), (&msg_str).into()],
        )?;
        Ok(())
    });
}

fn deliver_event(
    event_handle: jlong,
    kind: u8,
    req_id: u64,
    peer_id: &str,
    addr: &str,
    key: &[u8],
    value: &[u8],
) {
    let ptr = CALLBACK_CLASS_PTR.load(Ordering::Acquire);
    if ptr == 0 {
        log::error!("deliver_event: class not cached");
        return;
    }
    let Some(jvm) = JVM.get() else {
        log::error!("deliver_event: JVM not initialised");
        return;
    };
    if let Err(e) = jvm.attach_current_thread(|env| -> jni::errors::Result<()> {
        // Use cached GlobalRef to avoid FindClass from background threads (Android classloader issue).
        // Safety: JClass<'_> is #[repr(transparent)] over jobject; ptr is a valid GlobalRef.
        let cls: JClass<'_> = unsafe { std::mem::transmute(ptr as jni::sys::jobject) };
        let pid_str = env.new_string(peer_id)?;
        let addr_str = env.new_string(addr)?;
        let key_arr = env.byte_array_from_slice(key)?;
        let val_arr = env.byte_array_from_slice(value)?;
        env.call_static_method(
            &cls,
            jni_str!("onEvent"),
            jni_sig!("(JBJLjava/lang/String;Ljava/lang/String;[B[B)V"),
            &[
                event_handle.into(),
                (kind as jni::sys::jbyte).into(),
                (req_id as jlong).into(),
                (&pid_str).into(),
                (&addr_str).into(),
                (&key_arr).into(),
                (&val_arr).into(),
            ],
        )?;
        Ok(())
    }) {
        log::error!("deliver_event failed: {e:?}");
    }
}

/// Java: `external fun start(nodeId: String, sigKeyPrivate: ByteArray, listenAddr: String, eventHandle: Long): Long`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_start(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node_id: JString<'_>,
    sig_key: JByteArray<'_>,
    listen_addr: JString<'_>,
    event_handle: jlong,
) -> jlong {
    let (nid, key_bytes, addr) = match env
        .with_env(|env| -> jni::errors::Result<(String, Vec<u8>, String)> {
            let nid: String = node_id.try_to_string(env)?;
            let key_bytes: Vec<u8> = env.convert_byte_array(&sig_key)?;
            let addr: String = listen_addr.try_to_string(env)?;
            Ok((nid, key_bytes, addr))
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        jni::Outcome::Err(e) => {
            log::error!("JNI start: env error: {e}");
            return 0;
        }
        jni::Outcome::Panic(_) => return 0,
    };

    let event_fun: unsafe extern "C" fn(*mut c_void, *mut RawLibP2pEvent) = {
        unsafe extern "C" fn cb(ctx: *mut c_void, ev: *mut RawLibP2pEvent) {
            let event_handle = ctx as jlong;
            // ev is always set by the Rust core before invoking this callback — null is a bug.
            assert!(!ev.is_null(), "JNI event callback: ev must not be null");
            let e = unsafe { &*ev };
            let pid = String::from_utf8_lossy(unsafe {
                std::slice::from_raw_parts(e.peer_id, e.peer_id_len)
            })
            .into_owned();
            let addr = if e.addr.is_null() {
                String::new()
            } else {
                String::from_utf8_lossy(unsafe { std::slice::from_raw_parts(e.addr, e.addr_len) })
                    .into_owned()
            };
            let key = if e.key.is_null() {
                &[]
            } else {
                unsafe { std::slice::from_raw_parts(e.key, e.key_len) }
            };
            let val = if e.value.is_null() {
                &[]
            } else {
                unsafe { std::slice::from_raw_parts(e.value, e.value_len) }
            };
            deliver_event(event_handle, e.kind, e.req_id, &pid, &addr, key, val);
            crate::ffi::libp2p_event_free(ev);
        }
        cb
    };

    let event_cb = EventCallback {
        ptr: event_handle as *mut c_void,
        fun: event_fun,
    };

    match crate::core::start_node(&nid, &key_bytes, &addr, event_cb) {
        Ok(arc) => Arc::into_raw(arc) as jlong,
        Err(e) => {
            log::error!("JNI start failed: {e}");
            0
        }
    }
}

/// Java: `external fun dial(nodeHandle: Long, multiaddr: String)`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_dial(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node: jlong,
    addr: JString<'_>,
) {
    // A zero handle means start() failed — the Kotlin caller must not invoke dial() in that case.
    assert!(node != 0, "JNI dial: node handle must not be zero");
    let addr_str: String = match env.with_env(|env| addr.try_to_string(env)).into_outcome() {
        jni::Outcome::Ok(s) => s,
        jni::Outcome::Err(e) => {
            log::error!("JNI dial: {e}");
            return;
        }
        jni::Outcome::Panic(_) => return,
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::Dial(addr_str));
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Java: `external fun sendRequest(nodeHandle: Long, peerId: String, reqId: Long, payload: ByteArray)`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendRequest(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node: jlong,
    peer_id: JString<'_>,
    req_id: jlong,
    payload: JByteArray<'_>,
) {
    // A zero handle means start() failed — the Kotlin caller must not invoke sendRequest() in that case.
    assert!(node != 0, "JNI sendRequest: node handle must not be zero");
    let (peer_id_str, payload_bytes) = match env
        .with_env(|env| -> jni::errors::Result<(String, Vec<u8>)> {
            let pid: String = peer_id.try_to_string(env)?;
            let bytes: Vec<u8> = env.convert_byte_array(&payload)?;
            Ok((pid, bytes))
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        jni::Outcome::Err(e) => {
            log::error!("JNI sendRequest: {e}");
            return;
        }
        jni::Outcome::Panic(_) => return,
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendRequest {
            peer_id: peer_id_str,
            req_id: req_id as u64,
            payload: payload_bytes,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Java: `external fun sendResponse(nodeHandle: Long, reqId: Long, payload: ByteArray)`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendResponse(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node: jlong,
    req_id: jlong,
    payload: JByteArray<'_>,
) {
    // A zero handle means start() failed — the Kotlin caller must not invoke sendResponse() in that case.
    assert!(node != 0, "JNI sendResponse: node handle must not be zero");
    let payload_bytes = match env
        .with_env(|env| env.convert_byte_array(&payload))
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        jni::Outcome::Err(e) => {
            log::error!("JNI sendResponse: {e}");
            return;
        }
        jni::Outcome::Panic(_) => return,
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendResponse {
            req_id: req_id as u64,
            payload: payload_bytes,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Java: `external fun sendResponseFailed(nodeHandle: Long, reqId: Long, error: String)`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendResponseFailed(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node: jlong,
    req_id: jlong,
    error: JString<'_>,
) {
    assert!(node != 0, "JNI sendResponseFailed: node handle must not be zero");
    let error_str: String = match env.with_env(|env| error.try_to_string(env)).into_outcome() {
        jni::Outcome::Ok(s) => s,
        jni::Outcome::Err(e) => {
            log::error!("JNI sendResponseFailed: {e}");
            return;
        }
        jni::Outcome::Panic(_) => return,
    };
    let arc = unsafe { Arc::from_raw(node as *const LibP2pNode) };
    let _ = arc
        .swarm_tx
        .try_send(crate::core::SwarmCommand::SendResponseFailed {
            req_id: req_id as u64,
            error: error_str,
        });
    let _ = Arc::into_raw(arc); // prevent drop
}

/// Java: `external fun stop(nodeHandle: Long)`
#[no_mangle]
pub extern "C" fn Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_stop(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    node: jlong,
) {
    // A zero handle means start() failed — the Kotlin caller must not invoke stop() in that case.
    assert!(node != 0, "JNI stop: node handle must not be zero");
    let _ = unsafe { Arc::from_raw(node as *const LibP2pNode) };
}
