package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V5_CreateTableContentSync = """
CREATE TABLE IF NOT EXISTS content_sync
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    peer_id    TEXT    NOT NULL,
    content_id TEXT    NOT NULL,
    version    INTEGER NOT NULL,
    synced_at  INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS content_sync_peer_id_content_id_uidx ON content_sync (peer_id, content_id);
"""
