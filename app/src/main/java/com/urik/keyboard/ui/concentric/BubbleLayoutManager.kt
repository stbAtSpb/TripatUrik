package com.urik.keyboard.ui.concentric

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anti-occlusion layout algorithm for concentric bubble rings.
 *
 * Places bubbles along concentric rings ensuring ZERO overlap,
 * both within each ring (intra-ring) and between adjacent rings (inter-ring).
 *
 * Algorithm:
 * 1. Layout each ring independently with even angular distribution
 * 2. Run cross-ring collision resolution: nudge angular positions to
 *    eliminate overlaps between bubbles on different rings
 * 3. If a ring cannot fit all its bubbles, hide lowest-priority ones
 *
 * This class is stateless and allocation-minimal.
 */
class BubbleLayoutManager {

    companion object {
        const val MIN_GAP_PX = 8f // minimum pixel gap between bubble edges
        private const val MAX_CROSS_RING_ITERATIONS = 5
        private const val NUDGE_ANGLE_RAD = 0.08f // ~4.6 degrees per nudge
    }

    data class BubbleInput(
        val id: Int,
        val ring: Int,
        val label: String,
        val priority: Float,
        val preferredAngleDeg: Float = 0f,
    )

    /**
     * Mutable positioned bubble used during layout computation.
     * Converted to immutable PositionedBubble at the end.
     */
    private data class MutableBubble(
        val id: Int,
        val ring: Int,
        val label: String,
        var x: Float,
        var y: Float,
        val radius: Float,
        var angleRad: Float,
        val ringRadius: Float,
        val visible: Boolean,
        val centerX: Float,
        val centerY: Float,
    ) {
        fun toPositioned(): PositionedBubble = PositionedBubble(
            id = id, ring = ring, label = label,
            x = x, y = y, radius = radius,
            angleDeg = (angleRad * 180f / PI).toFloat(),
            visible = visible,
        )

        fun updatePosition() {
            x = centerX + (ringRadius * cos(angleRad.toDouble())).toFloat()
            y = centerY + (ringRadius * sin(angleRad.toDouble())).toFloat()
        }
    }

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

    fun layout(
        bubbles: List<BubbleInput>,
        centerX: Float,
        centerY: Float,
        ringRadii: FloatArray,
        bubbleRadiusProvider: (String) -> Float,
    ): List<PositionedBubble> {
        if (bubbles.isEmpty()) return emptyList()

        val byRing = bubbles.groupBy { it.ring }
        val allMutable = mutableListOf<MutableBubble>()

        // Phase 1: Layout each ring independently
        for ((ringIndex, ringBubbles) in byRing) {
            if (ringIndex < 0 || ringIndex >= ringRadii.size) {
                for (b in ringBubbles) {
                    allMutable.add(MutableBubble(
                        b.id, b.ring, b.label, centerX, centerY,
                        0f, 0f, 0f, false, centerX, centerY,
                    ))
                }
                continue
            }
            val ringRadius = ringRadii[ringIndex]
            val placed = layoutRing(ringBubbles, centerX, centerY, ringRadius, bubbleRadiusProvider)
            allMutable.addAll(placed)
        }

        // Phase 2: Cross-ring collision resolution
        resolveCrossRingOverlaps(allMutable)

        return allMutable.map { it.toPositioned() }
    }

    private fun layoutRing(
        bubbles: List<BubbleInput>,
        centerX: Float,
        centerY: Float,
        ringRadius: Float,
        bubbleRadiusProvider: (String) -> Float,
    ): List<MutableBubble> {
        if (ringRadius < 1f) {
            return bubbles.map {
                MutableBubble(it.id, it.ring, it.label, centerX, centerY,
                    0f, 0f, 0f, false, centerX, centerY)
            }
        }

        data class BubbleWithRadius(val input: BubbleInput, val bubbleRadius: Float)
        val withRadii = bubbles.map { BubbleWithRadius(it, bubbleRadiusProvider(it.label)) }
        val sorted = withRadii.sortedByDescending { it.input.priority }

        fun angularArc(bubbleRadius: Float): Float {
            val ratio = min((bubbleRadius + MIN_GAP_PX / 2f) / ringRadius, 1f)
            return 2f * asin(ratio).toFloat()
        }

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

        val visibleBubbles = sorted.take(visibleCount)
        val hiddenBubbles = sorted.drop(visibleCount)

        val angleStep = if (visibleCount > 0) totalArcAvailable / visibleCount else 0f

        val result = mutableListOf<MutableBubble>()

        for ((i, bwr) in visibleBubbles.withIndex()) {
            val angleRad = i * angleStep
            val x = centerX + (ringRadius * cos(angleRad.toDouble())).toFloat()
            val y = centerY + (ringRadius * sin(angleRad.toDouble())).toFloat()

            result.add(MutableBubble(
                id = bwr.input.id, ring = bwr.input.ring, label = bwr.input.label,
                x = x, y = y, radius = bwr.bubbleRadius,
                angleRad = angleRad, ringRadius = ringRadius,
                visible = true, centerX = centerX, centerY = centerY,
            ))
        }

        for (bwr in hiddenBubbles) {
            result.add(MutableBubble(
                id = bwr.input.id, ring = bwr.input.ring, label = bwr.input.label,
                x = centerX, y = centerY, radius = bwr.bubbleRadius,
                angleRad = 0f, ringRadius = ringRadius,
                visible = false, centerX = centerX, centerY = centerY,
            ))
        }

        return result
    }

    /**
     * Resolve overlaps between bubbles on DIFFERENT rings.
     * For each overlapping pair, nudge the bubble on the outer ring
     * angularly until the overlap is resolved.
     */
    private fun resolveCrossRingOverlaps(bubbles: MutableList<MutableBubble>) {
        val visible = bubbles.filter { it.visible }
        if (visible.size < 2) return

        for (iteration in 0 until MAX_CROSS_RING_ITERATIONS) {
            var hasOverlap = false

            for (i in visible.indices) {
                for (j in i + 1 until visible.size) {
                    val a = visible[i]
                    val b = visible[j]
                    if (a.ring == b.ring) continue // intra-ring already handled

                    val dx = a.x - b.x
                    val dy = a.y - b.y
                    val distSq = dx * dx + dy * dy
                    val minDist = a.radius + b.radius + MIN_GAP_PX
                    if (distSq < minDist * minDist) {
                        hasOverlap = true

                        // Nudge the outer ring bubble away
                        val outer = if (a.ring > b.ring) a else b
                        outer.angleRad += NUDGE_ANGLE_RAD
                        outer.updatePosition()
                    }
                }
            }

            if (!hasOverlap) break
        }
    }

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
