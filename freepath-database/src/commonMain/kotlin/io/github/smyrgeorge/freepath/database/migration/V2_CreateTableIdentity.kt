package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V2_CreateTableIdentity = """
CREATE TABLE IF NOT EXISTS identity
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    peer_id    TEXT    NOT NULL,
    identity   TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS identity_peer_id_uidx ON identity (peer_id);
"""
