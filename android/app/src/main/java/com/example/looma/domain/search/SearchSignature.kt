package com.example.looma.domain.search

import com.example.looma.domain.markdown.forEachLineRange
import com.example.looma.domain.markdown.frontmatterEnd

internal object SearchSignature {
    private const val BIT_COUNT = 4096
    private const val BIT_MASK = BIT_COUNT - 1
    private const val FORMAT_VERSION: Byte = 1
    const val STORED_BYTE_SIZE = BIT_COUNT / 8 + 1

    fun fromBody(source: String): ByteArray {
        val signature = ByteArray(STORED_BYTE_SIZE)
        signature[signature.lastIndex] = FORMAT_VERSION
        val bodyStart = frontmatterEnd(source)
        if (bodyStart >= source.length) return signature
        forEachLineRange(source, bodyStart) { start, end, _ ->
            addRange(signature, source, start, end)
            if (end - start <= MAX_MARKDOWN_CONTEXT_CHARS && hasInlineSyntax(source, start, end)) {
                val rawLine = source.substring(start, end)
                val cleaned = plainText(rawLine)
                if (cleaned.isNotEmpty() && cleaned != rawLine) {
                    addRange(signature, cleaned, 0, cleaned.length)
                }
            }
            true
        }
        return signature
    }

    fun mayContain(signature: ByteArray?, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        return prepare(keyword)?.mayContain(signature) ?: true
    }

    fun prepare(keyword: String): Query? {
        if (keyword.isEmpty() || keyword.any { it.isSurrogate() }) return null
        val bits = IntArray(keyword.length * 4 - 2)
        var bitIndex = 0
        var previous = -1
        for (char in keyword) {
            val folded = foldChar(char)
            bits[bitIndex++] = unigramFirst(folded)
            bits[bitIndex++] = unigramSecond(folded)
            if (previous >= 0) {
                val packed = pack(previous, folded)
                bits[bitIndex++] = bigramFirst(packed)
                bits[bitIndex++] = bigramSecond(packed)
            }
            previous = folded
        }
        return Query(bits)
    }

    class Query internal constructor(private val bits: IntArray) {
        fun mayContain(signature: ByteArray?): Boolean =
            signature == null || signature.size != STORED_BYTE_SIZE ||
                signature.last() != FORMAT_VERSION || bits.all { getBit(signature, it) }
    }

    private fun addRange(signature: ByteArray, text: CharSequence, start: Int, end: Int) {
        var previous = -1
        for (index in start until end) {
            val folded = foldChar(text[index])
            setUnigram(signature, folded)
            if (previous >= 0) {
                setBigram(signature, previous, folded)
            }
            previous = folded
        }
    }

    private fun foldChar(char: Char): Int = char.uppercaseChar().lowercaseChar().code

    private fun setUnigram(signature: ByteArray, folded: Int) {
        setBit(signature, unigramFirst(folded))
        setBit(signature, unigramSecond(folded))
    }

    private fun setBigram(signature: ByteArray, first: Int, second: Int) {
        val packed = pack(first, second)
        setBit(signature, bigramFirst(packed))
        setBit(signature, bigramSecond(packed))
    }

    private fun pack(first: Int, second: Int): Int = (first shl 16) or second

    private fun unigramFirst(folded: Int): Int = mix(folded xor 0x27d4eb2d) and BIT_MASK

    private fun unigramSecond(folded: Int): Int = mix(folded * 31 + 0x165667b1) and BIT_MASK

    private fun bigramFirst(packed: Int): Int = mix(packed xor -0x61c88647) and BIT_MASK

    private fun bigramSecond(packed: Int): Int = mix(packed * -0x61c88647 + 0x517cc1b7) and BIT_MASK

    private fun setBit(signature: ByteArray, bit: Int) {
        val byteIndex = bit ushr 3
        signature[byteIndex] = (signature[byteIndex].toInt() or (1 shl (bit and 7))).toByte()
    }

    private fun getBit(signature: ByteArray, bit: Int): Boolean {
        val byteIndex = bit ushr 3
        return (signature[byteIndex].toInt() and (1 shl (bit and 7))) != 0
    }

    private fun mix(value: Int): Int {
        var hash = value
        hash = hash xor (hash ushr 16)
        hash *= -0x7a143595
        hash = hash xor (hash ushr 13)
        hash *= -0x3d4d51cb
        hash = hash xor (hash ushr 16)
        return hash
    }
}
