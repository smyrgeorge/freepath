package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V11_ChangeRelayPayloadToText = """
DROP TABLE relay;
CREATE TABLE relay
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    receiver_peer_id TEXT    NOT NULL,
    payload          TEXT    NOT NULL,
    expire_at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS relay_receiver_peer_id_expire_at_idx ON relay (receiver_peer_id, expire_at);
"""
