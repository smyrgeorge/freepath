package io.github.smyrgeorge.freepath.database.util

import kotlin.time.Instant

interface Auditable<ID> {
    val id: ID
    var createdAt: Instant
    var updatedAt: Instant
}
