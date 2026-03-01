package io.github.smyrgeorge.freepath.contact

enum class TrustLevel {
    /** Content is received, stored, and propagated. Private messages are accepted. Default. */
    TRUSTED,

    /** Content is received and stored but not actively propagated. */
    KNOWN,

    /** Content from this Node ID is ignored entirely and existing content is removed immediately. */
    BLOCKED,
}
