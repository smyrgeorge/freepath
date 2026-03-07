package io.github.smyrgeorge.freepath

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.AppState.identity
import io.github.smyrgeorge.freepath.AppState.identityEntry
import io.github.smyrgeorge.freepath.database.ContactCardEntryRepository
import io.github.smyrgeorge.freepath.database.IdentityEntryRepository
import io.github.smyrgeorge.freepath.database.generated.ContactCardEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.generated.IdentityEntryRepositoryImpl
import io.github.smyrgeorge.freepath.database.migration.migrations
import io.github.smyrgeorge.freepath.database.sqlite
import io.github.smyrgeorge.freepath.transport.StatefulProtocol
import io.github.smyrgeorge.freepath.transport.lan.LanLinkAdapter
import io.github.smyrgeorge.freepath.transport.lan.createPeerDiscovery
import io.github.smyrgeorge.freepath.util.codec.Base58
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.RootLogger
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlin.io.encoding.Base64

object AppResources {
    @Suppress("unused")
    private val rootLogger = RootLogger // Do not delete this line.
    private val log = Logger.of(this::class)

    lateinit var system: ActorRef private set

    lateinit var db: ISQLite private set
    lateinit var lanAdapter: LanLinkAdapter private set
    lateinit var lanProtocol: StatefulProtocol private set

    val identityRepository: IdentityEntryRepository = IdentityEntryRepositoryImpl
    val contactCardRepository: ContactCardEntryRepository = ContactCardEntryRepositoryImpl

    fun initialize(system: ActorRef) {
        this.system = system
    }

    suspend fun openDatabase() {
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

    suspend fun closeDatabase() {
        db.close().getOrThrow()
    }

    suspend fun startupLan() {
        val nodeId = identityEntry.nodeId

        lanAdapter = LanLinkAdapter(
            peerDiscovery = createPeerDiscovery(nodeId),
            onPeerDisconnected = { peerId ->
                log.warn { "Peer $peerId disconnected" }
                system.tell(Protocol.PeerDisconnected(peerId))
                lanProtocol.closeSession(peerId)
            },
            isKnownPeer = { peerId -> contactCardRepository.findOneByNodeId(db, peerId).getOrThrow() != null },
            onConnectionEstablished = { peerId ->
                log.info { "Connected to $peerId — starting handshake" }
                system.tell(Protocol.PeerConnected(peerId))
                lanProtocol.initiateHandshake(peerId)
            },
            onInboundConnectionEstablished = { peerId ->
                log.info { "Inbound connection from $peerId" }
                system.tell(Protocol.PeerConnected(peerId))
            },
            onPeerDiscovered = { peerId ->
                log.info { "Peer discovered via mDNS: $peerId" }
                system.tell(Protocol.PeerDiscovered(peerId))
            },
            onPeerLost = { peerId ->
                log.info { "Peer lost via mDNS: $peerId" }
                system.tell(Protocol.PeerLost(peerId))
            },
            onIdleTimeout = { peerId ->
                log.info { "Idle timeout for $peerId — sending CLOSE" }
                lanProtocol.closeSession(peerId)
            },
        )

        lanProtocol = StatefulProtocol(
            identity = identity,
            contactLookup = { nodeIdRaw ->
                val nodeId = Base58.encode(nodeIdRaw)
                val contact = contactCardRepository.findOneByNodeId(db, nodeId).getOrThrow()
                contact?.let { Base64.decode(it.card.sigKey) }
            },
            linkAdapter = lanAdapter,
            onFrameReceived = { peerId, _, _ -> log.info { "Frame received from $peerId" } },
        )
        lanProtocol.start()
        log.info { "LAN protocol started on port ${lanAdapter.localPort}" }
    }

    suspend fun shutdownLan() {
        lanProtocol.stop()
    }
}