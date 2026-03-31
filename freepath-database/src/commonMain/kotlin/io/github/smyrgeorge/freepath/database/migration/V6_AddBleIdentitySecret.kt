package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V6_AddBleIdentitySecret = """
ALTER TABLE contact_routing ADD COLUMN ble_identity_secret TEXT;
"""
