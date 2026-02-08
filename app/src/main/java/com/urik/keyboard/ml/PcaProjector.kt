package com.urik.keyboard.ml

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projects N high-dimensional vectors to 2D via Principal Component Analysis.
 * Uses power iteration to find the 2 principal eigenvectors.
 * Designed for N=8-20 neighbors, D=100 dimensions (~1-5ms).
 */
object PcaProjector {

    data class Projection2D(
        val word: String,
        val x: Float,
        val y: Float,
        val languageTag: String,
        val similarity: Float,
    )

    private const val POWER_ITERATIONS = 25
    private const val MARGIN_MIN = 0.1f
    private const val MARGIN_MAX = 0.9f
    private const val ANCHOR_X = 0.5f
    private const val ANCHOR_Y = 0.45f

    /**
     * Project neighbor vectors to 2D positions via PCA.
     *
     * @param anchorVector The anchor word's vector (placed at center)
     * @param neighbors List of (word, aligned vector) pairs
     * @param languageTags Language tag for each neighbor (parallel to neighbors)
     * @param similarities Cosine similarity for each neighbor (parallel to neighbors)
     * @param rotationAngleRad Optional rotation for multi-pinch gesture
     * @return 2D projections normalized to [0.1, 0.9] with anchor at center
     */
    fun project(
        anchorVector: FloatArray,
        neighbors: List<Pair<String, FloatArray>>,
        languageTags: List<String>,
        similarities: List<Float>,
        rotationAngleRad: Float = 0f,
    ): List<Projection2D> {
        if (neighbors.isEmpty()) return emptyList()

        val dim = anchorVector.size
        val n = neighbors.size

        // 1. Collect all vectors (anchor + neighbors)
        val allVectors = mutableListOf(anchorVector)
        neighbors.forEach { allVectors.add(it.second) }

        // 2. Center: subtract mean
        val mean = VectorMath.mean(allVectors, dim)
        val centered = allVectors.map { vec ->
            val c = FloatArray(dim)
            VectorMath.subtract(vec, mean, c, dim)
            c
        }

        // 3. Find 2 principal components via power iteration
        val pc1 = powerIteration(centered, dim)
        // Deflate: remove pc1 component from all vectors
        val deflated = centered.map { vec ->
            val proj = VectorMath.dotProduct(vec, pc1)
            val result = vec.copyOf()
            for (i in 0 until dim) {
                result[i] -= proj * pc1[i]
            }
            result
        }
        val pc2 = powerIteration(deflated, dim)

        // 4. Project each vector onto PC1, PC2
        val coords = Array(allVectors.size) { i ->
            val x = VectorMath.dotProduct(centered[i], pc1)
            val y = VectorMath.dotProduct(centered[i], pc2)
            floatArrayOf(x, y)
        }

        // 5. Apply optional rotation
        if (rotationAngleRad != 0f) {
            val cosA = cos(rotationAngleRad)
            val sinA = sin(rotationAngleRad)
            for (coord in coords) {
                val rx = coord[0] * cosA - coord[1] * sinA
                val ry = coord[0] * sinA + coord[1] * cosA
                coord[0] = rx
                coord[1] = ry
            }
        }

        // 6. Normalize to [MARGIN_MIN, MARGIN_MAX]
        // Skip index 0 (anchor) for range computation, but include it
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (coord in coords) {
            if (coord[0] < minX) minX = coord[0]
            if (coord[0] > maxX) maxX = coord[0]
            if (coord[1] < minY) minY = coord[1]
            if (coord[1] > maxY) maxY = coord[1]
        }

        val rangeX = maxX - minX
        val rangeY = maxY - minY
        val range = MARGIN_MAX - MARGIN_MIN

        // Map anchor (index 0) to center, scale neighbors relative to it
        val anchorProjX = coords[0][0]
        val anchorProjY = coords[0][1]

        val scaleX = if (rangeX > 1e-6f) range / rangeX else 1f
        val scaleY = if (rangeY > 1e-6f) range / rangeY else 1f

        val results = mutableListOf<Projection2D>()
        for (i in 1..n) {
            val relX = (coords[i][0] - anchorProjX) * scaleX
            val relY = (coords[i][1] - anchorProjY) * scaleY
            val nx = (ANCHOR_X + relX).coerceIn(MARGIN_MIN, MARGIN_MAX)
            val ny = (ANCHOR_Y + relY).coerceIn(MARGIN_MIN, MARGIN_MAX)

            results.add(
                Projection2D(
                    word = neighbors[i - 1].first,
                    x = nx,
                    y = ny,
                    languageTag = languageTags[i - 1],
                    similarity = similarities[i - 1],
                ),
            )
        }

        return results
    }

    /**
     * Power iteration to find the dominant eigenvector of the covariance matrix.
     * For small N (8-20 vectors), this converges in ~20 iterations.
     */
    private fun powerIteration(vectors: List<FloatArray>, dim: Int): FloatArray {
        // Initialize with first non-zero vector or random direction
        val v = FloatArray(dim)
        for (d in 0 until dim) {
            v[d] = if (vectors.isNotEmpty()) vectors[0].getOrElse(d) { 0f } else 0f
        }
        // Ensure non-zero start
        if (v.all { it == 0f }) {
            v[0] = 1f
        }
        VectorMath.l2Normalize(v)

        val temp = FloatArray(dim)

        for (iter in 0 until POWER_ITERATIONS) {
            // Compute C * v = (1/N) * sum_i (x_i * (x_i . v))
            temp.fill(0f)
            for (vec in vectors) {
                val dot = VectorMath.dotProduct(vec, v)
                for (d in 0 until dim) {
                    temp[d] += vec[d] * dot
                }
            }
            // Copy temp to v and normalize
            System.arraycopy(temp, 0, v, 0, dim)
            VectorMath.l2Normalize(v)
        }

        return v
    }
}
