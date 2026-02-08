package com.urik.keyboard.ui.concentric

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Phase 0+1 benchmark: SurfaceView with dedicated render thread.
 *
 * Phase 0: Static bubbles on 3 rings at 60fps (validated).
 * Phase 1: Dynamic bubble add/remove with BubbleLayoutManager anti-occlusion,
 *          animated repositioning, and stress test mode.
 *
 * FPS counter and overlap counter are displayed as overlay for real-time validation.
 */
class ConcentricBenchmarkView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : SurfaceView(context, attrs, defStyleAttr),
        SurfaceHolder.Callback {

    // --- Configuration ---
    private val ringRadiusFactors = floatArrayOf(0.18f, 0.32f, 0.46f)
    private val ringColors = intArrayOf(
        Color.parseColor("#1a3d4f"),
        Color.parseColor("#2d5a6b"),
        Color.parseColor("#7a5f4d"),
    )
    private val bubbleTextColor = Color.parseColor("#87d6db")
    private val backgroundColor = Color.parseColor("#2e1f1a")

    // --- Layout Manager ---
    private val layoutManager = BubbleLayoutManager()
    private var nextBubbleId = 0

    // Thread-safe input list (modified from UI thread, read from render thread)
    private val inputBubbles = CopyOnWriteArrayList<BubbleLayoutManager.BubbleInput>()

    // Animated bubble state (only accessed from render thread)
    private val animatedBubbles = mutableListOf<AnimatedBubble>()

    // Last layout result overlap count
    @Volatile
    var lastOverlapCount = 0
        private set

    @Volatile
    var hiddenBubbleCount = 0
        private set

    // --- Animated bubble wrapper ---
    data class AnimatedBubble(
        val id: Int,
        val ring: Int,
        val label: String,
        var currentX: Float,
        var currentY: Float,
        var currentRadius: Float,
        var targetX: Float,
        var targetY: Float,
        var targetRadius: Float,
        var visible: Boolean,
        var angleDeg: Float,
        var alpha: Float = 1f, // for fade-in/fade-out
        var targetAlpha: Float = 1f,
    )

    // Animation spring factor (0..1, higher = faster convergence)
    private val springFactor = 0.12f
    private val alphaSpringFactor = 0.15f
    private val positionThreshold = 0.5f // pixels - stop animating when close enough

    // --- Paints (pre-allocated) ---
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#87d6db")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bubbleTextColor
        textAlign = Paint.Align.CENTER
        textSize = 36f
    }
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ffc4a3")
        textSize = 32f
        isFakeBoldText = true
    }
    private val fpsBackgroundPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#b4f0f5")
    }
    private val overlapWarningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 28f
        isFakeBoldText = true
    }

    // --- FPS tracking ---
    private val frameTimestamps = LongArray(60)
    private var frameIndex = 0
    private var frameCount = 0
    private var currentFps = 0f
    private var minFrameTimeMs = Float.MAX_VALUE
    private var maxFrameTimeMs = 0f
    private var lastFrameTimeNs = 0L

    // --- Layout timing ---
    private var lastLayoutTimeMs = 0f

    // --- Render thread ---
    private var renderThread: RenderThread? = null
    private var surfaceReady = false

    // --- Layout dirty flag ---
    @Volatile
    private var layoutDirty = true

    // --- Pre-allocated drawing helpers ---
    private val textBounds = Rect()

    init {
        holder.addCallback(this)
        buildInitialBubbles()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderThread = RenderThread(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        layoutDirty = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        renderThread?.running = false
        renderThread?.join(500)
        renderThread = null
    }

    // === Public API (called from UI thread) ===

    /**
     * Add a bubble dynamically. Thread-safe.
     */
    fun addBubble(ring: Int, label: String, priority: Float = 1f): Int {
        val id = nextBubbleId++
        inputBubbles.add(
            BubbleLayoutManager.BubbleInput(
                id = id,
                ring = ring,
                label = label,
                priority = priority,
            ),
        )
        layoutDirty = true
        return id
    }

    /**
     * Remove a bubble by ID. Thread-safe.
     */
    fun removeBubble(id: Int) {
        inputBubbles.removeAll { it.id == id }
        layoutDirty = true
    }

    /**
     * Remove all bubbles. Thread-safe.
     */
    fun clearBubbles() {
        inputBubbles.clear()
        layoutDirty = true
    }

    /**
     * Reset to initial 30-bubble configuration.
     */
    fun resetToInitial() {
        inputBubbles.clear()
        nextBubbleId = 0
        buildInitialBubbles()
        layoutDirty = true
    }

    fun getBubbleCount(): Int = inputBubbles.size

    fun resetMetrics() {
        frameCount = 0
        minFrameTimeMs = Float.MAX_VALUE
        maxFrameTimeMs = 0f
        currentFps = 0f
    }

    // === Render Thread ===

    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("ConcentricRender") {
        @Volatile
        var running = true

        override fun run() {
            val targetFrameNs = 16_666_667L
            lastFrameTimeNs = System.nanoTime()

            while (running) {
                val frameStartNs = System.nanoTime()

                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        drawFrame(canvas)
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: IllegalArgumentException) {
                            // Surface destroyed
                        }
                    }
                }

                val frameEndNs = System.nanoTime()
                val frameDurationNs = frameEndNs - frameStartNs
                trackFrameTiming(frameStartNs, frameDurationNs)

                val sleepNs = targetFrameNs - frameDurationNs
                if (sleepNs > 0) {
                    try {
                        sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    // === Layout & Animation ===

    /**
     * Recompute layout if dirty. Called on render thread.
     */
    private fun recomputeLayout(cx: Float, cy: Float, maxRadius: Float) {
        if (!layoutDirty) return
        layoutDirty = false

        val layoutStartNs = System.nanoTime()

        val ringRadii = FloatArray(ringRadiusFactors.size) { maxRadius * ringRadiusFactors[it] }
        val snapshot = inputBubbles.toList()

        val positioned = layoutManager.layout(
            bubbles = snapshot,
            centerX = cx,
            centerY = cy,
            ringRadii = ringRadii,
            bubbleRadiusProvider = { label -> computeBubbleRadius(label) },
        )

        lastOverlapCount = layoutManager.countOverlaps(positioned)
        hiddenBubbleCount = positioned.count { !it.visible }

        // Merge with animated bubbles: update targets for existing, add new, remove dead
        val positionedById = positioned.associateBy { it.id }
        val existingIds = animatedBubbles.map { it.id }.toSet()

        // Update existing
        val toRemove = mutableListOf<AnimatedBubble>()
        for (ab in animatedBubbles) {
            val target = positionedById[ab.id]
            if (target == null) {
                // Bubble was removed - fade out
                ab.targetAlpha = 0f
                if (ab.alpha < 0.01f) {
                    toRemove.add(ab)
                }
            } else {
                ab.targetX = target.x
                ab.targetY = target.y
                ab.targetRadius = target.radius
                ab.visible = target.visible
                ab.angleDeg = target.angleDeg
                ab.targetAlpha = if (target.visible) 1f else 0f
            }
        }
        animatedBubbles.removeAll(toRemove)

        // Add new bubbles
        for (pb in positioned) {
            if (pb.id !in existingIds) {
                animatedBubbles.add(
                    AnimatedBubble(
                        id = pb.id,
                        ring = pb.ring,
                        label = pb.label,
                        currentX = cx, // start from center (animate outward)
                        currentY = cy,
                        currentRadius = pb.radius,
                        targetX = pb.x,
                        targetY = pb.y,
                        targetRadius = pb.radius,
                        visible = pb.visible,
                        angleDeg = pb.angleDeg,
                        alpha = 0f, // fade in
                        targetAlpha = if (pb.visible) 1f else 0f,
                    ),
                )
            }
        }

        lastLayoutTimeMs = (System.nanoTime() - layoutStartNs) / 1_000_000f
    }

    /**
     * Animate bubbles toward their targets. Called every frame on render thread.
     */
    private fun animateBubbles() {
        val toRemove = mutableListOf<AnimatedBubble>()

        for (ab in animatedBubbles) {
            // Spring interpolation for position
            val dx = ab.targetX - ab.currentX
            val dy = ab.targetY - ab.currentY
            if (abs(dx) > positionThreshold || abs(dy) > positionThreshold) {
                ab.currentX += dx * springFactor
                ab.currentY += dy * springFactor
            } else {
                ab.currentX = ab.targetX
                ab.currentY = ab.targetY
            }

            // Spring interpolation for alpha
            val da = ab.targetAlpha - ab.alpha
            if (abs(da) > 0.01f) {
                ab.alpha += da * alphaSpringFactor
            } else {
                ab.alpha = ab.targetAlpha
                // Remove fully faded out bubbles
                if (ab.alpha < 0.01f && ab.targetAlpha == 0f) {
                    toRemove.add(ab)
                }
            }
        }

        animatedBubbles.removeAll(toRemove)
    }

    // === Drawing ===

    private fun drawFrame(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = minOf(cx, cy) * 0.95f

        // Recompute layout if needed
        recomputeLayout(cx, cy, maxRadius)

        // Animate
        animateBubbles()

        // Background
        canvas.drawColor(backgroundColor)

        // Draw concentric ring guides
        for (i in ringRadiusFactors.indices) {
            val radius = maxRadius * ringRadiusFactors[i]
            ringPaint.color = ringColors[i]
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }

        // Center dot
        canvas.drawCircle(cx, cy, 8f, centerDotPaint)

        // Draw animated bubbles
        for (ab in animatedBubbles) {
            if (ab.alpha < 0.01f) continue
            drawAnimatedBubble(canvas, ab)
        }

        // Overlay
        drawOverlay(canvas, w)
    }

    private fun drawAnimatedBubble(canvas: Canvas, ab: AnimatedBubble) {
        val alphaInt = (ab.alpha * 255).toInt().coerceIn(0, 255)

        // Fill
        val ringIdx = ab.ring.coerceIn(0, ringColors.size - 1)
        bubblePaint.color = ringColors[ringIdx]
        bubblePaint.alpha = alphaInt
        canvas.drawCircle(ab.currentX, ab.currentY, ab.currentRadius, bubblePaint)

        // Border
        bubbleBorderPaint.alpha = alphaInt
        canvas.drawCircle(ab.currentX, ab.currentY, ab.currentRadius, bubbleBorderPaint)

        // Text
        textPaint.alpha = alphaInt
        val textY = ab.currentY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(ab.label, ab.currentX, textY, textPaint)

        // Reset alpha
        bubblePaint.alpha = 255
        bubbleBorderPaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawOverlay(canvas: Canvas, width: Float) {
        val fpsText = "FPS: %.1f".format(currentFps)
        val timingText = "min: %.1fms  max: %.1fms  layout: %.1fms".format(
            if (minFrameTimeMs == Float.MAX_VALUE) 0f else minFrameTimeMs,
            maxFrameTimeMs,
            lastLayoutTimeMs,
        )
        val countText = "bubbles: ${inputBubbles.size}  visible: ${animatedBubbles.count { it.alpha > 0.5f }}  hidden: $hiddenBubbleCount"
        val overlapText = "overlaps: $lastOverlapCount  frames: $frameCount"

        // Background rect
        canvas.drawRect(0f, 0f, width, 140f, fpsBackgroundPaint)

        // FPS line
        fpsPaint.textSize = 32f
        canvas.drawText(fpsText, 16f, 32f, fpsPaint)

        // Timing line
        fpsPaint.textSize = 22f
        canvas.drawText(timingText, 16f, 58f, fpsPaint)

        // Count line
        canvas.drawText(countText, 16f, 84f, fpsPaint)

        // Overlap line (red if overlaps detected)
        if (lastOverlapCount > 0) {
            canvas.drawText(overlapText, 16f, 110f, overlapWarningPaint)
        } else {
            fpsPaint.color = Color.parseColor("#4CAF50") // green
            canvas.drawText(overlapText, 16f, 110f, fpsPaint)
            fpsPaint.color = Color.parseColor("#ffc4a3") // reset
        }

        // Phase indicator
        fpsPaint.textSize = 18f
        canvas.drawText("PHASE 1 - Dynamic Layout", 16f, 134f, fpsPaint)
    }

    // === Helpers ===

    private fun computeBubbleRadius(label: String): Float {
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        return maxOf(textBounds.width() / 2f + 16f, 28f)
    }

    private fun buildInitialBubbles() {
        val ring0 = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        for (l in ring0) {
            addBubble(0, l, priority = 2f)
        }

        val ring1 = listOf("bon", "ça", "oui", "non", "et", "le", "un", "je", "tu", "de")
        for (w in ring1) {
            addBubble(1, w, priority = 1.5f)
        }

        val ring2 = listOf("bonjour", "merci", "salut", "bonne", "super", "accord",
            "demain", "soir", "matin", "content", "voyage", "bisou")
        for (w in ring2) {
            addBubble(2, w, priority = 1f)
        }
    }

    private fun trackFrameTiming(frameTimeNs: Long, frameDurationNs: Long) {
        val frameTimeMs = frameDurationNs / 1_000_000f

        if (frameCount > 5) {
            if (frameTimeMs < minFrameTimeMs) minFrameTimeMs = frameTimeMs
            if (frameTimeMs > maxFrameTimeMs) maxFrameTimeMs = frameTimeMs
        }

        frameTimestamps[frameIndex] = frameTimeNs
        frameIndex = (frameIndex + 1) % frameTimestamps.size
        frameCount++

        if (frameCount % 30 == 0 && frameCount >= frameTimestamps.size) {
            val oldest = frameTimestamps[(frameIndex) % frameTimestamps.size]
            val newest = frameTimestamps[(frameIndex - 1 + frameTimestamps.size) % frameTimestamps.size]
            val durationSec = (newest - oldest) / 1_000_000_000.0
            if (durationSec > 0) {
                currentFps = ((frameTimestamps.size - 1) / durationSec).toFloat()
            }
        }

        lastFrameTimeNs = frameTimeNs
    }
}
