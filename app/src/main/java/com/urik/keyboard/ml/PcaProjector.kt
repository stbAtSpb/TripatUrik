package com.urik.keyboard.ml

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Projects N high-dimensional vectors to 2D via Principal Component Analysis.
 * Uses power iteration to find the 2 principal eigenvectors.
 * Designed for N=8-20 neighbors, D=100 dimensions (~1-5ms).
 *
 * Supports trigram S-V-O projection: 3 independent PCA projections
 * mapped to distinct screen regions.
 */
object PcaProjector {

    data class Projection2D(
        val word: String,
        val x: Float,
        val y: Float,
        val languageTag: String,
        val similarity: Float,
    )

    enum class TrigramZone { SUBJECT, VERB, OBJECT }

    data class TrigramProjection(
        val zone: TrigramZone,
        val projections: List<Projection2D>,
        val anchorWord: String,
    )

    private const val POWER_ITERATIONS = 25
    private const val MARGIN_MIN = 0.1f
    private const val MARGIN_MAX = 0.9f
    private const val ANCHOR_X = 0.5f
    private const val ANCHOR_Y = 0.45f

    /**
     * Project neighbor vectors to 2D positions via PCA.
     * Uses default full-screen bounds [0.1, 0.9] with anchor at (0.5, 0.45).
     */
    fun project(
        anchorVector: FloatArray,
        neighbors: List<Pair<String, FloatArray>>,
        languageTags: List<String>,
        similarities: List<Float>,
        rotationAngleRad: Float = 0f,
    ): List<Projection2D> {
        return projectToRegion(
            anchorVector, neighbors, languageTags, similarities,
            rotationAngleRad,
            xMin = MARGIN_MIN, xMax = MARGIN_MAX,
            anchorX = ANCHOR_X, anchorY = ANCHOR_Y,
        )
    }

    /**
     * Project 3 sets of neighbors into 3 distinct screen zones for S-V-O trigram.
     *
     * - SUBJECT: x in [0.05, 0.30], anchor at (0.175, 0.45)
     * - VERB:    x in [0.35, 0.65], anchor at (0.50, 0.45)
     * - OBJECT:  x in [0.70, 0.95], anchor at (0.825, 0.45)
     */
    fun projectTrigram(
        subjectAnchor: String,
        subjectAnchorVec: FloatArray,
        subjectNeighbors: List<Pair<String, FloatArray>>,
        subjectLangTags: List<String>,
        subjectSims: List<Float>,
        verbAnchor: String,
        verbAnchorVec: FloatArray,
        verbNeighbors: List<Pair<String, FloatArray>>,
        verbLangTags: List<String>,
        verbSims: List<Float>,
        objectAnchor: String,
        objectAnchorVec: FloatArray,
        objectNeighbors: List<Pair<String, FloatArray>>,
        objectLangTags: List<String>,
        objectSims: List<Float>,
        rotationAngleRad: Float = 0f,
    ): List<TrigramProjection> {
        val subjectProjections = projectToRegion(
            subjectAnchorVec, subjectNeighbors, subjectLangTags, subjectSims,
            rotationAngleRad,
            xMin = 0.05f, xMax = 0.30f,
            anchorX = 0.175f, anchorY = 0.45f,
        )

        val verbProjections = projectToRegion(
            verbAnchorVec, verbNeighbors, verbLangTags, verbSims,
            rotationAngleRad,
            xMin = 0.35f, xMax = 0.65f,
            anchorX = 0.50f, anchorY = 0.45f,
        )

        val objectProjections = projectToRegion(
            objectAnchorVec, objectNeighbors, objectLangTags, objectSims,
            rotationAngleRad,
            xMin = 0.70f, xMax = 0.95f,
            anchorX = 0.825f, anchorY = 0.45f,
        )

        return listOf(
            TrigramProjection(TrigramZone.SUBJECT, subjectProjections, subjectAnchor),
            TrigramProjection(TrigramZone.VERB, verbProjections, verbAnchor),
            TrigramProjection(TrigramZone.OBJECT, objectProjections, objectAnchor),
        )
    }

    /**
     * Project neighbor vectors to 2D within a custom region.
     */
    private fun projectToRegion(
        anchorVector: FloatArray,
        neighbors: List<Pair<String, FloatArray>>,
        languageTags: List<String>,
        similarities: List<Float>,
        rotationAngleRad: Float,
        xMin: Float,
        xMax: Float,
        anchorX: Float,
        anchorY: Float,
    ): List<Projection2D> {
        if (neighbors.isEmpty()) return emptyList()

        val dim = anchorVector.size
        val n = neighbors.size
        val yMin = 0.1f
        val yMax = 0.9f

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

        // 6. Normalize to [xMin, xMax] x [yMin, yMax]
        var coordMinX = Float.MAX_VALUE
        var coordMaxX = Float.MIN_VALUE
        var coordMinY = Float.MAX_VALUE
        var coordMaxY = Float.MIN_VALUE

        for (coord in coords) {
            if (coord[0] < coordMinX) coordMinX = coord[0]
            if (coord[0] > coordMaxX) coordMaxX = coord[0]
            if (coord[1] < coordMinY) coordMinY = coord[1]
            if (coord[1] > coordMaxY) coordMaxY = coord[1]
        }

        val rangeX = coordMaxX - coordMinX
        val rangeY = coordMaxY - coordMinY
        val regionRangeX = xMax - xMin
        val regionRangeY = yMax - yMin

        val anchorProjX = coords[0][0]
        val anchorProjY = coords[0][1]

        val scaleX = if (rangeX > 1e-6f) regionRangeX / rangeX else 1f
        val scaleY = if (rangeY > 1e-6f) regionRangeY / rangeY else 1f

        val results = mutableListOf<Projection2D>()
        for (i in 1..n) {
            val relX = (coords[i][0] - anchorProjX) * scaleX
            val relY = (coords[i][1] - anchorProjY) * scaleY
            val nx = (anchorX + relX).coerceIn(xMin, xMax)
            val ny = (anchorY + relY).coerceIn(yMin, yMax)

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
