package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.contact.Contact
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
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
    val contentRepository: ContentEntryRepository = ContentEntryRepositoryImpl
    val contentSyncRepository: ContentSyncEntryRepository = ContentSyncEntryRepositoryImpl
    val contactRoutingRepository: ContactRoutingEntryRepository = ContactRoutingEntryRepositoryImpl

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
            localPeerId = identity.peerId,
            peripheralIdLookup = {
                contactRoutingRepository.findOneByPeerId(db, it)
                    .getOrNull()?.blePeripheralId
            },
            contactSecretsLookup = {
                contactRoutingRepository.findAllByIdentitySecretNotNull(db)
                    .getOrDefault(emptyList())
                    .mapNotNull { entry ->
                        entry.bleIdentitySecret?.let { b64 ->
                            runCatching { entry.peerId to kotlin.io.encoding.Base64.decode(b64) }.getOrNull()
                        }
                    }.toMap()
            },
            onPeripheralIdentified = { peerId, peripheralId ->
                val now = kotlin.time.Clock.System.now()
                val existing = contactRoutingRepository.findOneByPeerId(db, peerId).getOrNull()
                if (existing != null && existing.blePeripheralId != peripheralId) {
                    contactRoutingRepository.save(db, existing.copy(blePeripheralId = peripheralId, bleUpdatedAt = now))
                }
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
        internal const val LIBBLE_ENABLED = true
    }
}
