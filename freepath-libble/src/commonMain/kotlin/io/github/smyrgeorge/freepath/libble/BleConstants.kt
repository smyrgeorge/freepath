package io.github.smyrgeorge.freepath.libble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal object BleConstants {
    val FREEPATH_SERVICE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf11")

    /** Write-with-response characteristic used as a connectivity probe. No payload required. */
    val PING_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf14")
    val CARD_READ_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf12")
    val CARD_WRITE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf13")
}
