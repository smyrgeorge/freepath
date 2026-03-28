package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V4_CreateTableContactRouting = """
CREATE TABLE IF NOT EXISTS contact_routing
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    peer_id           TEXT    NOT NULL,
    ble_peripheral_id TEXT,
    ble_updated_at    INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS contact_routing_peer_id_uidx ON contact_routing (peer_id);
"""
