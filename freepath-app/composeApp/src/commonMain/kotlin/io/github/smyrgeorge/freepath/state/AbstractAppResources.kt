package io.github.smyrgeorge.freepath.state

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.Protocol
import io.github.smyrgeorge.freepath.contact.Identity
import io.github.smyrgeorge.freepath.contact.exchange.LanContactExchange
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
import io.github.smyrgeorge.freepath.util.exitApplication
import io.github.smyrgeorge.log4k.Logger
import io.github.smyrgeorge.log4k.impl.extensions.launch
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

abstract class AbstractAppResources(
    private val database: String
) {
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

    suspend fun startupLan(nodeId: String, identity: Identity) {
        fun Result<*>.logError(): Unit =
            if (isFailure) {
                val error = exceptionOrNull() ?: IllegalStateException("Uknown error")
                log.warn(error) { "Lan: $this" }
            } else Unit

        lanAdapter = LanLinkAdapter(
            peerDiscovery = createPeerDiscovery(nodeId),
            onPeerDisconnected = { peerId ->
                log.warn { "Peer $peerId disconnected" }
                system.tell(Protocol.PeerDisconnected(peerId)).logError()
                lanProtocol.closeSession(peerId)
            },
            isKnownPeer = { peerId -> contactCardRepository.findOneByNodeId(db, peerId).getOrThrow() != null },
            onConnectionEstablished = { peerId ->
                log.info { "Connected to $peerId — starting handshake" }
                system.tell(Protocol.PeerConnected(peerId)).logError()
                lanProtocol.initiateHandshake(peerId)
            },
            onInboundConnectionEstablished = { peerId ->
                log.info { "Inbound connection from $peerId" }
                system.tell(Protocol.PeerConnected(peerId)).logError()
            },
            onPeerDiscovered = { peerId ->
                log.info { "Peer discovered via mDNS: $peerId" }
                system.tell(Protocol.PeerDiscovered(peerId)).logError()
            },
            onPeerLost = { peerId ->
                log.info { "Peer lost via mDNS: $peerId" }
                system.tell(Protocol.PeerLost(peerId)).logError()
            },
            onIdleTimeout = { peerId ->
                log.info { "Idle timeout for $peerId — sending CLOSE" }
                lanProtocol.closeSession(peerId)
            },
            onExchangeRequested = { pin, peerCardBytes ->
                val deferred = CompletableDeferred<ByteArray?>()
                val msg = Protocol.IncomingContactExchange(pin, peerCardBytes, LanContactExchange, deferred)
                system.tell(msg).logError()
                deferred.await()
            },
        )

        lanProtocol = StatefulProtocol(
            identity = identity,
            contactLookup = { peerIdRaw ->
                val nodeId = Base58.encode(peerIdRaw)
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
