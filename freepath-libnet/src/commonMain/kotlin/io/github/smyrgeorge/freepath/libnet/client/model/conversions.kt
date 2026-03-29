package io.github.smyrgeorge.freepath.libnet.client.model

fun <T> T.success(): Result<T> = Result.success(this)
fun <T> failure(reason: String): Result<T> = Result.failure(IllegalStateException(reason))
