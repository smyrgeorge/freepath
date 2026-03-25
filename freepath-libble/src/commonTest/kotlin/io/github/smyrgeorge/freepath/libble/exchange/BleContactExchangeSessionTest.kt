package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.libble.gatt.BleConnectionPort
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BleContactExchangeSessionTest {

    private fun makeCard(): Pair<ContactCard, ByteArray> {
        val kp = CryptoProvider.generateEd25519KeyPair()
        val encKp = CryptoProvider.generateX25519KeyPair()
        val card = ContactCard(
            schema = ContactCard.SCHEMA,
            sigKey = Base64.encode(kp.publicKey),
            encKey = Base64.encode(encKp.publicKey),
            updatedAt = Instant.fromEpochMilliseconds(1_000L),
        )
        return card to kp.privateKey
    }

    private class FakeBleConnection(
        private val peerCardBytes: ByteArray,
        val writtenBytes: MutableList<ByteArray> = mutableListOf(),
    ) : BleConnectionPort {
        override suspend fun connect() {}
        override suspend fun ping() {}
        override suspend fun readCard(): ByteArray = peerCardBytes
        override suspend fun writeCard(bytes: ByteArray) {
            writtenBytes += bytes
        }

        override suspend fun disconnect() {}
    }

    @Test
    fun initiator_send_writesEncodedCard() = runTest {
        val (myCard, myKey) = makeCard()
        val (peerCard, peerKey) = makeCard()
        val fake = FakeBleConnection(peerCardBytes = BleContactExchange.encode(peerCard, peerKey))

        BleContactExchangeSession(fake, "1234").send(myCard, myKey)

        assertEquals(1, fake.writtenBytes.size)
        assertEquals("1234", BleContactExchange.decodeWithPin(fake.writtenBytes[0]).getOrThrow().first)
        assertEquals(myCard, BleContactExchange.decodeWithPin(fake.writtenBytes[0]).getOrThrow().second)
    }

    @Test
    fun initiator_receive_returnsPeerCard() = runTest {
        val (myCard, myKey) = makeCard()
        val (peerCard, peerKey) = makeCard()
        val fake = FakeBleConnection(peerCardBytes = BleContactExchange.encode(peerCard, peerKey))

        val session = BleContactExchangeSession(fake, "1234")
        session.send(myCard, myKey)

        assertEquals(peerCard, session.receive().getOrThrow())
    }
}