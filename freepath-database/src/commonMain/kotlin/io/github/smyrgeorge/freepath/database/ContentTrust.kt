package io.github.smyrgeorge.freepath.database

enum class ContentTrust {
    /** Signature verified against the author's known public key. */
    VERIFIED,
    /** Author is not in our contact list — cannot verify. */
    UNKNOWN,
    /** Author is known but signature verification failed — possibly forged or corrupted. */
    FAILED,
}
