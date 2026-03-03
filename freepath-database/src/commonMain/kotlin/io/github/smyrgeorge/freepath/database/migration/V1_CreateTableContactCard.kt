package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V1_CreateTableContactCard = """
CREATE TABLE IF NOT EXISTS contact
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    node_id      TEXT    NOT NULL,
    card         TEXT    NOT NULL,
    trust_level  TEXT    NOT NULL,
    name         TEXT,
    last_seen_at INTEGER,
    notes        TEXT,
    pinned       INTEGER NOT NULL DEFAULT 0,
    muted        INTEGER NOT NULL DEFAULT 0,
    tags         TEXT    NOT NULL DEFAULT ''
);

CREATE UNIQUE INDEX IF NOT EXISTS contact_node_id_uidx ON contact (node_id);
CREATE INDEX IF NOT EXISTS contact_pinned_idx ON contact (pinned);
CREATE INDEX IF NOT EXISTS contact_last_seen_at_idx ON contact (last_seen_at);
"""