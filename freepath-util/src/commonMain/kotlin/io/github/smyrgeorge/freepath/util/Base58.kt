package io.github.smyrgeorge.freepath.util

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

object Base58 {
    fun encode(data: ByteArray): String = encode(Base.BASE58_BTC.alphabet, BigInteger(58), data)
    fun decode(data: String): ByteArray = decode(Base.BASE58_BTC.alphabet, BigInteger(58), data)

    private fun decode(alphabet: String, base: BigInteger, input: String): ByteArray {
        // Count leading '1' characters — each maps to a 0x00 byte.
        var leadingZeros = 0
        var i = 0
        while (i < input.length && input[i] == alphabet[0]) {
            leadingZeros++
            i++
        }
        // If the entire input was '1' characters, return that many zero bytes.
        val remaining = input.substring(i)
        if (remaining.isEmpty()) return ByteArray(leadingZeros)

        // Decode the significant part to a BigInteger, then to bytes.
        var bytes = decodeToBigInteger(alphabet, base, remaining).toByteArray()
        // Strip the sign byte that BigInteger.toByteArray() may prepend for values whose
        // high bit is set (e.g. 0xFF encodes as [0x00, 0xFF] in two's-complement).
        if (bytes.size > 1 && bytes[0].compareTo(0) == 0 && bytes[1] < 0) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        return ByteArray(leadingZeros) + bytes
    }

    private fun encode(alphabet: String, base: BigInteger, input: ByteArray): String {
        // Count leading zero bytes — each maps to a '1' character.
        var leadingZeros = 0
        for (b in input) {
            if (b.compareTo(0) == 0) leadingZeros++ else break
        }
        // Encode the numeric value. Stop when bi reaches zero so that zero-value
        // inputs (all 0x00 bytes) don't produce a spurious extra '1' character.
        var bi = BigInteger.fromByteArray(input, Sign.POSITIVE)
        val sb = StringBuilder()
        while (bi > BigInteger.ZERO) {
            val mod = bi.mod(base)
            sb.insert(0, alphabet[mod.intValue()])
            bi = bi.divide(base)
        }
        repeat(leadingZeros) { sb.insert(0, alphabet[0]) }
        return sb.toString()
    }

    private fun decodeToBigInteger(alphabet: String, base: BigInteger, input: String): BigInteger {
        var bi = BigInteger.ZERO
        for (i in input.length - 1 downTo 0) {
            val alphaIndex = alphabet.indexOf(input[i])
            if (alphaIndex == -1) error("Illegal character ${input[i]} at $i")
            bi = bi.add(BigInteger.fromLong(alphaIndex.toLong()).multiply(base.pow(input.length - 1 - i)))
        }
        return bi
    }

    private enum class Base(val alphabet: String) {
        BASE58_BTC("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    }
}
