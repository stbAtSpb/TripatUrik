package com.urik.keyboard.ml

/**
 * Optimized vector operations, allocation-free where possible.
 * All operations work on contiguous FloatArray segments using offsets.
 */
object VectorMath {

    /**
     * Dot product of two vectors stored in arrays at given offsets.
     * For L2-normalized vectors, this equals cosine similarity.
     */
    fun dotProduct(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, dim: Int): Float {
        var sum = 0f
        for (i in 0 until dim) {
            sum += a[aOffset + i] * b[bOffset + i]
        }
        return sum
    }

    /**
     * Dot product of two full vectors (convenience overload).
     */
    fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val dim = minOf(a.size, b.size)
        for (i in 0 until dim) {
            sum += a[i] * b[i]
        }
        return sum
    }

    /**
     * L2-normalize a vector segment in-place.
     */
    fun l2Normalize(v: FloatArray, offset: Int, dim: Int) {
        var sumSq = 0f
        for (i in 0 until dim) {
            val x = v[offset + i]
            sumSq += x * x
        }
        if (sumSq <= 0f) return
        val invNorm = 1f / kotlin.math.sqrt(sumSq)
        for (i in 0 until dim) {
            v[offset + i] *= invNorm
        }
    }

    /**
     * L2-normalize a full vector in-place (convenience overload).
     */
    fun l2Normalize(v: FloatArray) {
        l2Normalize(v, 0, v.size)
    }

    /**
     * Matrix-vector multiply: result = mat * vec.
     * mat is stored row-major as [rows * cols] floats.
     */
    fun matVecMultiply(mat: FloatArray, vec: FloatArray, result: FloatArray, rows: Int, cols: Int) {
        for (r in 0 until rows) {
            var sum = 0f
            val rowOffset = r * cols
            for (c in 0 until cols) {
                sum += mat[rowOffset + c] * vec[c]
            }
            result[r] = sum
        }
    }

    /**
     * Subtract vector b from vector a, store result in out.
     */
    fun subtract(a: FloatArray, b: FloatArray, out: FloatArray, dim: Int) {
        for (i in 0 until dim) {
            out[i] = a[i] - b[i]
        }
    }

    /**
     * Add vector b to vector a in-place.
     */
    fun addInPlace(a: FloatArray, b: FloatArray, dim: Int) {
        for (i in 0 until dim) {
            a[i] += b[i]
        }
    }

    /**
     * Scale vector in-place.
     */
    fun scaleInPlace(v: FloatArray, factor: Float, dim: Int) {
        for (i in 0 until dim) {
            v[i] *= factor
        }
    }

    /**
     * Compute mean of a list of vectors.
     */
    fun mean(vectors: List<FloatArray>, dim: Int): FloatArray {
        val result = FloatArray(dim)
        for (vec in vectors) {
            for (i in 0 until dim) {
                result[i] += vec[i]
            }
        }
        val invN = 1f / vectors.size
        for (i in 0 until dim) {
            result[i] *= invN
        }
        return result
    }

    /**
     * Extract a vector segment from a contiguous array.
     */
    fun extractVector(source: FloatArray, index: Int, dim: Int): FloatArray {
        val result = FloatArray(dim)
        System.arraycopy(source, index * dim, result, 0, dim)
        return result
    }
}
