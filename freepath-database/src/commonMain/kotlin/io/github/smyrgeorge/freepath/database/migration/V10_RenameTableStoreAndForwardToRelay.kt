package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V10_RenameTableStoreAndForwardToRelay = """
ALTER TABLE store_and_forward RENAME TO relay;
DROP INDEX IF EXISTS store_and_forward_receiver_peer_id_expire_at_idx;
CREATE INDEX IF NOT EXISTS relay_receiver_peer_id_expire_at_idx ON relay (receiver_peer_id, expire_at);
"""
