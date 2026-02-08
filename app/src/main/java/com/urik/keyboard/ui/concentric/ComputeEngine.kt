package com.urik.keyboard.ui.concentric

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

    // --- Mock data for Step 2a ---
    private val frenchLetters = "eaistnrulodcmpgbvhfqjxzykw".toList().map { it.toString() }

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
     * Mock computation: simulates k-NN lookup with realistic delays.
     * Step 2b/2c will replace this with real frequency model / FastText.
     */
    private fun computeMock(input: String, generation: Long): ComputeResult {
        // Simulate compute latency (2-5ms for mock)
        try {
            Thread.sleep(3)
        } catch (_: InterruptedException) {
            return ComputeResult(generation = generation)
        }

        // Ring 0: next probable letters (mock: shuffle based on input)
        val ring0 = computeMockLetters(input)

        // Ring 1: word completions (mock: lookup in mock dictionary)
        val ring1 = computeMockWords(input)

        // Ring 2: contextual words (mock: random subset)
        val ring2 = computeMockContext(input)

        return ComputeResult(
            ring0Letters = ring0,
            ring1Words = ring1,
            ring2Context = ring2,
            generation = generation,
        )
    }

    private fun computeMockLetters(input: String): List<ScoredItem> {
        // Simple frequency-based ordering: most common French letters first
        // Shift based on last character for visual feedback
        val shift = if (input.isNotEmpty()) input.last().code % frenchLetters.size else 0
        val shifted = frenchLetters.drop(shift) + frenchLetters.take(shift)
        return shifted.take(8).mapIndexed { i, letter ->
            ScoredItem(letter, 1f - i * 0.1f)
        }
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
