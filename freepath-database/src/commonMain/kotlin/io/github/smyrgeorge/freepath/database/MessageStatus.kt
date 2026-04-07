package io.github.smyrgeorge.freepath.database

enum class MessageStatus {
    /** Persisted locally, not yet delivered to the network. */
    SENDING,

    /** Acknowledged by the recipient's node via the network. */
    SENT,

    /** Network delivery failed. */
    FAILED,

    /** Received from a remote peer and persisted locally. */
    RECEIVED,
}
