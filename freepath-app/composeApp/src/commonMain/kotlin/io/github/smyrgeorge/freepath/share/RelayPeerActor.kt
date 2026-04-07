package io.github.smyrgeorge.freepath.share

import io.github.smyrgeorge.actor4k.actor.Actor
import io.github.smyrgeorge.actor4k.actor.Behavior
import io.github.smyrgeorge.freepath.database.RelayEntryRepository
import io.github.smyrgeorge.freepath.libnet.client.LibnetClient
import io.github.smyrgeorge.freepath.state.AbstractAppResources
import io.github.smyrgeorge.freepath.state.abbrev
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

class RelayPeerActor(
    key: String,
    resources: AbstractAppResources,
) : Actor<RelayPeerProtocol, RelayPeerProtocol.Response>(key) {

    private val peerId: String get() = key

    private val db: ISQLite = resources.db
    private val client: LibnetClient = resources.client
    private val relayRepository: RelayEntryRepository = resources.relayRepository

    override suspend fun onReceive(m: RelayPeerProtocol): Behavior<RelayPeerProtocol.Response> {
        when (m) {
            is RelayPeerProtocol.Relay -> relay()
        }
        return Behavior.Reply(RelayPeerProtocol.Ok)
    }

    private suspend fun relay() {
        relayRepository.findAllByLimit(db, RELAY_FETCH_LIMIT)
            .getOrElse {
                log.error("[${peerId.abbrev()}] Failed to load relay queue: ${it.message}")
                return
            }.forEach { entry ->
                client.relay(entry.payload, peerId)
                    .onSuccess {
                        // Only delete the entry if it was successfully delivered to its intended
                        // recipient. If the receiver peer ID does not match (e.g. the packet was
                        // accepted by a relay hop but not the final destination), we leave it in
                        // the store so it can be retried on a future connection and expires
                        // naturally via its TTL.
                        if (peerId == entry.receiverPeerId) {
                            relayRepository.deleteById(db, entry.id).onFailure {
                                log.error("[${peerId.abbrev()}] Failed to delete relay entry ${entry.id}: ${it.message}")
                            }
                        }
                        log.info("[${peerId.abbrev()}] Forwarded relay packet ${entry.id}")
                    }.onFailure {
                        log.warn("[${peerId.abbrev()}] Failed to forward relay packet ${entry.id}: ${it.message}")
                    }
            }
    }

    companion object {
        const val RELAY_FETCH_LIMIT = 256
    }
}
