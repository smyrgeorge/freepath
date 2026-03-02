package io.github.smyrgeorge.freepath.contact

import io.github.smyrgeorge.freepath.util.db.AuditableRepository
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface ContactCardEntryRepository : AuditableRepository<ContactCardEntry> {
}