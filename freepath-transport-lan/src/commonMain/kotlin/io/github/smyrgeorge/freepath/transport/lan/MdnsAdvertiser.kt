package io.github.smyrgeorge.freepath.transport.lan

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class MdnsAdvertiser(nodeId: String, port: Int) {
    suspend fun start()
    suspend fun stop()
}
