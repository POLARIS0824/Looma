package com.example.looma.domain.search

import com.example.looma.domain.markdown.frontmatterEnd

internal object SearchSignature {
    const val BYTE_SIZE = 512
    private const val BIT_COUNT = BYTE_SIZE * 8
    private const val BIT_MASK = BIT_COUNT - 1

    fun fromBody(source: String): ByteArray {
        val signature = ByteArray(BYTE_SIZE)
        val bodyStart = frontmatterEnd(source)
        if (bodyStart >= source.length) return signature
        forEachLineRange(source, bodyStart) { start, end ->
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
        if (signature == null || signature.size != BYTE_SIZE) return true
        var previous = -1
        for (index in keyword.indices) {
            val folded = foldChar(keyword[index])
            if (!testUnigram(signature, folded)) return false
            if (previous >= 0 && !testBigram(signature, previous, folded)) return false
            previous = folded
        }
        return true
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
        val h1 = mix(folded xor 0x27d4eb2d)
        val h2 = mix(folded * 31 + 0x165667b1)
        setBit(signature, h1 and BIT_MASK)
        setBit(signature, h2 and BIT_MASK)
    }

    private fun testUnigram(signature: ByteArray, folded: Int): Boolean {
        val h1 = mix(folded xor 0x27d4eb2d)
        val h2 = mix(folded * 31 + 0x165667b1)
        return getBit(signature, h1 and BIT_MASK) && getBit(signature, h2 and BIT_MASK)
    }

    private fun setBigram(signature: ByteArray, first: Int, second: Int) {
        val packed = (first shl 16) or (second and 0xFFFF)
        val h1 = mix(packed xor -0x61c88647)
        val h2 = mix(packed * -0x61c88647 + 0x517cc1b7)
        setBit(signature, h1 and BIT_MASK)
        setBit(signature, h2 and BIT_MASK)
    }

    private fun testBigram(signature: ByteArray, first: Int, second: Int): Boolean {
        val packed = (first shl 16) or (second and 0xFFFF)
        val h1 = mix(packed xor -0x61c88647)
        val h2 = mix(packed * -0x61c88647 + 0x517cc1b7)
        return getBit(signature, h1 and BIT_MASK) && getBit(signature, h2 and BIT_MASK)
    }

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
