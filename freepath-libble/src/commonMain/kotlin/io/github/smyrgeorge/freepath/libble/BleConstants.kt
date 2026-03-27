package io.github.smyrgeorge.freepath.libble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal object BleConstants {
    val FREEPATH_SERVICE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf10")
    val PING_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf11")

    /** Ephemeral X25519 public key exchange (Read/Write). */
    val EPHEMERAL_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf12")

    /** PIN confirmation + encrypted card exchange (Read/Write). */
    val CARD_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf13")

    /** Exchange result written by initiator (Write). */
    val STATUS_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf14")
}
