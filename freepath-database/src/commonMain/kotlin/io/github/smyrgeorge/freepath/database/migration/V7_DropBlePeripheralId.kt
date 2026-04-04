package io.github.smyrgeorge.freepath.database.migration

@Suppress("SqlNoDataSourceInspection")
// language=SQLite
internal const val V7_DropBlePeripheralId = """
ALTER TABLE contact_routing DROP COLUMN ble_peripheral_id;
"""
