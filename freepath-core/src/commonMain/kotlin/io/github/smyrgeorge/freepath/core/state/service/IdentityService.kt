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
    private val db: ISQLite,
    private val identityRepository: IdentityEntryRepository,
) {
    suspend fun geOwnIdentity(): IdentityEntry {
        val existing = identityRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        return existing.firstOrNull() ?: createIdentity()
    }

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
        return identityRepository.insert(db, entry).getOrThrow()
    }

    suspend fun deleteAll(tx: Transaction) {
        identityRepository.deleteAll(tx).getOrThrow()
    }
}