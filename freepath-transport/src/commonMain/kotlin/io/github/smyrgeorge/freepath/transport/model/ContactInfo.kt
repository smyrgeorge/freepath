package io.github.smyrgeorge.freepath.transport.model

data class ContactInfo(
    val sigKeyPublic: ByteArray,
    val encKeyPublic: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContactInfo) return false
        return sigKeyPublic.contentEquals(other.sigKeyPublic) && encKeyPublic.contentEquals(other.encKeyPublic)
    }

    override fun hashCode(): Int = 31 * sigKeyPublic.contentHashCode() + encKeyPublic.contentHashCode()
}
