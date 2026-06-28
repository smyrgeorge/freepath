package io.github.smyrgeorge.freepath.database

import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityEntryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun identity(): Identity {
        val sig = CryptoProvider.generateEd25519KeyPair()
        val enc = CryptoProvider.generateX25519KeyPair()
        return Identity(
            peerIdRaw = CryptoProvider.sha256(sig.publicKey),
            sigKeyPublic = sig.publicKey,
            sigKeyPrivate = sig.privateKey,
            encKeyPublic = enc.publicKey,
            encKeyPrivate = enc.privateKey,
        )
    }

    // ── construction ────────────────────────────────────────────────────────────

    @Test
    fun identityEntry_freshEntry_hasZeroId() {
        val id = identity()
        val entry = IdentityEntry(peerId = id.peerId, identity = id)
        assertEquals(0, entry.id)
    }

    @Test
    fun identityEntry_validPeerId_succeeds() {
        val id = identity()
        val entry = IdentityEntry(peerId = id.peerId, identity = id)
        assertEquals(id.peerId, entry.peerId)
        assertEquals(id, entry.identity)
    }

    // ── peerId validation ──────────────────────────────────────────────────────

    @Test
    fun identityEntry_invalidPeerId_throws() {
        val id = identity()
        assertFailsWith<IllegalArgumentException> {
            IdentityEntry(peerId = "not-base58", identity = id)
        }
    }

    @Test
    fun identityEntry_shortPeerId_throws() {
        val id = identity()
        assertFails {
            IdentityEntry(peerId = "short", identity = id)
        }
    }

    // ── id validation ────────────────────────────────────────────────────────────

    @Test
    fun identityEntry_negativeId_throws() {
        val id = identity()
        assertFailsWith<IllegalArgumentException> {
            IdentityEntry(id = -1, peerId = id.peerId, identity = id)
        }
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    fun identityEntry_toString_containsPeerId() {
        val id = identity()
        val entry = IdentityEntry(peerId = id.peerId, identity = id)
        assertTrue(entry.toString().contains(id.peerId))
    }
}
