package io.github.smyrgeorge.freepath.libble.exchange

import io.github.smyrgeorge.freepath.model.contact.Contact

class BleExchangeResult(
    val contact: Contact,
    val peripheralId: String,
    val identitySecret: ByteArray,
)
