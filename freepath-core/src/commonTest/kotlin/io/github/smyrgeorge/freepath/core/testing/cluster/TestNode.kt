package io.github.smyrgeorge.freepath.core.testing.cluster

import io.github.smyrgeorge.actor4k.actor.ref.ActorRef
import io.github.smyrgeorge.freepath.core.actor.AppProtocol
import io.github.smyrgeorge.freepath.core.state.service.Service.Companion.db
import io.github.smyrgeorge.freepath.core.testing.state.TestAppResources
import io.github.smyrgeorge.freepath.core.testing.state.TestAppState
import io.github.smyrgeorge.freepath.core.testing.state.TestViewState
import io.github.smyrgeorge.freepath.core.testing.util.toContactCard
import io.github.smyrgeorge.freepath.database.ContactEncounterEntry
import io.github.smyrgeorge.freepath.database.ContactEntry
import io.github.smyrgeorge.freepath.database.ContentEntry
import io.github.smyrgeorge.freepath.database.MessageEntry
import io.github.smyrgeorge.freepath.database.RelayEntry
import io.github.smyrgeorge.freepath.model.contact.Contact
import io.github.smyrgeorge.freepath.model.contact.Identity
import io.github.smyrgeorge.freepath.model.content.ContentBody

/**
 * One running node in a [TestCluster]: its own actors, [TestAppState]/[TestViewState], isolated
 * in-memory DB and [io.github.smyrgeorge.freepath.core.testing.fake.FakeLibp2pModule]. The convenience methods drive the node the way the UI would
 * — through the [AppProtocol] — so tests exercise the real actor/service path.
 */
class TestNode internal constructor(
    val id: String,
    val resources: TestAppResources,
    val state: TestAppState,
    val viewState: TestViewState,
) {
    lateinit var appRef: ActorRef private set
    lateinit var contactExchangeRef: ActorRef private set

    /** This node's libp2p peerId. Initialised once the node has booted. */
    lateinit var peerId: String private set

    internal fun attachRefs(app: ActorRef, contactExchange: ActorRef) {
        appRef = app
        contactExchangeRef = contactExchange
    }

    internal fun bindPeerId(value: String) {
        peerId = value
    }

    private var onboarded = false

    /**
     * Complete onboarding once with a unique profile (a distinct `bio`), giving this node a
     * non-empty, per-node contact content. Without it, empty contact bodies across peers hash to
     * the same `content_id` and collide on the unique index. Idempotent.
     */
    suspend fun ensureOnboarded() {
        if (onboarded) return
        state.completeOnboarding(name = id, bio = "bio-$id", location = null, avatar = null)
        onboarded = true
    }

    /**
     * Make [peer] a known contact: seed the peer's (unique) contact content first so loading
     * contacts finds it instead of inserting an empty placeholder, then store the peer's card so
     * messages can be encrypted to / verified from them. [peer] must be onboarded first.
     */
    suspend fun addContact(peer: TestNode) {
        state.receiveContent(peer.state.contactContent)
        state.acceptContact(peer.contactCard())
    }

    /** This node's own cryptographic identity (auto-generated on first boot). */
    val identity: Identity get() = state.identity

    /** The public contact card other nodes store in order to message this node. */
    fun contactCard(name: String? = null): Contact = identity.toContactCard(name)

    /** Send a chat message to [to] — same path as the UI (AppActor seals, persists, sends). */
    suspend fun sendMessage(to: TestNode, text: String) {
        appRef.tell(AppProtocol.SendMessage(peerId = to.peerId, text = text)).getOrThrow()
    }

    /** Publish content authored by this node. */
    suspend fun publish(body: ContentBody) {
        appRef.tell(AppProtocol.PublishContent(body)).getOrThrow()
    }

    /** Current in-memory chat with [other] (the chat map is keyed by the other node's peerId). */
    fun chatWith(other: TestNode): List<MessageEntry> = state.chats.value[other.peerId] ?: emptyList()

    /** Snapshot of this node's store-and-forward relay queue (its pending mesh mailbox). */
    suspend fun relayQueue(): List<RelayEntry> = resources.relayService.db { findAll(limit = 256) }

    /** This node's local (never-shared) encounter record for [peer], if any — the encounter heuristic. */
    suspend fun encounterWith(peer: TestNode): ContactEncounterEntry? =
        resources.contactEncounterService.db { getByPeerId(peer.peerId) }

    /** Currently loaded contacts (excludes self). */
    val contacts: List<ContactEntry> get() = state.contacts.value

    /** Currently loaded feed entries. */
    val feed: List<ContentEntry> get() = state.feedEntries.value

    /** Peers currently reachable on any transport (per the fake libp2p metrics). */
    val onlinePeers: Set<String> get() = resources.libp2p.metrics.value.value.identifiedPeers
}
