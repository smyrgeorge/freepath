package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V3_CreateTableContent = """
CREATE TABLE IF NOT EXISTS content
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    content_id         TEXT    NOT NULL,
    type               TEXT    NOT NULL,
    author_id          TEXT    NOT NULL,
    version            INTEGER NOT NULL,
    content            TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS content_content_id_uidx ON content (content_id);
CREATE        INDEX IF NOT EXISTS content_author_id_idx  ON content (author_id);
"""
