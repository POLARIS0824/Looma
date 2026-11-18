package com.example.looma.embeddingbenchmark

import android.util.JsonReader
import java.io.File
import java.text.Normalizer
import java.util.Locale

internal class WordPieceTokenizer(file: File, private val model: Model) {
    private val vocabulary = if (model == Model.BGE) {
        file.readLines().withIndex().associate { (index, token) -> token to index.toLong() }
    } else {
        readJsonVocabulary(file)
    }
    private val unknown = vocabulary.getValue("[UNK]")
    private val addSpecialTokens = model == Model.BGE

    fun ids(text: String): LongArray = buildList {
        if (addSpecialTokens) add(vocabulary.getValue("[CLS]"))
        val word = StringBuilder()

        fun flush() {
            if (word.isEmpty()) return
            val value = word.toString()
            val normalized = if (model == Model.BEKKO) {
                Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                    .replace(nonSpacingMarks, "")
            } else {
                value
            }
            addAll(wordPieces(normalized))
            word.setLength(0)
        }

        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            val type = Character.getType(code)
            when {
                code in 9..10 || code == 13 || type == Character.SPACE_SEPARATOR.toInt() -> flush()
                code == 0 || code == 0xfffd ||
                    type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                isCjk(code) || isPunctuation(code, type) -> {
                    flush()
                    addAll(wordPieces(String(Character.toChars(code))))
                }
                else -> word.appendCodePoint(code)
            }
            offset += Character.charCount(code)
        }
        flush()
        if (addSpecialTokens) add(vocabulary.getValue("[SEP]"))
    }.toLongArray()

    fun verify(references: List<TokenizerReference>) {
        references.forEachIndexed { index, reference ->
            val actual = ids(reference.text)
            check(actual.contentEquals(reference.ids)) {
                "Tokenizer parity case $index: expected ${reference.ids.contentToString()}, got ${actual.contentToString()}"
            }
        }
    }

    fun workloadInput(): Input {
        val phrase = "缓存".repeat(WORKLOAD_TOKENS / 2 - if (addSpecialTokens) 1 else 0)
        check(ids(phrase).size == WORKLOAD_TOKENS) { "Expected $WORKLOAD_TOKENS tokens in workload input" }
        return Input("workload", phrase, Role.DOCUMENT)
    }

    private fun wordPieces(word: String): List<Long> {
        val codePoints = word.codePoints().toArray()
        if (codePoints.size > 100) return listOf(unknown)
        val pieces = mutableListOf<Long>()
        var start = 0
        while (start < codePoints.size) {
            var end = codePoints.size
            var id: Long? = null
            while (end > start) {
                val piece = String(codePoints, start, end - start)
                id = vocabulary[if (start == 0) piece else "##$piece"]
                if (id != null) break
                end--
            }
            if (id == null) return listOf(unknown)
            pieces += id
            start = end
        }
        return pieces
    }

    private fun isPunctuation(code: Int, type: Int): Boolean =
        code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126 ||
            type in punctuationTypes

    private fun isCjk(code: Int): Boolean =
        code in 0x4e00..0x9fff || code in 0x3400..0x4dbf ||
            code in 0x20000..0x2a6df || code in 0x2a700..0x2b73f ||
            code in 0x2b740..0x2b81f || code in 0x2b820..0x2ceaf ||
            code in 0xf900..0xfaff || code in 0x2f800..0x2fa1f

    private companion object {
        const val WORKLOAD_TOKENS = 128
        val punctuationTypes = setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )
        val nonSpacingMarks = Regex("\\p{Mn}+")

        fun readJsonVocabulary(file: File): Map<String, Long> = JsonReader(file.reader()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "model") {
                    reader.skipValue()
                    continue
                }
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "vocab") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    return@use buildMap {
                        while (reader.hasNext()) put(reader.nextName(), reader.nextLong())
                    }
                }
                reader.endObject()
            }
            error("Tokenizer has no model vocabulary")
        }
    }
}
