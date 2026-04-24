package io.github.smyrgeorge.freepath.model.contact

import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class IdentityTest {

    private fun makeIdentity(): Identity {
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

    @Test
    fun equal_whenPeerIdRaw_matches() {
        val a = makeIdentity()
        val b = a.copy()  // same peerIdRaw reference by copy
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equality_ignoresOtherFields() {
        val base = makeIdentity()
        val differentKeys = base.copy(
            sigKeyPublic = ByteArray(32) { 7 },
            sigKeyPrivate = ByteArray(32) { 8 },
            encKeyPublic = ByteArray(32) { 9 },
            encKeyPrivate = ByteArray(32) { 10 },
        )
        // identical peerIdRaw -> still equal despite every other field differing
        assertEquals(base, differentKeys)
        assertEquals(base.hashCode(), differentKeys.hashCode())
    }

    @Test
    fun notEqual_whenPeerIdRaw_differs() {
        val a = makeIdentity()
        val b = makeIdentity()
        assertNotEquals(a, b)
    }

    @Test
    fun equality_usesContentCompareNotReference() {
        val a = makeIdentity()
        val bPeer = a.peerIdRaw.copyOf()
        val b = a.copy(peerIdRaw = bPeer)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun selfEqual() {
        val a = makeIdentity()
        assertEquals(a, a)
    }

    @Test
    fun notEqual_toNonIdentity() {
        val a = makeIdentity()
        assertFalse(a.equals("peer"))
        assertFalse(a.equals(null))
    }

    @Test
    fun peerId_matchesCodecDerivePeerId() {
        val a = makeIdentity()
        assertEquals(ContactCodec.derivePeerId(a.sigKeyPublic), a.peerId)
    }

    @Test
    fun peerIdHash_isSha256OfPeerIdRaw() {
        val a = makeIdentity()
        assertContentEquals(CryptoProvider.sha256(a.peerIdRaw), a.peerIdHash)
    }

    @Test
    fun lazyProperties_cacheResults() {
        val a = makeIdentity()
        assertSame(a.peerIdHash, a.peerIdHash)
        assertEquals(a.peerId, a.peerId)
    }
}
