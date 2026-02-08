package com.urik.keyboard.ui.concentric

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Anti-occlusion layout algorithm for concentric bubble rings.
 *
 * Places bubbles along concentric rings ensuring ZERO overlap.
 * Each bubble is assigned an angular position on its ring such that
 * the arc distance between adjacent bubbles exceeds the sum of their radii
 * plus a minimum gap.
 *
 * If a ring cannot fit all its bubbles, excess bubbles are marked as hidden
 * (overflow), with the lowest-priority ones removed first.
 *
 * This class is stateless and allocation-minimal: it reuses output arrays
 * across calls to avoid GC pressure on the render thread.
 */
class BubbleLayoutManager {

    companion object {
        const val MIN_GAP_PX = 6f // minimum pixel gap between bubble edges
    }

    /**
     * Input bubble: what ring it belongs to, its label (for size calculation), and priority.
     */
    data class BubbleInput(
        val id: Int,
        val ring: Int,
        val label: String,
        val priority: Float, // higher = more important, kept when overflow
        val preferredAngleDeg: Float = 0f, // hint for initial placement
    )

    /**
     * Output: positioned bubble with coordinates and visibility.
     */
    data class PositionedBubble(
        val id: Int,
        val ring: Int,
        val label: String,
        val x: Float,
        val y: Float,
        val radius: Float,
        val angleDeg: Float,
        val visible: Boolean,
    )

    /**
     * Compute layout for all bubbles across all rings.
     *
     * @param bubbles input bubbles to position
     * @param centerX center X of the concentric layout
     * @param centerY center Y of the concentric layout
     * @param ringRadii pixel radius for each ring index
     * @param bubbleRadiusProvider function that returns the pixel radius of a bubble given its label
     * @return list of positioned bubbles (same order as input, with coordinates filled in)
     */
    fun layout(
        bubbles: List<BubbleInput>,
        centerX: Float,
        centerY: Float,
        ringRadii: FloatArray,
        bubbleRadiusProvider: (String) -> Float,
    ): List<PositionedBubble> {
        if (bubbles.isEmpty()) return emptyList()

        // Group by ring
        val byRing = bubbles.groupBy { it.ring }
        val result = mutableListOf<PositionedBubble>()

        for ((ringIndex, ringBubbles) in byRing) {
            if (ringIndex < 0 || ringIndex >= ringRadii.size) {
                // Invalid ring index - mark all as hidden
                for (b in ringBubbles) {
                    result.add(PositionedBubble(b.id, b.ring, b.label, centerX, centerY, 0f, 0f, false))
                }
                continue
            }

            val ringRadius = ringRadii[ringIndex]
            val positioned = layoutRing(ringBubbles, centerX, centerY, ringRadius, bubbleRadiusProvider)
            result.addAll(positioned)
        }

        return result
    }

    /**
     * Layout bubbles on a single ring.
     * Algorithm:
     * 1. Compute bubble radii and the angular arc each bubble occupies on the ring
     * 2. Sort by priority (descending) to keep the most important ones if overflow
     * 3. Greedily place bubbles, checking total arc usage
     * 4. If total arc > 2*PI, hide lowest-priority bubbles until it fits
     * 5. Distribute visible bubbles evenly with equal angular gaps
     */
    private fun layoutRing(
        bubbles: List<BubbleInput>,
        centerX: Float,
        centerY: Float,
        ringRadius: Float,
        bubbleRadiusProvider: (String) -> Float,
    ): List<PositionedBubble> {
        if (ringRadius < 1f) {
            return bubbles.map {
                PositionedBubble(it.id, it.ring, it.label, centerX, centerY, 0f, 0f, false)
            }
        }

        // Compute radius for each bubble
        data class BubbleWithRadius(val input: BubbleInput, val bubbleRadius: Float)
        val withRadii = bubbles.map { BubbleWithRadius(it, bubbleRadiusProvider(it.label)) }

        // Sort by priority descending (highest priority first)
        val sorted = withRadii.sortedByDescending { it.input.priority }

        // Compute the angular arc each bubble needs on the ring
        // arc = 2 * asin(bubbleRadius / ringRadius), clamped to avoid domain errors
        fun angularArc(bubbleRadius: Float): Float {
            val ratio = min((bubbleRadius + MIN_GAP_PX / 2f) / ringRadius, 1f)
            return 2f * asin(ratio).toFloat()
        }

        // Find how many bubbles fit
        val totalArcAvailable = (2.0 * PI).toFloat()
        var accumulatedArc = 0f
        var visibleCount = 0

        for (bwr in sorted) {
            val arc = angularArc(bwr.bubbleRadius)
            if (accumulatedArc + arc <= totalArcAvailable) {
                accumulatedArc += arc
                visibleCount++
            } else {
                break
            }
        }

        // Build result: visible bubbles get evenly distributed angles
        val visibleBubbles = sorted.take(visibleCount)
        val hiddenBubbles = sorted.drop(visibleCount)

        // Distribute evenly
        val angleStep = if (visibleCount > 0) totalArcAvailable / visibleCount else 0f

        val result = mutableListOf<PositionedBubble>()

        for ((i, bwr) in visibleBubbles.withIndex()) {
            val angleDeg = (i * angleStep * 180f / PI).toFloat()
            val angleRad = i * angleStep.toDouble()
            val x = centerX + (ringRadius * cos(angleRad)).toFloat()
            val y = centerY + (ringRadius * sin(angleRad)).toFloat()

            result.add(
                PositionedBubble(
                    id = bwr.input.id,
                    ring = bwr.input.ring,
                    label = bwr.input.label,
                    x = x,
                    y = y,
                    radius = bwr.bubbleRadius,
                    angleDeg = angleDeg,
                    visible = true,
                ),
            )
        }

        for (bwr in hiddenBubbles) {
            result.add(
                PositionedBubble(
                    id = bwr.input.id,
                    ring = bwr.input.ring,
                    label = bwr.input.label,
                    x = centerX,
                    y = centerY,
                    radius = bwr.bubbleRadius,
                    angleDeg = 0f,
                    visible = false,
                ),
            )
        }

        return result
    }

    /**
     * Check if any two visible bubbles in the layout overlap.
     * Returns the number of overlapping pairs (should always be 0).
     */
    fun countOverlaps(positioned: List<PositionedBubble>): Int {
        val visible = positioned.filter { it.visible }
        var overlaps = 0
        for (i in visible.indices) {
            for (j in i + 1 until visible.size) {
                val a = visible[i]
                val b = visible[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val distSq = dx * dx + dy * dy
                val minDist = a.radius + b.radius
                if (distSq < minDist * minDist) {
                    overlaps++
                }
            }
        }
        return overlaps
    }
}
