package io.github.smyrgeorge.freepath.util.db

import kotlin.time.Instant

interface Auditable<ID> {
    val id: ID
    var createdAt: Instant
    var updatedAt: Instant
}