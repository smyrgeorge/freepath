package io.github.smyrgeorge.freepath.state

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.readRawBytes
import kotlin.io.encoding.Base64
import kotlin.random.Random

object RandomAvatarGenerator {
    private val httpClient = HttpClient()

    suspend fun randomAvatar(name: String): String? = runCatching {
        val seed = "$name ${Random.nextInt(1, 10000)}"
        val bytes = httpClient.get("https://api.dicebear.com/9.x/bottts/png") {
            parameter("seed", seed)
            parameter("size", 128)
        }.readRawBytes()
        Base64.encode(bytes)
    }.getOrNull()
}
