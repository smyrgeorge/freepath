package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V15_CreateTableRelayOffered = """
CREATE TABLE IF NOT EXISTS relay_offered
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL,
    -- The relay replica that has been offered (references relay.id). No FK is declared: SQLite has
    -- foreign_keys OFF, so orphaned rows are reaped by RelayService.deleteOrphanedOffered() on sweep.
    relay_entry_id INTEGER NOT NULL,
    -- The peer the replica was offered to.
    peer_id        TEXT    NOT NULL
);
-- Dedup: at most one offered record per (replica, peer). Makes markOffered idempotent.
CREATE UNIQUE INDEX IF NOT EXISTS relay_offered_entry_peer_uidx ON relay_offered (relay_entry_id, peer_id);
-- Per-pass load of all replicas already offered to a peer.
CREATE        INDEX IF NOT EXISTS relay_offered_peer_id_idx     ON relay_offered (peer_id);
"""
