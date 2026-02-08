package com.urik.keyboard.ui.concentric

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Phase 0+1+2a benchmark: SurfaceView with dedicated render thread.
 *
 * Phase 0: Static bubbles on 3 rings at 60fps (validated).
 * Phase 1: Dynamic bubble add/remove with BubbleLayoutManager anti-occlusion (validated).
 * Phase 2a: Async ComputeEngine with double-buffering. Bubbles update based on
 *           compute results without blocking the render thread.
 *
 * FPS, compute time, layout time, and overlap count displayed as overlay.
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
    private val ringRadiusFactors = floatArrayOf(0.15f, 0.38f, 0.62f)
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

    // Thread-safe input list (modified from UI/compute, read from render)
    private val inputBubbles = CopyOnWriteArrayList<BubbleLayoutManager.BubbleInput>()

    // Animated bubble state (only accessed from render thread)
    private val animatedBubbles = mutableListOf<AnimatedBubble>()

    // --- Compute Engine (Phase 2a) ---
    private val computeEngine = ComputeEngine()
    private var lastAppliedGeneration = -1L

    @Volatile
    var lastComputeTimeMs = 0f
        private set

    // Last layout result
    @Volatile
    var lastOverlapCount = 0
        private set

    @Volatile
    var hiddenBubbleCount = 0
        private set

    // Current input text (displayed in overlay)
    @Volatile
    var currentInputText = ""
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
        var alpha: Float = 1f,
        var targetAlpha: Float = 1f,
    )

    private val springFactor = 0.12f
    private val alphaSpringFactor = 0.15f
    private val positionThreshold = 0.5f

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
    private val inputTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#b4f0f5")
        textSize = 48f
        textAlign = Paint.Align.CENTER
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
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        computeEngine.start()
        computeEngine.updateInput("") // trigger initial compute
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
        computeEngine.stop()
    }

    // === Public API (called from UI thread) ===

    /**
     * Feed a character input. Updates the compute engine which
     * will asynchronously produce new bubble suggestions.
     */
    fun onCharacterInput(char: Char) {
        currentInputText += char
        computeEngine.updateInput(currentInputText)
    }

    /**
     * Delete last character.
     */
    fun onBackspace() {
        if (currentInputText.isNotEmpty()) {
            currentInputText = currentInputText.dropLast(1)
            computeEngine.updateInput(currentInputText)
        }
    }

    /**
     * Clear all input.
     */
    fun onClearInput() {
        currentInputText = ""
        computeEngine.updateInput("")
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

    // === Compute Result Application ===

    /**
     * Check if compute engine has new results and apply them.
     * Called on render thread - reads from atomic front buffer (non-blocking).
     */
    private fun applyComputeResults() {
        val result = computeEngine.getResult()
        if (result.generation == lastAppliedGeneration) return
        lastAppliedGeneration = result.generation
        lastComputeTimeMs = result.computeTimeMs

        // Rebuild input bubbles from compute result
        inputBubbles.clear()
        nextBubbleId = 0

        // Ring 0: letters
        for (item in result.ring0Letters) {
            val id = nextBubbleId++
            inputBubbles.add(
                BubbleLayoutManager.BubbleInput(id, 0, item.label, item.score + 1f),
            )
        }

        // Ring 1: words
        for (item in result.ring1Words) {
            val id = nextBubbleId++
            inputBubbles.add(
                BubbleLayoutManager.BubbleInput(id, 1, item.label, item.score + 0.5f),
            )
        }

        // Ring 2: context
        for (item in result.ring2Context) {
            val id = nextBubbleId++
            inputBubbles.add(
                BubbleLayoutManager.BubbleInput(id, 2, item.label, item.score),
            )
        }

        layoutDirty = true
    }

    // === Layout & Animation ===

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

        // Merge with animated bubbles
        val positionedById = positioned.associateBy { it.id }
        val existingIds = animatedBubbles.map { it.id }.toSet()

        val toRemove = mutableListOf<AnimatedBubble>()
        for (ab in animatedBubbles) {
            val target = positionedById[ab.id]
            if (target == null) {
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

        for (pb in positioned) {
            if (pb.id !in existingIds) {
                animatedBubbles.add(
                    AnimatedBubble(
                        id = pb.id, ring = pb.ring, label = pb.label,
                        currentX = cx, currentY = cy,
                        currentRadius = pb.radius,
                        targetX = pb.x, targetY = pb.y,
                        targetRadius = pb.radius,
                        visible = pb.visible, angleDeg = pb.angleDeg,
                        alpha = 0f,
                        targetAlpha = if (pb.visible) 1f else 0f,
                    ),
                )
            }
        }

        lastLayoutTimeMs = (System.nanoTime() - layoutStartNs) / 1_000_000f
    }

    private fun animateBubbles() {
        val toRemove = mutableListOf<AnimatedBubble>()
        for (ab in animatedBubbles) {
            val dx = ab.targetX - ab.currentX
            val dy = ab.targetY - ab.currentY
            if (abs(dx) > positionThreshold || abs(dy) > positionThreshold) {
                ab.currentX += dx * springFactor
                ab.currentY += dy * springFactor
            } else {
                ab.currentX = ab.targetX
                ab.currentY = ab.targetY
            }
            val da = ab.targetAlpha - ab.alpha
            if (abs(da) > 0.01f) {
                ab.alpha += da * alphaSpringFactor
            } else {
                ab.alpha = ab.targetAlpha
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

        // Apply compute results (non-blocking read from double-buffer)
        applyComputeResults()

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

        // Center: show current input text or dot
        val inputDisplay = currentInputText
        if (inputDisplay.isNotEmpty()) {
            canvas.drawText(inputDisplay, cx, cy + 16f, inputTextPaint)
        } else {
            canvas.drawCircle(cx, cy, 8f, centerDotPaint)
        }

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

        val ringIdx = ab.ring.coerceIn(0, ringColors.size - 1)
        bubblePaint.color = ringColors[ringIdx]
        bubblePaint.alpha = alphaInt
        canvas.drawCircle(ab.currentX, ab.currentY, ab.currentRadius, bubblePaint)

        bubbleBorderPaint.alpha = alphaInt
        canvas.drawCircle(ab.currentX, ab.currentY, ab.currentRadius, bubbleBorderPaint)

        textPaint.alpha = alphaInt
        val textY = ab.currentY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(ab.label, ab.currentX, textY, textPaint)

        bubblePaint.alpha = 255
        bubbleBorderPaint.alpha = 255
        textPaint.alpha = 255
    }

    private fun drawOverlay(canvas: Canvas, width: Float) {
        val fpsText = "FPS: %.1f".format(currentFps)
        val timingText = "layout: %.1fms  compute: %.1fms  min: %.1fms  max: %.1fms".format(
            lastLayoutTimeMs,
            lastComputeTimeMs,
            if (minFrameTimeMs == Float.MAX_VALUE) 0f else minFrameTimeMs,
            maxFrameTimeMs,
        )
        val countText = "bubbles: ${inputBubbles.size}  visible: ${animatedBubbles.count { it.alpha > 0.5f }}  hidden: $hiddenBubbleCount"
        val overlapText = "overlaps: $lastOverlapCount  frames: $frameCount"
        val inputDisplay = "input: \"$currentInputText\""

        // Background rect
        canvas.drawRect(0f, 0f, width, 160f, fpsBackgroundPaint)

        fpsPaint.textSize = 32f
        canvas.drawText(fpsText, 16f, 32f, fpsPaint)

        fpsPaint.textSize = 20f
        canvas.drawText(timingText, 16f, 56f, fpsPaint)
        canvas.drawText(countText, 16f, 78f, fpsPaint)

        if (lastOverlapCount > 0) {
            canvas.drawText(overlapText, 16f, 100f, overlapWarningPaint)
        } else {
            fpsPaint.color = Color.parseColor("#4CAF50")
            canvas.drawText(overlapText, 16f, 100f, fpsPaint)
            fpsPaint.color = Color.parseColor("#ffc4a3")
        }

        fpsPaint.textSize = 22f
        fpsPaint.color = Color.parseColor("#b4f0f5")
        canvas.drawText(inputDisplay, 16f, 126f, fpsPaint)
        fpsPaint.color = Color.parseColor("#ffc4a3")

        fpsPaint.textSize = 18f
        canvas.drawText("PHASE 2a - Async Compute + Double-Buffer", 16f, 152f, fpsPaint)
    }

    // === Helpers ===

    private fun computeBubbleRadius(label: String): Float {
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        return maxOf(textBounds.width() / 2f + 16f, 28f)
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
