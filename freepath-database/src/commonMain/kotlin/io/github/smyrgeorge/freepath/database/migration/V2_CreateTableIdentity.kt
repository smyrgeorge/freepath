package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V2_CreateTableIdentity = """
CREATE TABLE IF NOT EXISTS identity
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    node_id    TEXT    NOT NULL,
    data       TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS identity_node_id_uidx ON identity (node_id);
"""
