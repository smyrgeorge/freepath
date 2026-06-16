package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V13_ReworkRelayTableForSprayAndWait = """
DROP TABLE IF EXISTS relay;
CREATE TABLE relay
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    envelope   JSON    NOT NULL,
    -- Mutable per-replica Spray-and-Wait budget. Seeded from the envelope, then decremented on spray.
    copies     INTEGER NOT NULL,
    -- Read-only routing fields derived from the envelope JSON (managed by SQLite).
    expires_at INTEGER NOT NULL GENERATED ALWAYS AS (json_extract(envelope, '$.relay.expiresAt')) STORED,
    message_id TEXT    NOT NULL GENERATED ALWAYS AS (json_extract(envelope, '$.relay.messageId')) STORED
);
-- Dedup: at most one stored replica per message.
CREATE UNIQUE INDEX IF NOT EXISTS relay_message_id_uidx      ON relay (message_id);
-- Pass-1 direct-delivery lookup by recipient.
CREATE        INDEX IF NOT EXISTS relay_receiver_id_hash_idx ON relay (json_extract(envelope, '$.receiverIdHash'));
-- Lifecycle sweep scans.
CREATE        INDEX IF NOT EXISTS relay_expires_at_idx       ON relay (expires_at);
"""
