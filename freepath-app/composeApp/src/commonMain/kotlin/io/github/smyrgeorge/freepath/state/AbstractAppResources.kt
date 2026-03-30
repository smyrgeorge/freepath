package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.crypto.CryptoProvider
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
import io.github.smyrgeorge.freepath.database.ContactRoutingEntry
import io.github.smyrgeorge.freepath.database.ContactRoutingEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContactRoutingEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentSyncEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

abstract class AbstractAppResources(
    private val database: String
) {
    private val log = Logger.of(this::class)

    lateinit var db: ISQLite private set
    lateinit var system: ActorRef private set
    lateinit var client: LibnetClient private set

    lateinit var identity: Identity
    lateinit var contactLookup: (String) -> Contact?

    val identityRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl
    val contactRepository: ContactEntryRepository = ContactEntryRepositoryImpl
    val contentEntryRepository: ContentEntryRepository = ContentEntryRepositoryImpl
    val contentSyncRepository: ContentSyncEntryRepository = ContentSyncEntryRepositoryImpl
    val contactRoutingEntryRepository: ContactRoutingEntryRepository = ContactRoutingEntryRepositoryImpl

    val libp2p: Libp2pModule = Libp2pModule().setEventHandler { event ->
        log.info { "LIBP2P Event: $event" }
        when (event) {
            is Libp2pEvent.PeerIdentified -> {
                val cmd = Protocol.PeerIdentified(event.peerId)
                system.tell(cmd)
            }

            else -> Result.success(Unit)
        }.onFailure {
            log.error { "Error while processing LIBP2P Event: $event" }
        }
    }

    val libble: LibbleModule = LibbleModule().setEventHandler { event ->
        log.info { "LIBBLE Event: $event" }
        when (event) {
            is LibbleEvent.ContactExchange.Failed -> {
                log.warn("LIBBLE Event: exchange failed: ${event.reason}")
                Result.success(Unit)
            }

            else -> Result.success(Unit)
        }.onFailure {
            log.error { "Error while processing LIBBLE Event: $event" }
        }
    }

    val libnet: LibnetModule = LibnetModule(libble, libp2p)

    fun initialize(system: ActorRef) {
        this.system = system
    }

    fun initialize(identity: Identity, contactLookup: (String) -> Contact?) {
        this.identity = identity
        this.contactLookup = contactLookup
    }

    suspend fun initializeDatabase() {
        db = sqlite(
            url = database,
            options = ConnectionPool.Options(
                minConnections = 1,
                maxConnections = 1,
            ),
        ).apply {
            migrate(
                files = migrations,
                afterStatementExecution = { s, d -> log.info { "DB: Executed: $s ($d)" } },
                afterFileMigration = { f, d -> log.info { "DB: Migrated: $f ($d)" } },
            ).onFailure { e ->
                log.info { "DB: Failed reading from database: $e" }
                launch {
                    delay(2.seconds)
                    io.github.smyrgeorge.freepath.util.exitApplication(1)
                }
            }
        }
    }

    suspend fun closeDatabase() {
        db.close().getOrThrow()
    }

    suspend fun startLibp2p() {
        libp2p.start(peerId = identity.peerId, sigKeyPrivate = identity.sigKeyPrivate)
    }

    suspend fun stopLibp2p() {
        libp2p.stop()
    }

    suspend fun startLibble() {
        if (!LIBBLE_ENABLED) return
        libble.start(
            bleBeaconId = bleBeaconId(identity.peerIdRaw),
            peripheralIdLookup = {
                contactRoutingEntryRepository.findOneByPeerId(db, it)
                    .getOrNull()?.blePeripheralId
            },
            peerIdLookup = {
                contactRoutingEntryRepository.findOneByBlePeripheralId(db, it)
                    .getOrNull()?.peerId
            },
            peerIdByRawBytesLookup = { beaconId ->
                contactRepository.findAll(db).getOrNull()
                    ?.firstOrNull { entry ->
                        val sigKey = Base64.decode(entry.contact.sigKey)
                        val contactPeerIdRaw = CryptoProvider.sha256(sigKey)
                        bleBeaconId(contactPeerIdRaw).contentEquals(beaconId)
                    }?.peerId
            },
            onNewPeripheralId = { peerId, peripheralId ->
                val now = Clock.System.now()
                val existing = contactRoutingEntryRepository.findOneByPeerId(db, peerId).getOrNull()
                val entry = existing?.copy(blePeripheralId = peripheralId, bleUpdatedAt = now)
                    ?: ContactRoutingEntry(peerId = peerId, blePeripheralId = peripheralId, bleUpdatedAt = now)
                contactRoutingEntryRepository.save(db, entry).getOrThrow()
            },
        )
    }

    suspend fun stopLibble() {
        if (!LIBBLE_ENABLED) return
        libble.stop()
    }

    fun startLibnet() {
        libnet.start(
            peerId = identity.peerId,
            peerIdLookup = {
                contactRoutingEntryRepository.findOneByBlePeripheralId(db, it)
                    .getOrNull()?.peerId
            },
        )
        client = LibnetClient(
            identity = identity,
            libnet = libnet,
            contactLookup = contactLookup,
            onChatMessageReceived = { msg ->
                val cmd = Protocol.ChatMessageReceived(msg.senderId, msg.receiverId, msg)
                system.tell(cmd).map { }
                    .onFailure { log.error { "Failed to deliver chat message to the system actor: $it" } }
            },
            onContentReceived = { content ->
                val cmd = Protocol.ContentReceived(content)
                system.tell(cmd).map { }
                    .onFailure { log.error { "Failed to deliver content to the system actor: $it" } }
            },
        ).apply {
            start()
        }
    }

    fun stopLibnet() {
        client.stop()
        libnet.stop()
    }

    companion object {
        internal const val LIBBLE_ENABLED = false
        private val BLE_BEACON_DOMAIN = "freepath-ble-beacon".encodeToByteArray()

        /**
         * Derives a stable, purpose-specific 8-byte BLE beacon identifier from peerIdRaw.
         * SHA-256(peerIdRaw ∥ "freepath-ble-beacon")[:8] — opaque and independent from peerId.
         */
        private fun bleBeaconId(peerIdRaw: ByteArray): ByteArray =
            CryptoProvider.sha256(peerIdRaw + BLE_BEACON_DOMAIN).copyOfRange(0, 8)
    }
}
