package io.github.smyrgeorge.freepath.database.migration

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

val migrations: List<MigrationFile> = listOf(
    MigrationFile("1_create_table_contact_card.sql", V1_CreateTableContactCard),
    MigrationFile("2_create_table_identity.sql", V2_CreateTableIdentity),
)
