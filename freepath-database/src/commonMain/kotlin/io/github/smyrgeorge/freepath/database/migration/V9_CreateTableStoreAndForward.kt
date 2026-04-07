package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V9_CreateTableStoreAndForward = """
CREATE TABLE IF NOT EXISTS store_and_forward
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at       INTEGER NOT NULL,
    updated_at       INTEGER NOT NULL,
    receiver_peer_id TEXT    NOT NULL,
    payload          BLOB    NOT NULL,
    expire_at        INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS store_and_forward_receiver_peer_id_expire_at_idx ON store_and_forward (receiver_peer_id, expire_at);
"""
