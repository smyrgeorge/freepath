package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.model.contact.ContactCodec
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.util.crypto.KeyPair
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class IdentityService(
    override val db: ISQLite,
    private val identityRepository: IdentityEntryRepository,
) : Service {
    lateinit var peerId: String
    lateinit var identity: Identity

    context(db: Transaction)
    suspend fun geOwnIdentity(): IdentityEntry {
        val existing = identityRepository.findAll().getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        return (existing.firstOrNull() ?: createIdentity()).also {
            peerId = it.peerId
            identity = it.identity
        }
    }

    context(db: Transaction)
    private suspend fun createIdentity(): IdentityEntry {
        val sigKeyPair: KeyPair = CryptoProvider.generateEd25519KeyPair()
        val encKeyPair: KeyPair = CryptoProvider.generateX25519KeyPair()
        val peerIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey)
        val peerId = ContactCodec.derivePeerId(sigKeyPair.publicKey)

        val identity = Identity(
            peerIdRaw = peerIdRaw,
            sigKeyPublic = sigKeyPair.publicKey,
            sigKeyPrivate = sigKeyPair.privateKey,
            encKeyPublic = encKeyPair.publicKey,
            encKeyPrivate = encKeyPair.privateKey,
        )

        val entry = IdentityEntry(peerId = peerId, identity = identity)
        return identityRepository.insert(entry).getOrThrow()
    }

    context(db: Transaction)
    suspend fun deleteAll() {
        identityRepository.deleteAll().getOrThrow()
    }
}
