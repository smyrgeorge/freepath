package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchangeMethod
import io.github.smyrgeorge.freepath.contact.exchange.ContactExchangeSession
import io.github.smyrgeorge.freepath.libble.gatt.BleConnectionPort

class BleContactExchangeSession(
    private val connection: BleConnectionPort,
    private val pin: String,
) : ContactExchangeSession {

    override val method: ContactExchangeMethod = ContactExchangeMethod.BLUETOOTH

    init {
        require(pin.isNotBlank()) { "PIN must not be blank" }
        require(pin.length == 4 || pin.length == 12) { "PIN must be 4 characters long (was ${pin.length})" }
        require(pin.matches(Regex("[0-9]+"))) { "PIN must contain only digits" }
    }

    override suspend fun send(localCard: ContactCard, sigKeyPrivate: ByteArray): ByteArray {
        val encoded = BleContactExchange.encodeWithPin(localCard, sigKeyPrivate, pin)
        connection.connect()
        connection.writeCard(encoded)
        return encoded
    }

    override suspend fun receive(): Result<ContactCard> = runCatching {
        BleContactExchange.decode(connection.readCard()).getOrThrow()
    }

    override suspend fun close() = connection.disconnect()
}
