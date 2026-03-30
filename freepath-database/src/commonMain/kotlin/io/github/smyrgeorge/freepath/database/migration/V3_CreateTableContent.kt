package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V3_CreateTableContent = """
CREATE TABLE IF NOT EXISTS content
(
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    content_id TEXT    GENERATED ALWAYS AS (json_extract(content, '$.id'))        STORED NOT NULL,
    type       TEXT    GENERATED ALWAYS AS (json_extract(content, '$.type'))      STORED NOT NULL,
    author_id  TEXT    GENERATED ALWAYS AS (json_extract(content, '$.authorId'))  STORED NOT NULL,
    version    INTEGER GENERATED ALWAYS AS (json_extract(content, '$.version'))   STORED NOT NULL,
    signature  TEXT    GENERATED ALWAYS AS (json_extract(content, '$.signature')) STORED NOT NULL,
    trust      TEXT    NOT NULL DEFAULT 'UNKNOWN',
    content    JSON    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS content_content_id_uidx  ON content (content_id);
CREATE        INDEX IF NOT EXISTS content_author_id_idx    ON content (author_id);
CREATE        INDEX IF NOT EXISTS content_created_at_idx   ON content (created_at);
"""
