package com.urik.keyboard.ml

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blends a French and an English word to create neologisms.
 * Three strategies: portmanteau, syllable blend, morpheme mix.
 */
@Singleton
class NeologismGenerator @Inject constructor() {

    data class Neologism(
        val word: String,
        val sourceA: String,
        val sourceB: String,
        val strategy: Strategy,
        val confidence: Float,
    )

    enum class Strategy {
        PORTMANTEAU,
        SYLLABLE_BLEND,
        MORPHEME_MIX,
    }

    /**
     * Generate neologism candidates from two words (typically one French, one English).
     */
    fun generate(wordA: String, wordB: String, maxCandidates: Int = 3): List<Neologism> {
        val a = wordA.lowercase()
        val b = wordB.lowercase()
        val candidates = mutableListOf<Neologism>()

        portmanteau(a, b)?.let { candidates.add(it) }
        portmanteau(b, a)?.let { candidates.add(it) }
        syllableBlend(a, b)?.let { candidates.add(it) }
        syllableBlend(b, a)?.let { candidates.add(it) }
        morphemeMix(a, b)?.let { candidates.add(it) }
        morphemeMix(b, a)?.let { candidates.add(it) }

        return candidates
            .distinctBy { it.word }
            .filter { it.word != a && it.word != b && it.word.length >= 3 }
            .sortedByDescending { it.confidence }
            .take(maxCandidates)
    }

    /**
     * Portmanteau: find longest overlap between end of A and start of B.
     * Example: "déjeuner" + "lunch" -> look for overlap in suffix(A) / prefix(B)
     */
    private fun portmanteau(a: String, b: String): Neologism? {
        var bestOverlap = 0
        val maxLen = minOf(a.length, b.length, 6)

        for (len in 1..maxLen) {
            val endA = a.substring(a.length - len)
            val startB = b.substring(0, len)
            if (endA.equals(startB, ignoreCase = true)) {
                bestOverlap = len
            }
        }

        if (bestOverlap >= 2) {
            val word = a + b.substring(bestOverlap)
            return Neologism(word, a, b, Strategy.PORTMANTEAU, naturalness(word))
        }

        // Fallback: single-letter overlap
        if (a.last() == b.first()) {
            val word = a + b.substring(1)
            if (word.length <= a.length + b.length - 1) {
                return Neologism(word, a, b, Strategy.PORTMANTEAU, naturalness(word) * 0.7f)
            }
        }

        return null
    }

    /**
     * Syllable blend: first syllable(s) of A + last syllable(s) of B.
     */
    private fun syllableBlend(a: String, b: String): Neologism? {
        val syllablesA = approximateSyllables(a)
        val syllablesB = approximateSyllables(b)

        if (syllablesA.size < 2 || syllablesB.size < 2) return null

        // Take first half of A's syllables, last half of B's
        val cutA = (syllablesA.size + 1) / 2
        val cutB = syllablesB.size / 2

        val prefixPart = syllablesA.take(cutA).joinToString("")
        val suffixPart = syllablesB.drop(cutB).joinToString("")

        val word = prefixPart + suffixPart
        if (word.length < 3 || word == a || word == b) return null

        return Neologism(word, a, b, Strategy.SYLLABLE_BLEND, naturalness(word))
    }

    /**
     * Morpheme mix: prefix of A + suffix of B (simple cut-based).
     */
    private fun morphemeMix(a: String, b: String): Neologism? {
        if (a.length < 3 || b.length < 3) return null

        // Cut A at ~60%, B at ~40%
        val cutA = (a.length * 0.6f).toInt().coerceIn(2, a.length - 1)
        val cutB = (b.length * 0.4f).toInt().coerceIn(1, b.length - 1)

        val prefix = a.substring(0, cutA)
        val suffix = b.substring(cutB)

        val word = prefix + suffix
        if (word.length < 3 || word == a || word == b) return null

        return Neologism(word, a, b, Strategy.MORPHEME_MIX, naturalness(word))
    }

    /**
     * Approximate syllable splitting using vowel/consonant boundaries.
     */
    private fun approximateSyllables(word: String): List<String> {
        if (word.length <= 2) return listOf(word)

        val vowels = "aeiouyàâäéèêëïîôùûüœæ"
        val syllables = mutableListOf<String>()
        var current = StringBuilder()

        for (i in word.indices) {
            current.append(word[i])
            val isVowel = word[i].lowercaseChar() in vowels
            val nextIsConsonant = i + 1 < word.length && word[i + 1].lowercaseChar() !in vowels

            if (isVowel && nextIsConsonant && current.length >= 2 && i < word.length - 2) {
                syllables.add(current.toString())
                current = StringBuilder()
            }
        }

        if (current.isNotEmpty()) {
            if (syllables.isNotEmpty() && current.length == 1) {
                syllables[syllables.lastIndex] = syllables.last() + current
            } else {
                syllables.add(current.toString())
            }
        }

        return syllables
    }

    /**
     * Score naturalness of a generated word [0, 1].
     * Penalizes consonant clusters > 3, rewards vowel-consonant alternation.
     */
    private fun naturalness(word: String): Float {
        if (word.length < 3) return 0.2f

        val vowels = "aeiouyàâäéèêëïîôùûüœæ"
        var score = 0.5f
        var consonantRun = 0
        var maxConsonantRun = 0
        var alternations = 0

        for (i in word.indices) {
            val isVowel = word[i].lowercaseChar() in vowels
            if (!isVowel) {
                consonantRun++
                maxConsonantRun = maxOf(maxConsonantRun, consonantRun)
            } else {
                consonantRun = 0
            }

            if (i > 0) {
                val prevVowel = word[i - 1].lowercaseChar() in vowels
                if (isVowel != prevVowel) alternations++
            }
        }

        // Penalize long consonant clusters
        if (maxConsonantRun > 3) score -= 0.3f
        if (maxConsonantRun > 4) score -= 0.2f

        // Reward alternation
        val altRatio = alternations.toFloat() / (word.length - 1)
        score += altRatio * 0.3f

        // Prefer medium-length words
        if (word.length in 5..10) score += 0.1f

        return score.coerceIn(0.05f, 1f)
    }
}
