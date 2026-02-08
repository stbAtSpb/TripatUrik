package com.urik.keyboard.ui.concentric

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 2a: Async compute engine with double-buffering.
 *
 * Runs semantic computations on a dedicated thread and publishes results
 * via an atomic double-buffer. The render thread reads the "front" buffer
 * without ever blocking on compute.
 *
 * For Step 2a, computations are MOCK (simulated delays + random word selection).
 * Step 2b will replace mock with real bigram/trigram frequency model.
 * Step 2c will add FastText k-NN lookup.
 */
class ComputeEngine {

    companion object {
        private const val TAG = "ConcentricCompute"
    }

    /**
     * Result of a compute cycle: lists of suggested items per ring.
     */
    data class ComputeResult(
        val ring0Letters: List<ScoredItem> = emptyList(),
        val ring1Words: List<ScoredItem> = emptyList(),
        val ring2Context: List<ScoredItem> = emptyList(),
        val computeTimeMs: Float = 0f,
        val generation: Long = 0,
    )

    data class ScoredItem(
        val label: String,
        val score: Float, // 0..1, used as priority
    )

    // Double-buffer: compute writes to "back", render reads from "front"
    private val frontBuffer = AtomicReference(ComputeResult())

    // Current input context
    @Volatile
    private var currentInput = ""

    @Volatile
    private var inputGeneration = 0L

    private var computeThread: ComputeThread? = null
    @Volatile
    private var lastComputedGeneration = -1L

    // --- Mock data (rings 1-2, will be replaced by FastText in Step 2c) ---
    private val mockWords = mapOf(
        "" to listOf("le", "la", "de", "un", "et", "je", "il", "ce", "ne", "pas"),
        "b" to listOf("bon", "bien", "beau", "blanc", "bleu", "bras", "but", "bas"),
        "bo" to listOf("bon", "bonne", "bonjour", "bonheur", "bord", "bois", "bout"),
        "bon" to listOf("bonjour", "bonne", "bonheur", "bonbon", "bond", "bonus"),
        "bonj" to listOf("bonjour"),
        "c" to listOf("ce", "car", "chez", "comme", "chose", "coeur", "ciel"),
        "j" to listOf("je", "jour", "joie", "jeu", "jamais", "juste", "jardin"),
        "je" to listOf("jeu", "jeune", "jeter", "jean"),
        "m" to listOf("mais", "mon", "merci", "monde", "main", "matin", "mot"),
        "me" to listOf("merci", "mer", "mettre", "menu", "message"),
        "mer" to listOf("merci", "merveille", "mercredi"),
        "s" to listOf("sur", "son", "si", "sans", "sous", "soir", "salut"),
        "sa" to listOf("salut", "sac", "sage", "sable", "savoir", "sang"),
        "sal" to listOf("salut", "sale", "salon", "salaire"),
    )

    private val mockContextWords = listOf(
        "content", "heureux", "triste", "voyage", "maison",
        "travail", "famille", "amour", "nature", "musique",
        "demain", "avenir", "souvenir", "reve", "espoir",
    )

    /**
     * Read the latest compute result. Non-blocking, called from render thread.
     */
    fun getResult(): ComputeResult = frontBuffer.get()

    /**
     * Update the input context. Called from UI thread.
     * Triggers a new compute cycle on the compute thread.
     */
    fun updateInput(input: String) {
        currentInput = input.lowercase()
        inputGeneration++
        Log.d(TAG, "INPUT_CHANGED: \"$currentInput\" (gen=$inputGeneration)")
    }

    /**
     * Start the compute thread.
     */
    fun start() {
        if (computeThread?.isAlive == true) return
        computeThread = ComputeThread().also { it.start() }
    }

    /**
     * Stop the compute thread.
     */
    fun stop() {
        computeThread?.running = false
        computeThread?.interrupt()
        computeThread?.join(500)
        computeThread = null
    }

    /**
     * Dedicated compute thread. Polls for input changes and runs computation.
     */
    private inner class ComputeThread : Thread("ConcentricCompute") {
        @Volatile
        var running = true

        override fun run() {
            while (running) {
                val gen = inputGeneration
                if (gen != lastComputedGeneration) {
                    val startNs = System.nanoTime()

                    val input = currentInput
                    val result = computeMock(input, gen)

                    val computeTimeMs = (System.nanoTime() - startNs) / 1_000_000f

                    // Publish to front buffer (atomic swap)
                    frontBuffer.set(result.copy(computeTimeMs = computeTimeMs))
                    lastComputedGeneration = gen
                }

                // Poll interval: check for new input every 16ms
                try {
                    sleep(16)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * Compute suggestions for all rings.
     * Ring 0: real French bigram model (Step 2b).
     * Ring 1: mock word completions (Step 2c will replace with FastText).
     * Ring 2: mock contextual words (Step 2c will replace with FastText).
     */
    private fun computeMock(input: String, generation: Long): ComputeResult {
        // Ring 0: REAL bigram frequency model
        val ring0 = computeBigramLetters(input)

        // Ring 1: word completions (still mock - FastText in Step 2c)
        val ring1 = computeMockWords(input)

        // Ring 2: contextual words (still mock - FastText in Step 2c)
        val ring2 = computeMockContext(input)

        Log.d(TAG, "COMPUTE gen=$generation input=\"$input\"")
        Log.d(TAG, "  RING0 letters: ${ring0.map { "${it.label}(${String.format("%.2f", it.score)})" }}")
        Log.d(TAG, "  RING1 words:   ${ring1.map { it.label }}")
        Log.d(TAG, "  RING2 context: ${ring2.map { it.label }}")

        return ComputeResult(
            ring0Letters = ring0,
            ring1Words = ring1,
            ring2Context = ring2,
            generation = generation,
        )
    }

    /**
     * Real French bigram model: returns the most probable next letters
     * based on the last character typed.
     */
    private fun computeBigramLetters(input: String): List<ScoredItem> {
        val lastChar = if (input.isNotEmpty()) input.last().lowercaseChar() else null
        Log.d(TAG, "  BIGRAM lookup: lastChar='$lastChar' from input=\"$input\"")
        val bigramResult = FrenchBigramModel.getNextLetters(lastChar, maxResults = 8)
        Log.d(TAG, "  BIGRAM result: ${bigramResult.map { "${it.letter}(${String.format("%.2f", it.score)})" }}")
        return bigramResult.map { ScoredItem(it.letter, it.score) }
    }

    private fun computeMockWords(input: String): List<ScoredItem> {
        // Find best matching prefix in mock data
        var prefix = input
        while (prefix.isNotEmpty() && prefix !in mockWords) {
            prefix = prefix.dropLast(1)
        }
        val words = mockWords[prefix] ?: mockWords[""] ?: emptyList()
        return words.take(10).mapIndexed { i, word ->
            ScoredItem(word, 1f - i * 0.08f)
        }
    }

    private fun computeMockContext(input: String): List<ScoredItem> {
        // Mock: rotate through context words based on input length
        val offset = input.length % mockContextWords.size
        val rotated = mockContextWords.drop(offset) + mockContextWords.take(offset)
        return rotated.take(8).mapIndexed { i, word ->
            ScoredItem(word, 0.8f - i * 0.08f)
        }
    }
}
