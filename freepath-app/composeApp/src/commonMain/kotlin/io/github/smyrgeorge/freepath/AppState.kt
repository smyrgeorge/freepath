package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.crypto.KeyPair
import io.github.smyrgeorge.freepath.database.ContactCardEntry
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactCardEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.lan.LanLinkAdapter
import io.github.smyrgeorge.freepath.transport.lan.createPeerDiscovery
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.freepath.util.configureLogging
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.io.encoding.Base64
import kotlin.time.Clock

object AppState {
    @Suppress("unused")
    private val rootLogger = RootLogger // Do not delete this line.
    val log = Logger.of(AppState::class)

    init {
        configureLogging()
    }

    lateinit var db: ISQLite private set
    lateinit var identity: Identity private set
    lateinit var identityEntry: IdentityEntry private set
    lateinit var contactCard: ContactCard private set
    lateinit var contactCardEntry: ContactCardEntry private set
    lateinit var lanProtocol: StatefulProtocol private set

    val contactCardEntryRepository: ContactCardEntryRepository = ContactCardEntryRepositoryImpl
    val identityEntryRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl


    // In-memory map of known peer nodeId (Base58) -> sigKey public bytes.
    // Populated at startup from the DB; used for synchronous lookups by the protocol stack.
    private val contactSigKeys = mutableMapOf<String, ByteArray>()

    suspend fun initialize() {
        loadDatabase()
        loadIdentity()
        loadOwnContactCard()
        loadLanProtocol()
    }

    private suspend fun loadDatabase() {
        db = sqlite(
            url = "freepath.db",
            options = ConnectionPool.Options(
                minConnections = 1,
                maxConnections = 1,
            ),
        ).also {
            it.migrate(
                files = migrations,
                afterStatementExecution = { s, d -> log.info { "DB: Executed: $s ($d)" } },
                afterFileMigration = { f, d -> log.info { "DB: Migrated: $f ($d)" } },
            )
        }
    }

    private suspend fun loadIdentity() {
        val existing = identityEntryRepository.findAll(db).getOrThrow()
        require(existing.size <= 1) { "Expected at most one identity entry, got $existing" }
        val entry = existing.firstOrNull() ?: createAndSaveIdentity()
        identity = entry.data
        identityEntry = entry
    }

    private suspend fun loadOwnContactCard() {
        val nodeId = identityEntry.nodeId
        val existing = contactCardEntryRepository.findOneByNodeId(db, nodeId).getOrThrow()
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
        contactCardEntry = contactCardEntryRepository.insert(db, entry).getOrThrow()
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
        contactCardEntry = contactCardEntryRepository.update(db, updatedEntry).getOrThrow()
        contactCard = contactCardEntry.card
    }

    private suspend fun loadLanProtocol() {
        // Build in-memory contact map from DB, excluding our own card.
        // contactSigKeys must be populated before the protocol starts so that
        // synchronous lookups from incoming mDNS/handshake callbacks are satisfied.
        contactCardEntryRepository.findAll(db).getOrThrow().forEach {
            if (it.nodeId != identityEntry.nodeId) {
                contactSigKeys[it.nodeId] = Base64.decode(it.card.sigKey)
            }
        }

        val nodeId = identityEntry.nodeId
        var proto: StatefulProtocol? = null

        val adapter = LanLinkAdapter(
            peerDiscovery = createPeerDiscovery(nodeId),
            onPeerDisconnected = { peerId ->
                log.warn { "Peer $peerId disconnected" }
                proto?.closeSession(peerId)
            },
            isKnownPeer = { peerId ->
                log.info { "Looking up $peerId in contact map" }
                peerId in contactSigKeys
            },
            onConnectionEstablished = { peerId ->
                log.info { "Connected to $peerId — starting handshake" }
                proto?.initiateHandshake(peerId)
            },
            onIdleTimeout = { peerId ->
                log.info { "Idle timeout for $peerId — sending CLOSE" }
                proto?.closeSession(peerId)
            },
        )

        proto = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdRaw ->
                val nodeId = Base58.encode(nodeIdRaw)
                log.info { "Looking up $nodeId in contact map" }
                contactSigKeys[nodeId]
            },
            linkAdapter = adapter,
            onFrameReceived = { peerId, _, _ ->
                log.info { "Frame received from $peerId" }
            },
        )
        lanProtocol = proto
        proto.start()
        log.info { "LAN protocol started on port ${adapter.localPort}" }
    }

    private suspend fun createAndSaveIdentity(): IdentityEntry {
        require(this::db.isInitialized) { "Database not initialized" }

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
        return identityEntryRepository.insert(db, entry).getOrThrow()
    }
}
