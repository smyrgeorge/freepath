package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Clock
import kotlin.time.Instant

class ContactCardSignedCodecTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeCard(sigKp: KeyPair, encKp: KeyPair, updatedAt: Instant = Clock.System.now()): ContactCard =
        ContactCard(
            schema = ContactCardCodec.SCHEMA,
            sigKey = Base64.encode(sigKp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt.toEpochMilliseconds()),
        )

    // ── seal / open ───────────────────────────────────────────────────────────

    @Test
    fun sealOpen_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val signed = ContactCardSignedCodec.seal(card, kp.privateKey)
        val opened = ContactCardSignedCodec.open(signed).getOrThrow()
        assertEquals(card, opened)
    }

    @Test
    fun open_throwsForInvalidSignature() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val kp2 = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val wrongSig = ContactCardCodec.sign(card, kp2.privateKey)
        assertFails {
            ContactCardSignedCodec.open(ContactCardSigned(card, Base64.encode(wrongSig))).getOrThrow()
        }
    }

    // ── encode / decode ───────────────────────────────────────────────────────

    @Test
    fun encodeDecodeSignedContactCard_roundTrip() {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = makeCard(kp, encKp)
        val signed = ContactCardSignedCodec.seal(card, kp.privateKey)
        assertEquals(signed, ContactCardSignedCodec.decode(ContactCardSignedCodec.encode(signed)))
    }
}
