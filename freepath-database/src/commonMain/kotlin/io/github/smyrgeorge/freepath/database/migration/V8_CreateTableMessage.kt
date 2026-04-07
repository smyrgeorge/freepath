package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V8_CreateTableMessage = """
CREATE TABLE IF NOT EXISTS message
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    message_id      TEXT    GENERATED ALWAYS AS (json_extract(message, '$.id'))             STORED NOT NULL,
    conversation_id TEXT    GENERATED ALWAYS AS (json_extract(message, '$.conversationId')) STORED NOT NULL,
    sender_id       TEXT    GENERATED ALWAYS AS (json_extract(message, '$.senderId'))       STORED NOT NULL,
    recipient_id    TEXT    GENERATED ALWAYS AS (json_extract(message, '$.recipientId'))    STORED,
    signature       TEXT    GENERATED ALWAYS AS (json_extract(message, '$.signature'))      STORED NOT NULL,
    timestamp       INTEGER GENERATED ALWAYS AS (json_extract(message, '$.timestamp'))      STORED NOT NULL,
    status          TEXT    NOT NULL,
    message         JSON    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS message_message_id_uidx               ON message (message_id);
CREATE        INDEX IF NOT EXISTS message_conversation_id_idx           ON message (conversation_id);
CREATE        INDEX IF NOT EXISTS message_sender_id_idx                 ON message (sender_id);
CREATE        INDEX IF NOT EXISTS message_conversation_id_timestamp_idx ON message (conversation_id, timestamp);
"""
