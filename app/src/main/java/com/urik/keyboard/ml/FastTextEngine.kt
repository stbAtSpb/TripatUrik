package com.urik.keyboard.ml

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FastText engine: lazy loading of .uvec files and cosine k-NN search.
 *
 * Vectors are stored pre-normalized, so cosine similarity = dot product.
 * Brute-force search over 60k words x 100 dims ~ 1.5ms on Snapdragon 855.
 *
 * Supports category-based stores (NOUN/VERB) for trigram S-V-O graph.
 */
@Singleton
class FastTextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val stores = mutableMapOf<String, VectorStore>()
    private val alignMatrices = mutableMapOf<String, FloatArray>()

    enum class WordCategory { NOUN, VERB }

    class VectorStore(
        val words: Array<String>,
        val vectors: FloatArray,
        val dimension: Int,
        val wordIndex: HashMap<String, Int>,
        val alignMatrix: FloatArray?,
    )

    data class ScoredWord(
        val word: String,
        val similarity: Float,
        val languageTag: String,
    )

    /**
     * Load a .uvec file from assets for the given language tag.
     * Also loads the MUSE alignment matrix if available.
     */
    suspend fun loadLanguage(tag: String) = withContext(Dispatchers.IO) {
        if (stores.containsKey(tag)) {
            Log.d(TAG, "loadLanguage($tag): already loaded")
            return@withContext
        }

        val startMs = System.currentTimeMillis()
        Log.d(TAG, "loadLanguage($tag): loading...")

        val vecPath = "vectors/fasttext_$tag.uvec"
        val alignPath = "vectors/align_$tag.bin"

        val store = loadUvecFile(vecPath)
        val alignMatrix = loadAlignMatrix(alignPath, store.dimension)

        val finalStore = VectorStore(
            words = store.words,
            vectors = store.vectors,
            dimension = store.dimension,
            wordIndex = store.wordIndex,
            alignMatrix = alignMatrix,
        )

        stores[tag] = finalStore
        val elapsed = System.currentTimeMillis() - startMs
        Log.d(TAG, "loadLanguage($tag): ${finalStore.words.size} words, ${finalStore.dimension}D, ${elapsed}ms")
    }

    private fun loadUvecFile(assetPath: String): VectorStore {
        val input = DataInputStream(BufferedInputStream(context.assets.open(assetPath), 65536))
        input.use { stream ->
            // Read header (16 bytes)
            val magic = ByteArray(4)
            stream.readFully(magic)
            check(String(magic) == "UVEC") { "Invalid magic: ${String(magic)}" }

            val headerBuf = ByteArray(12)
            stream.readFully(headerBuf)
            val hdr = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            val dimension = hdr.short.toInt() and 0xFFFF
            val wordCount = hdr.int
            // skip 6 reserved bytes (already consumed by reading)

            Log.d(TAG, "  UVEC header: dim=$dimension, words=$wordCount")

            // Read vocabulary
            val words = Array(wordCount) { "" }
            val wordIndex = HashMap<String, Int>(wordCount * 2)
            for (i in 0 until wordCount) {
                val lenBuf = ByteArray(2)
                stream.readFully(lenBuf)
                val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val wordBytes = ByteArray(len)
                stream.readFully(wordBytes)
                val word = String(wordBytes, Charsets.UTF_8)
                words[i] = word
                wordIndex[word] = i
            }

            // Read vectors (float16 -> float32)
            val totalFloats = wordCount * dimension
            val float16Bytes = ByteArray(totalFloats * 2)
            stream.readFully(float16Bytes)

            val vectors = FloatArray(totalFloats)
            val f16Buf = ByteBuffer.wrap(float16Bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalFloats) {
                vectors[i] = halfToFloat(f16Buf.short)
            }

            return VectorStore(words, vectors, dimension, wordIndex, null)
        }
    }

    private fun loadAlignMatrix(assetPath: String, dim: Int): FloatArray? {
        return try {
            val input = DataInputStream(BufferedInputStream(context.assets.open(assetPath)))
            input.use { stream ->
                val totalFloats = dim * dim
                val bytes = ByteArray(totalFloats * 2)
                stream.readFully(bytes)

                val result = FloatArray(totalFloats)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalFloats) {
                    result[i] = halfToFloat(buf.short)
                }
                Log.d(TAG, "  Loaded alignment matrix: ${dim}x${dim}")
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "  No alignment matrix at $assetPath: ${e.message}")
            null
        }
    }

    fun unloadLanguage(tag: String) {
        stores.remove(tag)
        Log.d(TAG, "unloadLanguage($tag)")
    }

    fun isLoaded(tag: String): Boolean = stores.containsKey(tag)

    /**
     * Get raw vector for a word in the given language.
     */
    fun getVector(word: String, tag: String): FloatArray? {
        val store = stores[tag] ?: return null
        val idx = store.wordIndex[word.lowercase()] ?: return null
        return VectorMath.extractVector(store.vectors, idx, store.dimension)
    }

    /**
     * Get vector projected into shared MUSE space (for bilingual search).
     */
    fun getAlignedVector(word: String, tag: String): FloatArray? {
        val store = stores[tag] ?: return null
        val idx = store.wordIndex[word.lowercase()] ?: return null
        val raw = VectorMath.extractVector(store.vectors, idx, store.dimension)
        val matrix = store.alignMatrix ?: return raw

        val aligned = FloatArray(store.dimension)
        VectorMath.matVecMultiply(matrix, raw, aligned, store.dimension, store.dimension)
        return aligned
    }

    /**
     * Find k nearest neighbors by cosine similarity (monolingual).
     */
    fun findKNearest(anchor: String, tag: String, k: Int): List<ScoredWord> {
        val store = stores[tag] ?: return emptyList()
        val anchorIdx = store.wordIndex[anchor.lowercase()] ?: return emptyList()
        val dim = store.dimension
        val anchorOffset = anchorIdx * dim

        return findTopK(store, anchorOffset, dim, k, tag, excludeIndex = anchorIdx)
    }

    /**
     * Find k nearest neighbors across two languages using MUSE-aligned vectors.
     */
    fun findKNearestBilingual(
        anchor: String,
        lang1: String,
        lang2: String,
        k: Int,
    ): List<ScoredWord> {
        val store1 = stores[lang1] ?: return emptyList()
        val store2 = stores[lang2]

        // Get anchor's aligned vector
        val anchorAligned = getAlignedVector(anchor, lang1) ?: return emptyList()
        val dim = store1.dimension

        val results = mutableListOf<ScoredWord>()

        // Search in lang1
        val anchorIdx1 = store1.wordIndex[anchor.lowercase()] ?: -1
        results.addAll(findTopKAligned(store1, anchorAligned, dim, k, lang1, excludeIndex = anchorIdx1))

        // Search in lang2 if loaded
        if (store2 != null) {
            results.addAll(findTopKAligned(store2, anchorAligned, dim, k, lang2, excludeIndex = -1))
        }

        // Merge and return top-k across both languages
        return results.sortedByDescending { it.similarity }.take(k)
    }

    /**
     * Load two languages in parallel.
     */
    suspend fun loadLanguagesPar(tag1: String, tag2: String) = coroutineScope {
        val d1 = async { loadLanguage(tag1) }
        val d2 = async { loadLanguage(tag2) }
        d1.await()
        d2.await()
    }

    // --- Category-aware API for trigram S-V-O graph ---

    private fun categoryKey(tag: String, category: WordCategory): String = "${tag}_${category.name}"

    /**
     * Load a category-specific .uvec file (e.g., fasttext_fr_nouns.uvec).
     * Alignment matrix is loaded once per language and shared across categories.
     */
    suspend fun loadLanguageCategory(tag: String, category: WordCategory) = withContext(Dispatchers.IO) {
        val key = categoryKey(tag, category)
        if (stores.containsKey(key)) {
            Log.d(TAG, "loadLanguageCategory($key): already loaded")
            return@withContext
        }

        val startMs = System.currentTimeMillis()
        Log.d(TAG, "loadLanguageCategory($key): loading...")

        val suffix = when (category) {
            WordCategory.NOUN -> "nouns"
            WordCategory.VERB -> "verbs"
        }
        val vecPath = "vectors/fasttext_${tag}_${suffix}.uvec"

        val store = loadUvecFile(vecPath)

        // Load alignment matrix once per language (shared between noun/verb)
        val alignMatrix = alignMatrices.getOrPut(tag) {
            val alignPath = "vectors/align_$tag.bin"
            loadAlignMatrix(alignPath, store.dimension) ?: FloatArray(0)
        }.let { if (it.isEmpty()) null else it }

        val finalStore = VectorStore(
            words = store.words,
            vectors = store.vectors,
            dimension = store.dimension,
            wordIndex = store.wordIndex,
            alignMatrix = alignMatrix,
        )

        stores[key] = finalStore
        val elapsed = System.currentTimeMillis() - startMs
        Log.d(TAG, "loadLanguageCategory($key): ${finalStore.words.size} words, ${finalStore.dimension}D, ${elapsed}ms")
    }

    /**
     * Load noun+verb stores for 1 or 2 languages in parallel.
     */
    suspend fun loadTrigramStores(tag1: String, tag2: String? = null) = coroutineScope {
        val jobs = mutableListOf(
            async { loadLanguageCategory(tag1, WordCategory.NOUN) },
            async { loadLanguageCategory(tag1, WordCategory.VERB) },
        )
        if (tag2 != null) {
            jobs.add(async { loadLanguageCategory(tag2, WordCategory.NOUN) })
            jobs.add(async { loadLanguageCategory(tag2, WordCategory.VERB) })
        }
        jobs.forEach { it.await() }
    }

    /**
     * Find k nearest neighbors in a specific category store.
     */
    fun findKNearest(anchor: String, tag: String, category: WordCategory, k: Int): List<ScoredWord> {
        val key = categoryKey(tag, category)
        val store = stores[key] ?: return emptyList()
        val anchorIdx = store.wordIndex[anchor.lowercase()] ?: return emptyList()
        val dim = store.dimension
        val anchorOffset = anchorIdx * dim

        return findTopK(store, anchorOffset, dim, k, tag, excludeIndex = anchorIdx)
    }

    /**
     * Find k nearest neighbors across two languages in a specific category.
     */
    fun findKNearestBilingual(
        anchor: String,
        lang1: String,
        lang2: String,
        category: WordCategory,
        k: Int,
    ): List<ScoredWord> {
        val key1 = categoryKey(lang1, category)
        val store1 = stores[key1] ?: return emptyList()
        val store2 = stores[categoryKey(lang2, category)]

        val anchorAligned = getAlignedVector(anchor, lang1, category) ?: return emptyList()
        val dim = store1.dimension

        val results = mutableListOf<ScoredWord>()

        val anchorIdx1 = store1.wordIndex[anchor.lowercase()] ?: -1
        results.addAll(findTopKAligned(store1, anchorAligned, dim, k, lang1, excludeIndex = anchorIdx1))

        if (store2 != null) {
            results.addAll(findTopKAligned(store2, anchorAligned, dim, k, lang2, excludeIndex = -1))
        }

        return results.sortedByDescending { it.similarity }.take(k)
    }

    /**
     * Get aligned vector from a category store.
     */
    fun getAlignedVector(word: String, tag: String, category: WordCategory): FloatArray? {
        val key = categoryKey(tag, category)
        val store = stores[key] ?: return null
        val idx = store.wordIndex[word.lowercase()] ?: return null
        val raw = VectorMath.extractVector(store.vectors, idx, store.dimension)
        val matrix = store.alignMatrix ?: return raw

        val aligned = FloatArray(store.dimension)
        VectorMath.matVecMultiply(matrix, raw, aligned, store.dimension, store.dimension)
        return aligned
    }

    /**
     * Get raw vector from a category store.
     */
    fun getVector(word: String, tag: String, category: WordCategory): FloatArray? {
        val key = categoryKey(tag, category)
        val store = stores[key] ?: return null
        val idx = store.wordIndex[word.lowercase()] ?: return null
        return VectorMath.extractVector(store.vectors, idx, store.dimension)
    }

    fun isCategoryLoaded(tag: String, category: WordCategory): Boolean =
        stores.containsKey(categoryKey(tag, category))

    /**
     * Handle memory pressure: unload secondary language.
     * Unloads verbs first (smaller, less critical), then nouns.
     */
    fun onTrimMemory(primaryLang: String) {
        // Unload category stores for non-primary languages (verbs first)
        val categoryKeys = stores.keys.filter { key ->
            !key.startsWith("${primaryLang}_") && key.contains("_")
        }.sortedByDescending { it.endsWith("VERB") } // verbs first

        categoryKeys.forEach { key ->
            stores.remove(key)
            Log.d(TAG, "onTrimMemory: unloaded category store $key")
        }

        // Unload legacy full-language stores for non-primary
        val toUnload = stores.keys.filter { it != primaryLang && !it.contains("_") }
        toUnload.forEach { unloadLanguage(it) }
        if (toUnload.isNotEmpty() || categoryKeys.isNotEmpty()) {
            Log.d(TAG, "onTrimMemory: unloaded ${(toUnload + categoryKeys).joinToString()}, kept $primaryLang")
        }
    }

    private fun findTopK(
        store: VectorStore,
        anchorOffset: Int,
        dim: Int,
        k: Int,
        tag: String,
        excludeIndex: Int,
    ): List<ScoredWord> {
        // Min-heap of (similarity, index) using simple array for small k
        val topSims = FloatArray(k) { -2f }
        val topIndices = IntArray(k) { -1 }

        for (i in store.words.indices) {
            if (i == excludeIndex) continue
            val sim = VectorMath.dotProduct(
                store.vectors, anchorOffset,
                store.vectors, i * dim,
                dim,
            )
            // Find min in top-k
            var minIdx = 0
            for (j in 1 until k) {
                if (topSims[j] < topSims[minIdx]) minIdx = j
            }
            if (sim > topSims[minIdx]) {
                topSims[minIdx] = sim
                topIndices[minIdx] = i
            }
        }

        return (0 until k)
            .filter { topIndices[it] >= 0 }
            .map { ScoredWord(store.words[topIndices[it]], topSims[it], tag) }
            .sortedByDescending { it.similarity }
    }

    private fun findTopKAligned(
        store: VectorStore,
        anchorAligned: FloatArray,
        dim: Int,
        k: Int,
        tag: String,
        excludeIndex: Int,
    ): List<ScoredWord> {
        val topSims = FloatArray(k) { -2f }
        val topIndices = IntArray(k) { -1 }

        // Temporary aligned vector to avoid allocating per word
        val aligned = FloatArray(dim)
        val matrix = store.alignMatrix

        for (i in store.words.indices) {
            if (i == excludeIndex) continue

            val sim: Float
            if (matrix != null) {
                // Align this word's vector
                VectorMath.matVecMultiply(matrix, store.vectors, aligned, dim, dim)
                // Actually, we need to extract the vector first
                val offset = i * dim
                for (d in 0 until dim) {
                    var s = 0f
                    for (c in 0 until dim) {
                        s += matrix[d * dim + c] * store.vectors[offset + c]
                    }
                    aligned[d] = s
                }
                sim = VectorMath.dotProduct(anchorAligned, aligned)
            } else {
                sim = VectorMath.dotProduct(
                    anchorAligned, 0,
                    store.vectors, i * dim,
                    dim,
                )
            }

            var minIdx = 0
            for (j in 1 until k) {
                if (topSims[j] < topSims[minIdx]) minIdx = j
            }
            if (sim > topSims[minIdx]) {
                topSims[minIdx] = sim
                topIndices[minIdx] = i
            }
        }

        return (0 until k)
            .filter { topIndices[it] >= 0 }
            .map { ScoredWord(store.words[topIndices[it]], topSims[it], tag) }
            .sortedByDescending { it.similarity }
    }

    companion object {
        private const val TAG = "FastText"

        /**
         * Convert IEEE 754 half-precision float (16-bit) to single-precision float.
         */
        fun halfToFloat(half: Short): Float {
            val h = half.toInt() and 0xFFFF
            val sign = (h shr 15) and 1
            val exponent = (h shr 10) and 0x1F
            val mantissa = h and 0x3FF

            val f = when {
                exponent == 0 -> {
                    // Subnormal or zero
                    if (mantissa == 0) 0f
                    else {
                        // Subnormal: (-1)^sign * 2^-14 * (mantissa / 1024)
                        val s = if (sign == 1) -1f else 1f
                        s * (mantissa.toFloat() / 1024f) * (1f / 16384f) // 2^-14
                    }
                }
                exponent == 31 -> {
                    // Inf or NaN
                    if (mantissa == 0) {
                        if (sign == 1) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
                    } else Float.NaN
                }
                else -> {
                    // Normal: repack into float32 bit pattern
                    val bits = (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
                    Float.fromBits(bits)
                }
            }
            return f
        }
    }
}
