package io.github.smyrgeorge.freepath.client.model

import io.github.smyrgeorge.freepath.libp2p.Libp2pEvent

fun Libp2pEvent.Response.toResult(): Result<ByteArray> {
    return when (this) {
        is Libp2pEvent.RequestFailed -> Result.failure(RuntimeException(error))
        is Libp2pEvent.ResponseReceived -> Result.success(payload)
    }
}

fun <T> T.success(): Result<T> = Result.success(this)
fun <T> failure(reason: String): Result<T> = Result.failure(IllegalStateException(reason))
