package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V12_ReworkRelayTable = """
DROP TABLE IF EXISTS relay;
CREATE TABLE relay
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    envelope   JSON    NOT NULL,
    ttl        INTEGER NOT NULL GENERATED ALWAYS AS (json_extract(envelope, '$.relay.ttl')) STORED
);
CREATE INDEX IF NOT EXISTS relay_receiver_id_hash_idx ON relay (json_extract(envelope, '$.receiverIdHash'));
CREATE INDEX IF NOT EXISTS relay_ttl_idx ON relay (ttl);
"""
