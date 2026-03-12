package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.client.AppClient
import io.github.smyrgeorge.freepath.client.model.ContactInfo
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntry
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactCardEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.freepath.libp2p.metrics.Libp2pMetricsSnapshot
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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
    lateinit var contactLookup: (ByteArray) -> ContactInfo?

    val identityRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl
    val contactCardRepository: ContactCardEntryRepository = ContactCardEntryRepositoryImpl

    val libp2p: Libp2pModule = Libp2pModule().also { module ->
        module.setEventHandler { event ->
            log.info { "Libp2pEvent: $event" }
        }
    }
    val libp2pMetrics: StateFlow<Libp2pMetricsSnapshot> = libp2p.metrics.value

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
        nodeId: String,
        sigKeyPrivate: ByteArray,
        identityEntry: IdentityEntry,
        contactLookup: (ByteArray) -> ContactInfo?,
    ) {
        this.identity = identityEntry.data
        this.identityEntry = identityEntry
        this.contactLookup = contactLookup
        libp2p.start(nodeId = nodeId, sigKeyPrivate = sigKeyPrivate)
    }

    suspend fun stopLibp2p() {
        libp2p.stop()
    }
}
