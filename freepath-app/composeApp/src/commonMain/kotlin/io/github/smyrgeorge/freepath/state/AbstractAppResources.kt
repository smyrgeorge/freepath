package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.client.AppClient
import io.github.smyrgeorge.freepath.contact.ContactCard
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactCardEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libble.exchange.BleContactExchange
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.freepath.util.exitApplication
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
    lateinit var client: AppClient private set

    lateinit var identity: Identity
    lateinit var identityEntry: IdentityEntry
    lateinit var contactLookup: (String) -> ContactCard?

    val identityRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl
    val contactCardRepository: ContactCardEntryRepository = ContactCardEntryRepositoryImpl
    val contentEntryRepository: ContentEntryRepository = ContentEntryRepositoryImpl

    val libp2p: Libp2pModule = Libp2pModule().setEventHandler { event ->
        log.info { "Libp2pEvent: $event" }
        when (event) {
            is Libp2pEvent.PeerIdentified -> {
                val cmd = Protocol.PeerIdentified(event.peerId)
                system.tell(cmd).getOrThrow()
            }

            else -> Unit
        }
    }

    val libble: LibbleModule = LibbleModule().setEventHandler { event ->
        when (event) {
            is LibbleEvent.ContactCardReceived -> {
                log.info { "LibbleEvent: $event" }
                val (pin, card) = BleContactExchange.decodeWithPin(event.cardBytes).getOrThrow()
                val cmd = Protocol.IncomingContactExchange(card.peerId, pin, card)
                system.tell(cmd).getOrThrow()
            }

            else -> Unit
        }
    }

    fun initialize(system: ActorRef) {
        this.system = system
    }

    fun initializeAppClient(state: AbstractAppState) {
        this.client = AppClient(system, state, this)
    }

    suspend fun initializeDatabase() {
        db = sqlite(
            url = database,
            options = ConnectionPool.Options(
                minConnections = 1,
                maxConnections = 1,
            ),
        ).also {
            it.migrate(
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

    suspend fun startLibp2p(
        peerId: String,
        sigKeyPrivate: ByteArray,
        identityEntry: IdentityEntry,
        contactLookup: (String) -> ContactCard?,
    ) {
        this.identity = identityEntry.identity
        this.identityEntry = identityEntry
        this.contactLookup = contactLookup
        libp2p.start(peerId = peerId, sigKeyPrivate = sigKeyPrivate)
    }

    suspend fun stopLibp2p() {
        libp2p.stop()
    }

    suspend fun startupLibble(
        localCard: ContactCard,
        sigKeyPrivate: ByteArray
    ) {
        libble.start()
        libble.startGattServer(BleContactExchange.encode(localCard, sigKeyPrivate))
    }

    suspend fun stopLibble() {
        libble.stop()
    }
}
