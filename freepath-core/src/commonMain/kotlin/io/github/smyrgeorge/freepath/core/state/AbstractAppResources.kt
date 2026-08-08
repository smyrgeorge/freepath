package io.github.smyrgeorge.freepath.core.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.actor4k.system.ActorSystem
import io.github.smyrgeorge.actor4k.util.extentions.launch
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.actor.RelayActor
import io.github.smyrgeorge.freepath.core.actor.RelayProtocol
import io.github.smyrgeorge.freepath.core.state.service.ContactEncounterService
import io.github.smyrgeorge.freepath.core.state.service.ContactService
import io.github.smyrgeorge.freepath.core.state.service.ContentService
import io.github.smyrgeorge.freepath.core.state.service.IdentityService
import io.github.smyrgeorge.freepath.core.state.service.MessageService
import io.github.smyrgeorge.freepath.core.state.service.RelayService
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.database.ContactEncounterEntryRepository
import io.github.smyrgeorge.freepath.database.ContactEntryRepository
import io.github.smyrgeorge.freepath.database.ContactRoutingEntryRepository
import io.github.smyrgeorge.freepath.database.ContentEntryRepository
import io.github.smyrgeorge.freepath.database.ContentSyncEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.MessageEntryRepository
import io.github.smyrgeorge.freepath.database.RelayEntry.Companion.toRelayEntry
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.freepath.database.RelayOfferedEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactEncounterEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContactEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContactRoutingEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.ContentSyncEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.MessageEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.RelayEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.RelayOfferedEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.libble.LibbleEvent
import io.github.smyrgeorge.freepath.libble.LibbleModule
import io.github.smyrgeorge.freepath.libnet.LibnetModule
import io.github.smyrgeorge.freepath.libnet.LibnetModuleImpl
import io.github.smyrgeorge.freepath.libnet.Transport
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.libnet.client.LibnetClientImpl
import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent
import io.github.smyrgeorge.freepath.libp2p.Libp2pModule
import io.github.smyrgeorge.freepath.libp2p.defaultListenAddrs
import io.github.smyrgeorge.freepath.libp2p.defaultRelayAddrs
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.classic.error
import io.github.smyrgeorge.log4k.classic.info
import io.github.smyrgeorge.log4k.classic.warn
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

abstract class AbstractAppResources(
    private val database: String,
    libp2pModule: Libp2pModule,
    libbleModule: LibbleModule,
) {
    private val log = Logger.of(this::class)

    lateinit var db: ISQLite private set
    lateinit var app: ActorRef private set
    lateinit var contactExchange: ActorRef private set
    lateinit var client: LibnetClient private set

    lateinit var identity: Identity
    lateinit var contactLookup: (String) -> Contact?

    val identityRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl
    val contactEncounterRepository: ContactEncounterEntryRepository = ContactEncounterEntryRepositoryImpl
    val contactRepository: ContactEntryRepository = ContactEntryRepositoryImpl
    val contentRepository: ContentEntryRepository = ContentEntryRepositoryImpl
    val contentSyncRepository: ContentSyncEntryRepository = ContentSyncEntryRepositoryImpl
    val contactRoutingRepository: ContactRoutingEntryRepository = ContactRoutingEntryRepositoryImpl
    val messageRepository: MessageEntryRepository = MessageEntryRepositoryImpl
    val relayRepository: RelayEntryRepository = RelayEntryRepositoryImpl
    val relayOfferedRepository: RelayOfferedEntryRepository = RelayOfferedEntryRepositoryImpl

    val identityService: IdentityService by lazy {
        IdentityService(
            db = db,
            identityRepository = identityRepository,
        )
    }
    val contactEncounterService: ContactEncounterService by lazy {
        ContactEncounterService(
            db = db,
            repository = contactEncounterRepository,
        )
    }
    val contactService: ContactService by lazy {
        ContactService(
            db = db,
            identityService = identityService,
            contactRepository = contactRepository,
            contactRoutingRepository = contactRoutingRepository,
        )
    }
    val contentService: ContentService by lazy {
        ContentService(
            db = db,
            identityService = identityService,
            contactService = contactService,
            contentRepository = contentRepository,
            contentSyncRepository = contentSyncRepository,
        )
    }
    val messageService: MessageService by lazy {
        MessageService(
            db = db,
            identityService = identityService,
            messageRepository = messageRepository,
        )
    }
    val relayService: RelayService by lazy {
        RelayService(
            db = db,
            relayRepository = relayRepository,
            relayOfferedRepository = relayOfferedRepository,
        )
    }

    val libp2p: Libp2pModule = libp2pModule.setEventHandler { event ->
        log.info { "LIBP2P Event: $event" }
        when (event) {
            is Libp2pEvent.PeerConnected -> {
                val cmd = AppProtocol.PeerConnected(event.peerId)
                app.tell(cmd)
            }

            is Libp2pEvent.PeerIdentified -> {
                val cmd = AppProtocol.PeerIdentified(event.peerId)
                app.tell(cmd)
            }

            else -> Result.success(Unit)
        }.onFailure {
            log.error { "Error while processing LIBP2P Event: $event" }
        }
    }

    val libble: LibbleModule = libbleModule.setEventHandler { event ->
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

    val libnet: LibnetModule = LibnetModuleImpl(libble, libp2p)

    fun initialize(app: ActorRef, contactExchange: ActorRef) {
        this.app = app
        this.contactExchange = contactExchange
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
        if (Transport.LIBP2P.supported) startLibp2p()
        if (Transport.LIBBLE.supported) startLibble()

        libnet.start(peerId = identity.peerId)
        client = LibnetClientImpl(
            identity = identity,
            libnet = libnet,
            contactLookup = contactLookup,
            onMessageReceived = { msg ->
                val cmd = AppProtocol.MessageReceived(msg)
                app.tell(cmd)
            },
            onContentReceived = { content ->
                val cmd = AppProtocol.ContentReceived(content)
                app.tell(cmd)
            },
            onRelayPacket = { envelope, fromPeerId ->
                // Admission is contact-gated at the connection layer (libp2p only accepts sessions
                // from known contacts), so only contacts can hand us relay packets.
                // TODO: tighten further — store only when receiverIdHash ∈ {my contacts}
                //   ("I relay only for my friends") and add per-contact rate limits + a store cap
                //   with eviction so a misbehaving contact can't churn the queue.
                if (envelope.relay == null) {
                    log.warn { "Received relay packet without relay metadata, dropping" }
                } else {
                    // Hand to the relay-queue owner so all writes share one serialization point. It
                    // persists the packet and re-distributes it onward — but never back to [fromPeerId],
                    // the peer we just received it from.
                    ActorSystem.get(RelayActor::class, RelayActor.key(identity.peerId))
                        .tell(RelayProtocol.Distribute(envelope.toRelayEntry(), fromPeerId = fromPeerId))
                        .onFailure { log.error { "Failed to store relay packet: ${it.message}" } }
                }
            },
        ).apply {
            start()
        }
    }

    suspend fun stopNetworking() {
        client.stop()
        libnet.stop()
        if (Transport.LIBP2P.supported) libp2p.stop()
        if (Transport.LIBBLE.supported) libble.stop()
    }

    private suspend fun startLibp2p() {
        libp2p.start(
            peerId = identity.peerId,
            sigKeyPrivate = identity.sigKeyPrivate,
            listenAddrs = defaultListenAddrs,
            relayAddrs = defaultRelayAddrs,
            contactLookup = { peerId -> contactLookup(peerId) != null },
        )
    }

    private suspend fun startLibble() {
        libble.start(
            localPeerId = identity.peerId,
            contactSecretsLookup = {
                contactService.db { getAllBleContactRouting() }.mapNotNull { entry ->
                    entry.bleIdentitySecret?.let { b64 ->
                        runCatching { entry.peerId to Base64.decode(b64) }.getOrNull()
                    }
                }.toMap()
            },
        )
    }
}
