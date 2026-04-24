package io.github.smyrgeorge.freepath.model.contact

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ContactTest {

    private fun makeContact(
        sigKp: KeyPair,
        encKp: KeyPair,
        updatedAt: Instant = Clock.System.now(),
        name: String? = null,
    ): Contact = Contact(
        schema = Contact.SCHEMA,
        sigKey = Base64.encode(sigKp.publicKey),
        encKey = Base64.encode(encKp.publicKey),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt.toEpochMilliseconds()),
        name = name,
    )

    // ── sigKey / encKey length ────────────────────────────────────────────────

    @Test
    fun rejectsEmptySigKey() {
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            Contact(
                schema = Contact.SCHEMA,
                sigKey = "",
                encKey = Base64.encode(encKp.publicKey),
            )
        }
    }

    @Test
    fun rejectsSigKeyWrongLength() {
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            Contact(
                schema = Contact.SCHEMA,
                sigKey = "short",
                encKey = Base64.encode(encKp.publicKey),
            )
        }
    }

    @Test
    fun rejectsEmptyEncKey() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        assertFails {
            Contact(
                schema = Contact.SCHEMA,
                sigKey = Base64.encode(sigKp.publicKey),
                encKey = "",
            )
        }
    }

    @Test
    fun rejectsEncKeyWrongLength() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        assertFails {
            Contact(
                schema = Contact.SCHEMA,
                sigKey = Base64.encode(sigKp.publicKey),
                encKey = "short",
            )
        }
    }

    // ── updatedAt ─────────────────────────────────────────────────────────────

    @Test
    fun rejectsNegativeUpdatedAt() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            makeContact(sigKp, encKp, updatedAt = Instant.fromEpochMilliseconds(-1L))
        }
    }

    @Test
    fun acceptsEpochZeroUpdatedAt() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp, updatedAt = Instant.fromEpochMilliseconds(0L))
        assertEquals(Instant.fromEpochMilliseconds(0L), c.updatedAt)
    }

    // ── name edge cases ───────────────────────────────────────────────────────

    @Test
    fun rejectsBlankName() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        assertFails {
            makeContact(sigKp, encKp, name = "   ")
        }
    }

    @Test
    fun acceptsEmptyName() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        // Empty string passes the "isNullOrEmpty OR isNotBlank" guard via isNullOrEmpty
        val c = makeContact(sigKp, encKp, name = "")
        assertEquals("", c.name)
    }

    @Test
    fun acceptsNullName() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp, name = null)
        assertEquals(null, c.name)
    }

    // ── lazy key derivations ─────────────────────────────────────────────────

    @Test
    fun sigKeyPublic_decodesFromBase64() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        assertContentEquals(sigKp.publicKey, c.sigKeyPublic)
    }

    @Test
    fun encKeyPublic_decodesFromBase64() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        assertContentEquals(encKp.publicKey, c.encKeyPublic)
    }

    @Test
    fun peerId_matchesCodecDerivePeerId() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        assertEquals(ContactCodec.derivePeerId(sigKp.publicKey), c.peerId)
    }

    @Test
    fun peerIdRaw_matchesSha256OfSigKeyPublic() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        assertContentEquals(CryptoProvider.sha256(sigKp.publicKey), c.peerIdRaw)
    }

    @Test
    fun peerIdHash_isSha256OfPeerIdRaw() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        assertContentEquals(CryptoProvider.sha256(c.peerIdRaw), c.peerIdHash)
    }

    @Test
    fun lazyProperties_cacheResults_sameReference() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        // repeated access returns the same cached array
        assertSame(c.sigKeyPublic, c.sigKeyPublic)
        assertSame(c.encKeyPublic, c.encKeyPublic)
        assertSame(c.peerIdRaw, c.peerIdRaw)
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    fun toString_includesPeerIdAndSchema() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp, name = "Alice")
        val s = c.toString()
        assertTrue(s.contains("name=Alice"))
        assertTrue(s.contains("peerId='${c.peerId}'"))
        assertTrue(s.contains("schema=${Contact.SCHEMA}"))
    }

    @Test
    fun toString_omitsSigAndEncKeyBytes() {
        val sigKp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val c = makeContact(sigKp, encKp)
        val s = c.toString()
        // Secrets aren't included, and key material isn't either — keep toString compact
        assertTrue(!s.contains(c.sigKey))
        assertTrue(!s.contains(c.encKey))
    }
}
