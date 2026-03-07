package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.AppResources.contactCardRepository
import io.github.smyrgeorge.freepath.AppResources.db
import io.github.smyrgeorge.freepath.AppResources.identityRepository
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.TrustLevel
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.util.codec.Base58
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.io.encoding.Base64
import kotlin.time.Clock

object AppState {
    data class DiscoveredPeer(val nodeId: String, val isKnown: Boolean)

    private val _discoveredPeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactCardEntry>>(emptyList())
    val contacts: StateFlow<List<ContactCardEntry>> = _contacts.asStateFlow()

    lateinit var identity: Identity private set
    lateinit var identityEntry: IdentityEntry private set
    lateinit var contactCard: ContactCard private set
    lateinit var contactCardEntry: ContactCardEntry private set

    // Called only by AppActor
    suspend fun initialize() {
        loadIdentity()
        loadOwnContactCard()
        loadContacts()
    }

    // Called only by AppActor
    suspend fun acceptContact(card: ContactCard) {
        val existing = contactCardRepository.findOneByNodeId(db, card.nodeId).getOrThrow()
        if (existing == null) {
            val entry = ContactCardEntry(nodeId = card.nodeId, card = card)
            contactCardRepository.insert(db, entry).getOrThrow()
        } else {
            val entry = existing.merge(ContactCardEntry(nodeId = card.nodeId, card = card))
            contactCardRepository.update(db, entry).getOrThrow()
        }
        markPeerKnown(card.nodeId)
        loadContacts()
    }

    // Called only by AppActor
    suspend fun setTrustLevel(entry: ContactCardEntry, level: TrustLevel) {
        val updated = entry.copy(trustLevel = level, updatedAt = Clock.System.now())
        contactCardRepository.update(db, updated).getOrThrow()
        loadContacts()
    }

    // Called only by AppActor
    suspend fun loadContacts() {
        val ownNodeId = contactCardEntry.nodeId
        _contacts.value = contactCardRepository.findAll(db).getOrThrow().filter { it.nodeId != ownNodeId }
    }

    // Called only by AppActor
    fun peerDiscovered(nodeId: String) {
        _discoveredPeers.update { current ->
            if (nodeId !in current) current + (nodeId to DiscoveredPeer(nodeId = nodeId, isKnown = false))
            else current
        }
    }

    // Called only by AppActor
    fun peerConnected(nodeId: String) {
        _discoveredPeers.update { it + (nodeId to DiscoveredPeer(nodeId = nodeId, isKnown = true)) }
    }

    // Called only by AppActor
    fun peerLost(nodeId: String) {
        // Ignore mDNS loss for a peer that is actively connected (isKnown=true).
        // iOS re-registers mDNS with a new service name on foreground, which JmDNS reports as
        // "old service removed" followed by "new service found". The TCP connection is still alive,
        // so the mDNS event is spurious. Only peerDisconnected (TCP teardown) removes a connected peer.
        _discoveredPeers.update { current ->
            val peer = current[nodeId]
            if (peer == null || peer.isKnown) current else current - nodeId
        }
    }

    // Called only by AppActor
    fun peerDisconnected(nodeId: String) {
        _discoveredPeers.update { it - nodeId }
    }

    // Called only by AppActor — marks accepted contact as known in the live peer map
    fun markPeerKnown(nodeId: String) {
        _discoveredPeers.update { peers ->
            peers[nodeId]?.let { peer -> peers + (nodeId to peer.copy(isKnown = true)) } ?: peers
        }
    }

    suspend fun completeOnboarding(name: String?, bio: String?, location: String?) {
        val updatedCard = contactCard.copy(
            name = name?.takeIf { it.isNotBlank() },
            bio = bio?.takeIf { it.isNotBlank() },
            location = location?.takeIf { it.isNotBlank() },
            updatedAt = Clock.System.now(),
        )
        val updatedEntry = contactCardEntry.copy(
            card = updatedCard,
            tags = contactCardEntry.tags - ContactCardEntry.TAG_ONBOARDING,
        )
        contactCardEntry = contactCardRepository.update(db, updatedEntry).getOrThrow()
        contactCard = contactCardEntry.card
    }

    private suspend fun loadIdentity() {
        val existing = identityRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        val entry = existing.firstOrNull() ?: createAndSaveIdentity()
        identity = entry.data
        identityEntry = entry
    }

    private suspend fun loadOwnContactCard() {
        val nodeId = identityEntry.nodeId
        val existing = contactCardRepository.findOneByNodeId(db, nodeId).getOrThrow()
        if (existing != null) {
            contactCard = existing.card
            contactCardEntry = existing
            return
        }

        val card = ContactCard(
            schema = ContactCard.SCHEMA,
            nodeId = nodeId,
            sigKey = Base64.encode(identity.sigKeyPublic),
            encKey = Base64.encode(identity.encKeyPublic),
            updatedAt = Clock.System.now(),
            name = "#$nodeId",
        )

        contactCard = card
        val entry = ContactCardEntry(nodeId = nodeId, card = card, tags = listOf(ContactCardEntry.TAG_ONBOARDING))
        contactCardEntry = contactCardRepository.insert(db, entry).getOrThrow()
    }

    private suspend fun createAndSaveIdentity(): IdentityEntry {
        val sigKeyPair: KeyPair = CryptoProvider.generateEd25519KeyPair()
        val encKeyPair: KeyPair = CryptoProvider.generateX25519KeyPair()
        val nodeIdRaw = CryptoProvider.sha256(sigKeyPair.publicKey).copyOf(16)
        val nodeId = Base58.encode(nodeIdRaw)

        val identity = Identity(
            nodeIdRaw = nodeIdRaw,
            sigKeyPublic = sigKeyPair.publicKey,
            sigKeyPrivate = sigKeyPair.privateKey,
            encKeyPublic = encKeyPair.publicKey,
            encKeyPrivate = encKeyPair.privateKey,
        )

        val entry = IdentityEntry(nodeId = nodeId, data = identity)
        return identityRepository.insert(db, entry).getOrThrow()
    }
}
