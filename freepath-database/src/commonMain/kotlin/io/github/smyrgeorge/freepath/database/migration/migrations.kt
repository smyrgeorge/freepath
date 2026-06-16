package io.github.smyrgeorge.freepath.database.migration

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

val migrations: List<MigrationFile> = listOf(
    MigrationFile("1_create_table_contact.sql", V1_CreateTableContact),
    MigrationFile("2_create_table_identity.sql", V2_CreateTableIdentity),
    MigrationFile("3_create_table_content.sql", V3_CreateTableContent),
    MigrationFile("4_create_table_contact_routing.sql", V4_CreateTableContactRouting),
    MigrationFile("5_create_table_content_sync.sql", V5_CreateTableContentSync),
    MigrationFile("6_add_ble_identity_secret.sql", V6_AddBleIdentitySecret),
    MigrationFile("7_drop_ble_peripheral_id.sql", V7_DropBlePeripheralId),
    MigrationFile("8_create_table_message.sql", V8_CreateTableMessage),
    MigrationFile("9_create_table_store_and_forward.sql", V9_CreateTableStoreAndForward),
    MigrationFile("10_rename_table_store_and_forward_to_relay.sql", V10_RenameTableStoreAndForwardToRelay),
    MigrationFile("11_change_relay_payload_to_text.sql", V11_ChangeRelayPayloadToText),
    MigrationFile("12_rework_relay_table.sql", V12_ReworkRelayTable),
    MigrationFile("13_rework_relay_table_for_spray_and_wait.sql", V13_ReworkRelayTableForSprayAndWait),
    MigrationFile("14_create_table_contact_encounter.sql", V14_CreateTableContactEncounter),
)
