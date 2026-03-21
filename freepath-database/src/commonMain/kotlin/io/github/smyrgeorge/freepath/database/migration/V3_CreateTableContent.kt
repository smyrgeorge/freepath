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
    root_id            TEXT    NOT NULL,
    prev_id            TEXT,
    type               TEXT    NOT NULL,
    author_id          TEXT    NOT NULL,
    version            INTEGER NOT NULL,
    is_latest          INTEGER NOT NULL DEFAULT 1,
    content_created_at INTEGER NOT NULL,
    expires_at         INTEGER,
    comments_enabled   INTEGER NOT NULL,
    visibility         TEXT    NOT NULL,
    parent_id          TEXT,
    parent_root_id     TEXT,
    hops               INTEGER NOT NULL DEFAULT 0,
    envelope           TEXT    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS content_content_id_uidx     ON content (content_id);
CREATE        INDEX IF NOT EXISTS content_root_id_idx          ON content (root_id);
CREATE        INDEX IF NOT EXISTS content_author_id_idx        ON content (author_id);
CREATE        INDEX IF NOT EXISTS content_type_created_at_idx  ON content (type, content_created_at DESC);
CREATE        INDEX IF NOT EXISTS content_parent_root_id_idx   ON content (parent_root_id);
CREATE        INDEX IF NOT EXISTS content_is_latest_idx        ON content (is_latest);
"""
