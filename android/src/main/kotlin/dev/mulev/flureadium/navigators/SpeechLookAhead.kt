@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.mulev.flureadium.navigators

import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.tokenizer.Tokenizer

private const val MAX_LOOK_AHEAD_ENTRIES = 8_192
private val lookAheadWhitespace = Regex("\\s+")

/**
 * Exact next-sentence index populated by the tokenizer that Readium itself consumes.
 *
 * A repeated sentence with different successors is deliberately treated as ambiguous. In that
 * case no prefetch is attempted, which is safer than synthesizing the wrong text on the single
 * Sherpa executor.
 */
internal class SpeechLookAhead {
    private val successors = LinkedHashMap<String, MutableSet<String?>>()

    @Synchronized
    fun record(utterances: List<String>) {
        utterances.forEachIndexed { index, current ->
            val key = normalize(current)
            if (key.isEmpty()) return@forEachIndexed
            val next = utterances.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() }
            successors.getOrPut(key) { linkedSetOf() }.add(next)
        }
        while (successors.size > MAX_LOOK_AHEAD_ENTRIES) {
            val iterator = successors.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    @Synchronized
    fun followingSentence(current: String): String? =
        successors[normalize(current)]?.singleOrNull()

    private fun normalize(text: String): String = text.trim().replace(lookAheadWhitespace, " ")
}

/** Records the exact ranges returned by Readium's normal sentence tokenizer. */
internal class LookAheadSentenceTokenizer(
    language: Language?,
    private val lookAhead: SpeechLookAhead,
) : Tokenizer<String, IntRange> {
    private val delegate = DefaultTextContentTokenizer(TextUnit.Sentence, language)

    override fun tokenize(data: String): List<IntRange> = delegate.tokenize(data).also { ranges ->
        val utterances = ranges.mapNotNull { range ->
            if (range.isEmpty() || range.first < 0 || range.last >= data.length) null
            else data.substring(range).trim().takeIf { it.isNotEmpty() }
        }
        lookAhead.record(utterances)
    }
}
