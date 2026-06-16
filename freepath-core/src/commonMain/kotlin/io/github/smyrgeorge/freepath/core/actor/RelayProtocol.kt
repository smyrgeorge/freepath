package io.github.smyrgeorge.freepath.core.actor

import io.github.smyrgeorge.actor4k.actor.ActorProtocol
import io.github.smyrgeorge.freepath.database.RelayEntry

sealed interface RelayProtocol : ActorProtocol {
    sealed class Request<R : ActorProtocol.Response> : RelayProtocol, ActorProtocol.Message<R>()
    sealed class Response : ActorProtocol.Response()

    data object Ok : Response()

    /** Carries the persisted master replica back to the caller (for its id / logging). */
    data class Stored(val entry: RelayEntry) : Response()

    /** Carries the reserved copy to send, or `null` when the entry is in the wait phase / gone. */
    data class Reserved(val entry: RelayEntry?) : Response()

    /** Persist a freshly-sealed master replica (copies = L). Reply once it is durably committed. */
    data class Enqueue(val entry: RelayEntry) : Request<Stored>()

    /**
     * Atomically reserve the sprayed half of an entry's copy budget (binary Spray-and-Wait split).
     * Reply carries the copy to send onward, or `null` if only one copy is left (wait phase) or the
     * entry is gone.
     */
    data class ReserveSpray(val entryId: Int) : Request<Reserved>()

    /** A replica was handed off / delivered — drop it from the queue. */
    data class Delivered(val entryId: Int) : Request<Ok>()

    /** Periodic lifecycle sweep: drop expired / over-aged replicas. */
    data object Sweep : Request<Ok>()
}
