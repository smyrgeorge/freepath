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
import io.github.smyrgeorge.freepath.database.MessageEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContactRoutingEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentSyncEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.MessageEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.Transport
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
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
    val messageRepository: MessageEntryRepository = MessageEntryRepositoryImpl

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
                    exitApplication(1)
                }
            }
        }
    }

    suspend fun closeDatabase() {
        db.close().getOrThrow()
    }

    suspend fun startNetworking() {
        if (Transport.LIBP2P.isSupported) startLibp2p()
        if (Transport.LIBBLE.isSupported) startLibble()

        libnet.start(peerId = identity.peerId)
        client = LibnetClient(
            identity = identity,
            libnet = libnet,
            contactLookup = contactLookup,
            onMessageReceived = { msg ->
                val cmd = Protocol.MessageReceived(msg)
                system.tell(cmd).map { }
                    .onFailure { log.error { "Failed to deliver message to the system actor: $it" } }
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

    suspend fun stopNetworking() {
        client.stop()
        libnet.stop()
        if (Transport.LIBP2P.isSupported) libp2p.stop()
        if (Transport.LIBBLE.isSupported) libble.stop()
    }

    private suspend fun startLibp2p() {
        libp2p.start(peerId = identity.peerId, sigKeyPrivate = identity.sigKeyPrivate)
    }

    private suspend fun startLibble() {
        libble.start(
            localPeerId = identity.peerId,
            contactSecretsLookup = {
                contactRoutingRepository.findAllByIdentitySecretNotNull(db)
                    .getOrDefault(emptyList())
                    .mapNotNull { entry ->
                        entry.bleIdentitySecret?.let { b64 ->
                            runCatching { entry.peerId to Base64.decode(b64) }.getOrNull()
                        }
                    }.toMap()
            },
        )
    }
}
