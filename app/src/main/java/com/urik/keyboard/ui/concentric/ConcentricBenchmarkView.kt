package com.urik.keyboard.ui.concentric

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Phase 0 benchmark: SurfaceView with dedicated render thread.
 * Draws 3 concentric rings with static bubbles and an FPS counter overlay.
 *
 * Purpose: validate that the rendering pipeline can sustain 60fps
 * with N bubbles on the target device (Snapdragon 855 / OnePlus 7 Pro)
 * before any dynamic layout or semantic computation is added.
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
        Color.parseColor("#1a3d4f"),  // Ring 1 (inner) - teal
        Color.parseColor("#2d5a6b"),  // Ring 2 (middle)
        Color.parseColor("#7a5f4d"),  // Ring 3 (outer) - warm
    )
    private val bubbleTextColor = Color.parseColor("#87d6db")
    private val backgroundColor = Color.parseColor("#2e1f1a")

    // Static bubble data: ring index, angle offset (degrees), label
    data class BubbleData(val ring: Int, val angleDeg: Float, val label: String)

    private val bubbles: List<BubbleData> = buildStaticBubbles()

    // --- Paints (pre-allocated, zero-alloc in render loop) ---
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#3d6b7a")
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

    // --- FPS tracking ---
    private val frameTimestamps = LongArray(60)
    private var frameIndex = 0
    private var frameCount = 0
    private var currentFps = 0f
    private var minFrameTimeMs = Float.MAX_VALUE
    private var maxFrameTimeMs = 0f
    private var lastFrameTimeNs = 0L

    // --- Animation state ---
    private var rotationAngle = 0f
    private val rotationSpeedDegPerSec = 15f // slow rotation for visual validation

    // --- Render thread ---
    private var renderThread: RenderThread? = null
    private var surfaceReady = false

    // --- Pre-allocated drawing helpers (zero-alloc in loop) ---
    private val textBounds = Rect()

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderThread = RenderThread(holder).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Render thread will pick up new dimensions on next frame
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        renderThread?.running = false
        renderThread?.join(500)
        renderThread = null
    }

    /**
     * Dedicated render thread. Targets 60fps using Choreographer-style timing.
     * All drawing happens here - never on the UI thread.
     */
    private inner class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread("ConcentricRender") {
        @Volatile
        var running = true

        override fun run() {
            val targetFrameNs = 16_666_667L // ~60fps
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
                            // Surface was destroyed between lock and unlock
                        }
                    }
                }

                // Track frame timing
                val frameEndNs = System.nanoTime()
                val frameDurationNs = frameEndNs - frameStartNs
                trackFrameTiming(frameStartNs, frameDurationNs)

                // Sleep to maintain target frame rate
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

    private fun trackFrameTiming(frameTimeNs: Long, frameDurationNs: Long) {
        val frameTimeMs = frameDurationNs / 1_000_000f

        if (frameCount > 5) { // Skip first few frames (warmup)
            if (frameTimeMs < minFrameTimeMs) minFrameTimeMs = frameTimeMs
            if (frameTimeMs > maxFrameTimeMs) maxFrameTimeMs = frameTimeMs
        }

        frameTimestamps[frameIndex] = frameTimeNs
        frameIndex = (frameIndex + 1) % frameTimestamps.size
        frameCount++

        // Calculate FPS every 30 frames
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

    /**
     * Main draw method. Called on the render thread.
     * ZERO allocations allowed here for GC-free rendering.
     */
    private fun drawFrame(canvas: Canvas) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = minOf(cx, cy) * 0.95f

        // Update animation
        val now = SystemClock.elapsedRealtimeNanos()
        val dtSec = if (lastFrameTimeNs > 0) (now - lastFrameTimeNs) / 1_000_000_000f else 0.016f
        rotationAngle = (rotationAngle + rotationSpeedDegPerSec * dtSec) % 360f

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

        // Draw bubbles
        for (bubble in bubbles) {
            drawBubble(canvas, cx, cy, maxRadius, bubble)
        }

        // FPS overlay
        drawFpsOverlay(canvas, w)
    }

    private fun drawBubble(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float, bubble: BubbleData) {
        val ringRadius = maxRadius * ringRadiusFactors[bubble.ring]
        val angleRad = Math.toRadians((bubble.angleDeg + rotationAngle).toDouble())

        val bx = cx + (ringRadius * Math.cos(angleRad)).toFloat()
        val by = cy + (ringRadius * Math.sin(angleRad)).toFloat()

        // Bubble size adapts to text length
        textPaint.getTextBounds(bubble.label, 0, bubble.label.length, textBounds)
        val bubbleRadius = maxOf(textBounds.width() / 2f + 16f, 28f)

        // Fill
        bubblePaint.color = ringColors[bubble.ring]
        canvas.drawCircle(bx, by, bubbleRadius, bubblePaint)

        // Border
        canvas.drawCircle(bx, by, bubbleRadius, bubbleBorderPaint)

        // Text (vertically centered)
        val textY = by - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(bubble.label, bx, textY, textPaint)
    }

    private fun drawFpsOverlay(canvas: Canvas, width: Float) {
        val fpsText = "FPS: %.1f".format(currentFps)
        val minMaxText = "min: %.1fms  max: %.1fms".format(
            if (minFrameTimeMs == Float.MAX_VALUE) 0f else minFrameTimeMs,
            maxFrameTimeMs,
        )
        val countText = "bubbles: ${bubbles.size}  frames: $frameCount"

        // Background rect
        canvas.drawRect(0f, 0f, width, 110f, fpsBackgroundPaint)

        // Text lines
        fpsPaint.textSize = 32f
        canvas.drawText(fpsText, 16f, 32f, fpsPaint)
        fpsPaint.textSize = 24f
        canvas.drawText(minMaxText, 16f, 62f, fpsPaint)
        canvas.drawText(countText, 16f, 90f, fpsPaint)
    }

    /**
     * Build static bubble data for the benchmark.
     * 30 bubbles across 3 rings - letters on ring 0, short words on ring 1, longer words on ring 2.
     */
    private fun buildStaticBubbles(): List<BubbleData> {
        val result = mutableListOf<BubbleData>()

        // Ring 0 (inner): 8 letters, evenly spaced
        val ring0Letters = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        for (i in ring0Letters.indices) {
            result.add(BubbleData(0, i * 45f, ring0Letters[i]))
        }

        // Ring 1 (middle): 10 short words
        val ring1Words = listOf("bon", "ça", "oui", "non", "et", "le", "un", "je", "tu", "de")
        for (i in ring1Words.indices) {
            result.add(BubbleData(1, i * 36f, ring1Words[i]))
        }

        // Ring 2 (outer): 12 longer words
        val ring2Words = listOf(
            "bonjour", "merci", "salut", "bonne", "super", "accord",
            "demain", "soir", "matin", "content", "voyage", "bisou",
        )
        for (i in ring2Words.indices) {
            result.add(BubbleData(2, i * 30f, ring2Words[i]))
        }

        return result
    }

    fun resetMetrics() {
        frameCount = 0
        minFrameTimeMs = Float.MAX_VALUE
        maxFrameTimeMs = 0f
        currentFps = 0f
    }
}
