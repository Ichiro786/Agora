package com.newoether.agora.api.util.tokens

import com.newoether.agora.api.util.tokens.bpe.BpeTokenCount
import com.newoether.agora.api.util.tokens.bpe.BpeVocabulary
import kotlin.math.ceil

/**
 * Text counting through a real BPE vocabulary, with the offline heuristic as a fallback.
 *
 * The vocabulary is supplied rather than held, because it is loaded in the background and counting
 * must stay synchronous: before it arrives, and if it fails to load at all, the heuristic answers.
 *
 * [scale] exists for families whose own tokenizer is not published. Counting their text with a known
 * vocabulary and correcting by a measured factor is closer to the truth than counting characters, but
 * the factor has to be measured offline first; until it is, a family stays at 1.0 rather than
 * carrying an invented number.
 */
class BpeTextTokenCounter(
    private val vocabulary: () -> BpeVocabulary?,
    private val scale: Double = 1.0,
    private val fallback: TextTokenCounter = HeuristicTextTokenCounter,
) : TextTokenCounter {

    init {
        // A factor below one would claim a family is cheaper than the vocabulary says, which is the
        // unsafe direction for a budget.
        require(scale >= 1.0) { "scale must be at least 1.0, was $scale" }
    }

    override fun count(text: String): Long {
        if (text.isEmpty()) return 0L
        val loaded = vocabulary() ?: return fallback.count(text)
        val counted = BpeTokenCount.of(text, loaded)
        if (scale == 1.0) return counted
        return ceil(counted * scale).toLong()
    }
}
