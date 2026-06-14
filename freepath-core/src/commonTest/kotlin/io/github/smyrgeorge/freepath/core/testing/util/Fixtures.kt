package io.github.smyrgeorge.freepath.core.testing.util

import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import kotlin.io.encoding.Base64

/**
 * Builds the public contact card for this identity — the card other nodes store so they can encrypt
 * to (and verify) this node. Mirrors how `ContactService.save(identity)` derives the own card: the
 * two public keys, Base64-encoded.
 */
fun Identity.toContactCard(name: String? = null): Contact = Contact(
    schema = Contact.SCHEMA,
    sigKey = Base64.encode(sigKeyPublic),
    encKey = Base64.encode(encKeyPublic),
    name = name,
)
