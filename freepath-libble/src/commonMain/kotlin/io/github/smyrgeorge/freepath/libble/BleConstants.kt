package io.github.smyrgeorge.freepath.libble

import kotlin.uuid.Uuid

internal object BleConstants {
    val FREEPATH_SERVICE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf10")
    val PING_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf11")

    /** Ephemeral X25519 public key exchange (Read/Write). */
    val EPHEMERAL_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf12")

    /** PIN confirmation + encrypted card exchange (Read/Write). */
    val CARD_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf13")

    /** Exchange result written by initiator (Write). */
    val STATUS_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf14")

    /** Generic request written by initiator (Write): reqId (8 bytes LE) + payload. */
    val REQUEST_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf15")

    /** Generic response notified by responder (Notify): reqId (8 bytes LE) + status (1 byte) + payload/error. */
    val RESPONSE_UUID: Uuid = Uuid.parse("81e2d89b-f75f-4c72-95c4-8db84b24bf16")
}
