package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V14_CreateTableContactEncounter = """
CREATE TABLE IF NOT EXISTS contact_encounter
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    peer_id         TEXT    NOT NULL,
    last_seen_at    INTEGER NOT NULL,
    count           INTEGER NOT NULL
);
-- One encounter row per peer (upsert by peerId).
CREATE UNIQUE INDEX IF NOT EXISTS contact_encounter_peer_id_uidx ON contact_encounter (peer_id);
"""
