package io.github.smyrgeorge.freepath.database

enum class MessageStatus {
    /** Persisted locally, not yet delivered to the network. */
    SENDING,

    /**
     * Sealed into the relay queue but not yet handed to any peer — no peer was online to carry it.
     * `SyncPeerActor` forwards it into the mesh once a peer (re)connects.
     */
    QUEUED,

    /**
     * Handed to at least one online peer to carry through the mesh. Best-effort: the mesh is
     * fire-and-forget, so there is no end-to-end delivery receipt that would promote this to [SENT].
     */
    RELAYED,

    /** Acknowledged by the recipient's node via the network (direct delivery). */
    SENT,

    /** Network delivery failed. */
    FAILED,

    /** Received from a remote peer and persisted locally. */
    RECEIVED,
}
