package io.github.smyrgeorge.freepath.core.state.service

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Transaction

interface Service {
    val db: Driver

    companion object {
        suspend inline fun <S : Service, R> S.db(
            crossinline block: suspend context(QueryExecutor) S.() -> R
        ): R = with(db) { block() }

        suspend inline fun <S : Service, R> S.tx(
            crossinline block: suspend context(Transaction) S.() -> R
        ): R = db.transaction { block() }
    }
}
