#ifndef FREEPATH_LIBP2P_H
#define FREEPATH_LIBP2P_H

#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

/**
 * Event kinds:
 *   0 = PeerConnected             (peer_id + addr)
 *   1 = PeerDisconnected          (peer_id)
 *   3 = NewListenAddr             (addr stored in peer_id field)
 *   4 = PeerIdentified            (peer_id)
 *   6 = RequestReceived           (req_id, peer_id=senderId, addr=recipientId, value=payload)
 *   7 = ResponseReceived          (req_id, peer_id=senderId, addr=recipientId, value=payload)
 *   8 = RequestFailed             (req_id, peer_id=senderId, addr=recipientId, value=error bytes)
 *   9 = RelayConnected            (peer_id=relayPeerId)
 *  10 = RelayRegistered           (peer_id=relayPeerId, addr=namespace, value=ttl as decimal string)
 *  11 = RelayRegistrationFailed   (peer_id=relayPeerId, value=error string)
 *  14 = AutonatProbeFailed        (peer_id=testedAddr, addr=serverPeerId, value=error string)
 *  15 = AutonatProbeSucceeded     (peer_id=testedAddr, addr=serverPeerId)
 *  16 = UpnpGatewayNotFound       (no payload)
 *  17 = UpnpNonRoutableGateway    (no payload)
 *  18 = UpnpNewExternalAddr       (peer_id=addr)
 *  19 = UpnpExpiredExternalAddr   (peer_id=addr)
 */
typedef struct RawLibP2pEvent {
  uint8_t kind;
  uint64_t req_id;
  uint8_t *peer_id;
  uintptr_t peer_id_len;
  uint8_t *addr;
  uintptr_t addr_len;
  uint8_t *value;
  uintptr_t value_len;
  uint8_t *key;
  uintptr_t key_len;
} RawLibP2pEvent;

void libp2p_set_log_callback(void (*cb)(uint8_t level,
                                        const uint8_t *tag,
                                        uintptr_t tag_len,
                                        const uint8_t *msg,
                                        uintptr_t msg_len));

/**
 * Start a libp2p node. Returns an opaque Arc<LibP2pNode> pointer (never null on success).
 * On failure, returns null.
 */
void *libp2p_start(const char *node_id,
                   const uint8_t *sig_key_private,
                   uintptr_t sig_key_len,
                   const char *listen_addr,
                   const char *relay_addrs,
                   void *event_callback,
                   void (*event_fun)(void*, struct RawLibP2pEvent*),
                   void *contact_callback,
                   bool (*contact_fun)(void*, const uint8_t*, uintptr_t));

/**
 * Stop the node and release the Arc.
 */
void libp2p_stop(void *node);

/**
 * Dial a peer by multiaddr string. Non-blocking: queues the command to the swarm.
 */
void libp2p_dial(void *node, const char *addr);

/**
 * Send a request to a connected peer and expect a response.
 * `req_id` is a caller-assigned correlation ID; it will be echoed back in the
 * ResponseReceived (kind 7) or RequestFailed (kind 8) event. Non-blocking.
 */
void libp2p_send_request(void *node,
                         const char *peer_id,
                         uint64_t req_id,
                         const uint8_t *payload,
                         uintptr_t payload_len);

/**
 * Send a response to an incoming request identified by `req_id`.
 * `req_id` must be the value delivered with the RequestReceived (kind 6) event.
 * Non-blocking.
 */
void libp2p_send_response(void *node,
                          uint64_t req_id,
                          const uint8_t *payload,
                          uintptr_t payload_len);

/**
 * Send an error response to an incoming request identified by `req_id`.
 * The original sender will receive a RequestFailed (kind 8) event with `error` as the description.
 * `req_id` must be the value delivered with the RequestReceived (kind 6) event. Non-blocking.
 */
void libp2p_send_response_failed(void *node, uint64_t req_id, const char *error);

/**
 * Free a RawLibP2pEvent dispatched via event_fun. Must be called exactly once per event.
 */
void libp2p_event_free(struct RawLibP2pEvent *event);

#ifndef __APPLE__

jint JNI_OnLoad(JavaVM *vm, void *_reserved);

/**
 * Java: `external fun start(nodeId: String, sigKeyPrivate: ByteArray, listenAddr: String, relayAddrs: String, eventHandle: Long): Long`
 */
jlong Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_start(EnvUnowned env,
                                                                JClass _class,
                                                                JString node_id,
                                                                JByteArray sig_key,
                                                                JString listen_addr,
                                                                JString relay_addrs,
                                                                jlong event_handle);

/**
 * Java: `external fun dial(nodeHandle: Long, multiaddr: String)`
 */
void Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_dial(EnvUnowned env,
                                                              JClass _class,
                                                              jlong node,
                                                              JString addr);

/**
 * Java: `external fun sendRequest(nodeHandle: Long, peerId: String, reqId: Long, payload: ByteArray)`
 */
void Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendRequest(EnvUnowned env,
                                                                     JClass _class,
                                                                     jlong node,
                                                                     JString peer_id,
                                                                     jlong req_id,
                                                                     JByteArray payload);

/**
 * Java: `external fun sendResponse(nodeHandle: Long, reqId: Long, payload: ByteArray)`
 */
void Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendResponse(EnvUnowned env,
                                                                      JClass _class,
                                                                      jlong node,
                                                                      jlong req_id,
                                                                      JByteArray payload);

/**
 * Java: `external fun sendResponseFailed(nodeHandle: Long, reqId: Long, error: String)`
 */
void Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_sendResponseFailed(EnvUnowned env,
                                                                            JClass _class,
                                                                            jlong node,
                                                                            jlong req_id,
                                                                            JString error);

/**
 * Java: `external fun stop(nodeHandle: Long)`
 */
void Java_io_github_smyrgeorge_freepath_libp2p_Libp2pJni_stop(EnvUnowned _env,
                                                              JClass _class,
                                                              jlong node);

#endif  /* !__APPLE__ */

#endif  /* FREEPATH_LIBP2P_H */
