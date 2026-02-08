package com.urik.keyboard.ui.concentric

import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SemanticGraphOverlay
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {

        data class GraphNode(
            val word: String,
            val xPercent: Float,
            val yPercent: Float,
            val languageTag: String = "fr",
            val similarity: Float = 0f,
        )

        var onWordSelected: ((String) -> Unit)? = null
        var onDismissed: (() -> Unit)? = null
        var onTouchInteraction: (() -> Unit)? = null
        var onNeologismRequested: ((GraphNode, GraphNode) -> Unit)? = null
        var onRotationCompleted: ((Float) -> Unit)? = null // cumulative angle in radians
        var onMultiTouchStateChanged: ((Boolean) -> Unit)? = null // true=started, false=ended

        private var anchorWord: String = ""
        private var nodes: List<GraphNode> = emptyList()
        private var fadeAlpha: Float = 0f
        private var fadeAnimator: ValueAnimator? = null
        private var isLoading: Boolean = false

        private var swipeStartY = 0f
        private var isSwiping = false

        // Multi-touch state for neologism / rotation
        private var multiTouchStartAngle = 0f
        private var multiTouchAccumulatedAngle = 0f
        private var isMultiTouching = false
        private var pointer0NodeIndex = -1
        private var pointer1NodeIndex = -1

        // Cumulative PCA rotation persisted across gestures
        private var cumulativeRotation = 0f
        // Live rotation delta during active gesture (added to cumulative for rendering)
        private var liveRotationDelta = 0f

        // Multi-pinch highlight state
        private val highlightedNodeIndices = mutableSetOf<Int>()

        // Selection flash state
        private var selectedNodeIndex = -1
        private var selectionFlashAlpha = 0f
        private var selectionFlashAnimator: ValueAnimator? = null

        // DNA/RNA sentence context words (left side spiral)
        private var contextWords: List<String> = emptyList()

        // Theme colors for bilingual nodes
        private var frenchNodeColor = 0xFF_3A7CA5.toInt()
        private var englishNodeColor = 0xFF_C75050.toInt()

        private val bgPaint = Paint().apply {
            color = 0xE0_1A1A2E.toInt()
            style = Paint.Style.FILL
        }

        private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_E8D5B7.toInt()
            style = Paint.Style.FILL
        }

        private val anchorTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_1A1A2E.toInt()
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_3A3A5E.toInt()
            style = Paint.Style.FILL
        }

        private val nodeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_E8D5B7.toInt()
            textAlign = Paint.Align.CENTER
        }

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x40_E8D5B7.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val loadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_E8D5B7.toInt()
            textAlign = Paint.Align.CENTER
        }

        private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_E8D5B7.toInt()
            textAlign = Paint.Align.CENTER
        }

        // Highlight halo paint for multi-pinch proximity
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x60_FFD700.toInt() // gold halo
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        // Selection flash paint (white pulse)
        private val selectionFlashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF_FFFFFF.toInt()
            style = Paint.Style.FILL
        }

        // DNA backbone paint
        private val dnaBackbonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x30_E8D5B7.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        // DNA node paint (smaller, dimmer)
        private val dnaNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x50_3A3A5E.toInt()
            style = Paint.Style.FILL
        }

        private val dnaTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x80_E8D5B7.toInt()
            textAlign = Paint.Align.CENTER
        }

        private val dnaPath = Path()

        private val nodeHitRects = mutableListOf<Pair<RectF, Int>>() // rect -> node index

        fun setAnchorWord(word: String) {
            anchorWord = word.lowercase().trim()
            Log.d(TAG, "setAnchorWord: '$anchorWord'")
            nodeHitRects.clear()
            invalidate()
        }

        /**
         * Set nodes from real FastText + PCA projections.
         */
        fun setNodes(anchor: String, projectedNodes: List<GraphNode>) {
            anchorWord = anchor.lowercase().trim()
            nodes = projectedNodes
            isLoading = false
            fadeAlpha = 1f // enable rendering (View.alpha controls actual opacity)
            // After re-projection, reset the visual rotation since the PCA already includes the angle
            liveRotationDelta = 0f
            cumulativeRotation = 0f
            Log.d(TAG, "setNodes: anchor='$anchorWord' | nodes=${nodes.size}")
            nodeHitRects.clear()
            invalidate()
        }

        /**
         * Show loading indicator while FastText computes.
         */
        fun setLoading(anchor: String) {
            anchorWord = anchor.lowercase().trim()
            nodes = emptyList()
            isLoading = true
            fadeAlpha = 1f // enable rendering (View.alpha controls actual opacity)
            Log.d(TAG, "setLoading: anchor='$anchorWord'")
            nodeHitRects.clear()
            invalidate()
        }

        /**
         * Update bilingual theme colors from ThemeColors.
         */
        fun setLanguageColors(frenchColor: Int, englishColor: Int) {
            frenchNodeColor = frenchColor
            englishNodeColor = englishColor
            invalidate()
        }

        /**
         * Set sentence context words for DNA/RNA spiral display on the left side.
         * Words should be in order (earliest first).
         */
        fun setContextWords(words: List<String>) {
            contextWords = words.takeLast(8) // keep last 8 words max
            invalidate()
        }

        fun fadeIn(duration: Long = CROSS_FADE_DURATION) {
            Log.d(TAG, "fadeIn: duration=${duration}ms | anchor='$anchorWord'")
            fadeAnimator?.cancel()
            visibility = VISIBLE
            fadeAnimator = ValueAnimator.ofFloat(fadeAlpha, 1f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    fadeAlpha = animator.animatedValue as Float
                    alpha = fadeAlpha
                    invalidate()
                }
                start()
            }
        }

        fun fadeOut(duration: Long = CROSS_FADE_DURATION, onEnd: (() -> Unit)? = null) {
            Log.d(TAG, "fadeOut: duration=${duration}ms")
            fadeAnimator?.cancel()
            fadeAnimator = ValueAnimator.ofFloat(fadeAlpha, 0f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    fadeAlpha = animator.animatedValue as Float
                    alpha = fadeAlpha
                    invalidate()
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        onEnd?.invoke()
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        visibility = GONE
                        onEnd?.invoke()
                    }
                })
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (fadeAlpha <= 0f) return

            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val centerX = w * 0.5f
            val centerY = h * 0.45f

            val density = resources.displayMetrics.density
            val anchorRadius = 36f * density
            val baseNodeRadius = 24f * density
            anchorTextPaint.textSize = 16f * density
            nodeTextPaint.textSize = 13f * density
            loadingPaint.textSize = 14f * density
            legendTextPaint.textSize = 10f * density

            nodeHitRects.clear()

            if (isLoading) {
                // Draw loading indicator
                canvas.drawCircle(centerX, centerY, anchorRadius, anchorPaint)
                val anchorTextY = centerY - (anchorTextPaint.descent() + anchorTextPaint.ascent()) / 2f
                canvas.drawText(anchorWord, centerX, anchorTextY, anchorTextPaint)

                loadingPaint.alpha = (fadeAlpha * 180).toInt()
                val loadingY = centerY + anchorRadius + 20f * density
                canvas.drawText("...", centerX, loadingY, loadingPaint)
                return
            }

            // --- DNA/RNA spiral: sentence context on the left side ---
            if (contextWords.isNotEmpty()) {
                drawDnaSpiral(canvas, w, h, density)
            }

            // Compute rotation-adjusted positions
            val totalRotation = cumulativeRotation + liveRotationDelta
            val cosR = cos(totalRotation)
            val sinR = sin(totalRotation)

            // Draw lines from anchor to nodes
            for (node in nodes) {
                val rawX = w * node.xPercent - centerX
                val rawY = h * node.yPercent - centerY
                val nx = centerX + rawX * cosR - rawY * sinR
                val ny = centerY + rawX * sinR + rawY * cosR
                linePaint.alpha = (fadeAlpha * 64).toInt()
                canvas.drawLine(centerX, centerY, nx, ny, linePaint)
            }

            // Draw nodes with language-specific colors and similarity-proportional size
            val hasBilingual = nodes.any { it.languageTag != nodes.firstOrNull()?.languageTag }

            for ((index, node) in nodes.withIndex()) {
                val rawX = w * node.xPercent - centerX
                val rawY = h * node.yPercent - centerY
                val nx = centerX + rawX * cosR - rawY * sinR
                val ny = centerY + rawX * sinR + rawY * cosR

                // Size proportional to similarity: [0.7, 1.3] range
                val sizeFactor = 0.7f + (node.similarity.coerceIn(0f, 1f) * 0.6f)
                val nodeRadius = baseNodeRadius * sizeFactor

                // Color by language tag
                val isHighlighted = highlightedNodeIndices.contains(index)
                val isSelected = selectedNodeIndex == index

                nodePaint.color = when {
                    isHighlighted -> 0xFF_FFD700.toInt() // gold when highlighted by pinch
                    node.languageTag == "fr" && hasBilingual -> frenchNodeColor
                    node.languageTag == "en" && hasBilingual -> englishNodeColor
                    else -> 0xFF_3A3A5E.toInt()
                }

                canvas.drawCircle(nx, ny, nodeRadius, nodePaint)

                // Draw highlight halo ring
                if (isHighlighted) {
                    highlightPaint.strokeWidth = 3f * density
                    canvas.drawCircle(nx, ny, nodeRadius + 4f * density, highlightPaint)
                }

                // Draw selection flash overlay
                if (isSelected && selectionFlashAlpha > 0f) {
                    selectionFlashPaint.alpha = (selectionFlashAlpha * 200).toInt()
                    canvas.drawCircle(nx, ny, nodeRadius + 6f * density * selectionFlashAlpha, selectionFlashPaint)
                }

                val textY = ny - (nodeTextPaint.descent() + nodeTextPaint.ascent()) / 2f
                canvas.drawText(node.word, nx, textY, nodeTextPaint)

                nodeHitRects.add(
                    Pair(
                        RectF(
                            nx - nodeRadius * 1.5f,
                            ny - nodeRadius * 1.5f,
                            nx + nodeRadius * 1.5f,
                            ny + nodeRadius * 1.5f,
                        ),
                        index,
                    ),
                )
            }

            // Draw anchor node on top
            canvas.drawCircle(centerX, centerY, anchorRadius, anchorPaint)
            val anchorTextY = centerY - (anchorTextPaint.descent() + anchorTextPaint.ascent()) / 2f
            canvas.drawText(anchorWord, centerX, anchorTextY, anchorTextPaint)

            // Draw bilingual legend at bottom
            if (hasBilingual) {
                drawBilingualLegend(canvas, w, h, density)
            }
        }

        private fun drawBilingualLegend(canvas: Canvas, w: Float, h: Float, density: Float) {
            val legendY = h - 12f * density
            val dotRadius = 5f * density
            val spacing = 60f * density
            val startX = w * 0.5f - spacing * 0.5f

            // FR legend
            legendPaint.color = frenchNodeColor
            canvas.drawCircle(startX, legendY, dotRadius, legendPaint)
            canvas.drawText("FR", startX + 14f * density, legendY + 4f * density, legendTextPaint)

            // EN legend
            legendPaint.color = englishNodeColor
            canvas.drawCircle(startX + spacing, legendY, dotRadius, legendPaint)
            canvas.drawText("EN", startX + spacing + 14f * density, legendY + 4f * density, legendTextPaint)
        }

        /**
         * Draw DNA/RNA spiral showing sentence construction memory on the left side.
         * Words are arranged in a sinusoidal helix pattern, most recent word closest
         * to the center (anchor), older words spiraling away with decreasing size/opacity.
         */
        private fun drawDnaSpiral(canvas: Canvas, w: Float, h: Float, density: Float) {
            val count = contextWords.size
            if (count == 0) return

            val dnaNodeRadius = 14f * density
            dnaTextPaint.textSize = 9f * density

            // Spiral parameters: words go from bottom-left toward center
            // The most recent word (last) is closest to the anchor
            val spiralCenterX = w * 0.15f // left side
            val spiralTopY = h * 0.10f
            val spiralBottomY = h * 0.85f
            val amplitude = w * 0.06f // horizontal wave amplitude

            dnaPath.reset()
            var firstPoint = true

            for (i in 0 until count) {
                // Progress: 0 = oldest (top-left), 1 = newest (closer to center)
                val progress = if (count > 1) i.toFloat() / (count - 1) else 0.5f

                // Vertical position: spread evenly
                val y = spiralTopY + (spiralBottomY - spiralTopY) * (1f - progress)

                // Sinusoidal X offset (DNA double-helix wave)
                val phase = progress * Math.PI.toFloat() * 2.5f
                val waveOffset = sin(phase) * amplitude
                val x = spiralCenterX + waveOffset

                // Opacity: older words are dimmer
                val wordAlpha = 0.3f + 0.5f * progress

                // Size: older words smaller
                val sizeFactor = 0.6f + 0.4f * progress
                val radius = dnaNodeRadius * sizeFactor

                // Draw backbone path
                if (firstPoint) {
                    dnaPath.moveTo(x, y)
                    firstPoint = false
                } else {
                    dnaPath.lineTo(x, y)
                }

                // Draw node
                dnaNodePaint.alpha = (wordAlpha * 128).toInt()
                canvas.drawCircle(x, y, radius, dnaNodePaint)

                // Draw word
                dnaTextPaint.alpha = (wordAlpha * 200).toInt()
                val textY = y - (dnaTextPaint.descent() + dnaTextPaint.ascent()) / 2f
                canvas.drawText(contextWords[i], x, textY, dnaTextPaint)
            }

            // Draw backbone
            canvas.drawPath(dnaPath, dnaBackbonePaint)

            // Draw a faint connector from newest context word to anchor area
            if (count > 0) {
                val newestY = spiralTopY + (spiralBottomY - spiralTopY) * 0f // progress=1 -> y factor 0
                val newestPhase = 1f * Math.PI.toFloat() * 2.5f
                val newestX = spiralCenterX + sin(newestPhase) * amplitude
                val centerX = w * 0.5f
                val centerY = h * 0.45f
                dnaBackbonePaint.alpha = 30
                canvas.drawLine(newestX, newestY, centerX, centerY, dnaBackbonePaint)
                dnaBackbonePaint.alpha = (0x30) // restore
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val actionMasked = event.actionMasked

            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartY = event.y
                    isSwiping = false
                    isMultiTouching = false
                    multiTouchAccumulatedAngle = 0f
                    pointer0NodeIndex = -1
                    pointer1NodeIndex = -1
                    onTouchInteraction?.invoke()
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isMultiTouching = true
                        multiTouchStartAngle = getMultiTouchAngle(event)
                        multiTouchAccumulatedAngle = 0f

                        // Hit-test both pointers for neologism detection
                        pointer0NodeIndex = hitTestNode(event.getX(0), event.getY(0))
                        pointer1NodeIndex = hitTestNode(event.getX(1), event.getY(1))

                        // Find nearest nodes to highlight
                        highlightedNodeIndices.clear()
                        val near0 = findNearestNode(event.getX(0), event.getY(0))
                        val near1 = findNearestNode(event.getX(1), event.getY(1))
                        if (near0 >= 0) highlightedNodeIndices.add(near0)
                        if (near1 >= 0 && near1 != near0) highlightedNodeIndices.add(near1)

                        onMultiTouchStateChanged?.invoke(true)
                        Log.d(TAG, "multi-touch: pointer0=$pointer0NodeIndex pointer1=$pointer1NodeIndex highlight=$highlightedNodeIndices")
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isMultiTouching && event.pointerCount >= 2) {
                        val currentAngle = getMultiTouchAngle(event)
                        multiTouchAccumulatedAngle = currentAngle - multiTouchStartAngle
                        liveRotationDelta = multiTouchAccumulatedAngle

                        // Update nearest node highlights as fingers move
                        highlightedNodeIndices.clear()
                        val near0 = findNearestNode(event.getX(0), event.getY(0))
                        val near1 = findNearestNode(event.getX(1), event.getY(1))
                        if (near0 >= 0) highlightedNodeIndices.add(near0)
                        if (near1 >= 0 && near1 != near0) highlightedNodeIndices.add(near1)

                        invalidate() // real-time visual rotation + highlight
                        return true
                    }
                    val dy = event.y - swipeStartY
                    val threshold = SWIPE_DOWN_THRESHOLD * resources.displayMetrics.density
                    if (dy > threshold) {
                        isSwiping = true
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (isMultiTouching) {
                        val absAngle = Math.toDegrees(kotlin.math.abs(multiTouchAccumulatedAngle).toDouble())
                        if (absAngle < NEOLOGISM_ANGLE_THRESHOLD) {
                            // Small rotation = neologism tap
                            tryNeologism()
                        } else {
                            // Commit rotation: accumulate into cumulative and trigger re-projection
                            cumulativeRotation += liveRotationDelta
                            Log.d(TAG, "rotation: committed ${Math.toDegrees(liveRotationDelta.toDouble()).toInt()}° | total=${Math.toDegrees(cumulativeRotation.toDouble()).toInt()}°")
                            onRotationCompleted?.invoke(cumulativeRotation)
                        }
                        liveRotationDelta = 0f
                        isMultiTouching = false
                        highlightedNodeIndices.clear()
                        onMultiTouchStateChanged?.invoke(false)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isSwiping) {
                        Log.d(TAG, "touch: swipe-down dismiss")
                        onDismissed?.invoke()
                        performClick()
                        return true
                    }

                    if (!isMultiTouching) {
                        val x = event.x
                        val y = event.y
                        val nodeIdx = hitTestNode(x, y)
                        if (nodeIdx >= 0 && nodeIdx < nodes.size) {
                            val word = nodes[nodeIdx].word
                            Log.d(TAG, "touch: node tapped '$word' at (${x.toInt()}, ${y.toInt()})")
                            // Flash the selected node before firing callback
                            flashSelectedNode(nodeIdx) {
                                onWordSelected?.invoke(word)
                            }
                            performClick()
                            return true
                        }
                        Log.d(TAG, "touch: tap miss at (${x.toInt()}, ${y.toInt()}) | hitRects=${nodeHitRects.size}")
                    }
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun hitTestNode(x: Float, y: Float): Int {
            for ((rect, nodeIndex) in nodeHitRects) {
                if (rect.contains(x, y)) {
                    return nodeIndex
                }
            }
            return -1
        }

        /**
         * Find the nearest node within a generous radius (for multi-pinch highlight).
         */
        private fun findNearestNode(x: Float, y: Float): Int {
            val maxDist = 80f * resources.displayMetrics.density
            var bestIdx = -1
            var bestDist = maxDist * maxDist
            for ((rect, nodeIndex) in nodeHitRects) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val dx = x - cx
                val dy = y - cy
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = nodeIndex
                }
            }
            return bestIdx
        }

        private fun getMultiTouchAngle(event: MotionEvent): Float {
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            return atan2(dy, dx)
        }

        private fun tryNeologism() {
            if (pointer0NodeIndex < 0 || pointer1NodeIndex < 0) return
            if (pointer0NodeIndex >= nodes.size || pointer1NodeIndex >= nodes.size) return

            val nodeA = nodes[pointer0NodeIndex]
            val nodeB = nodes[pointer1NodeIndex]

            if (nodeA.languageTag != nodeB.languageTag) {
                Log.d(TAG, "neologism: ${nodeA.word} (${nodeA.languageTag}) + ${nodeB.word} (${nodeB.languageTag})")
                onNeologismRequested?.invoke(nodeA, nodeB)
            } else {
                Log.d(TAG, "neologism: SKIP same language (${nodeA.languageTag})")
            }
        }

        /**
         * Flash-highlight a selected node then invoke the callback.
         */
        private fun flashSelectedNode(nodeIndex: Int, onComplete: () -> Unit) {
            selectedNodeIndex = nodeIndex
            selectionFlashAnimator?.cancel()
            selectionFlashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = SELECTION_FLASH_DURATION
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    selectionFlashAlpha = animator.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        selectedNodeIndex = -1
                        onComplete()
                    }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        selectedNodeIndex = -1
                        onComplete()
                    }
                })
                start()
            }
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        fun cleanup() {
            Log.d(TAG, "cleanup")
            fadeAnimator?.cancel()
            fadeAnimator = null
            selectionFlashAnimator?.cancel()
            selectionFlashAnimator = null
            fadeAlpha = 0f
            onWordSelected = null
            onDismissed = null
            onTouchInteraction = null
            onNeologismRequested = null
            onRotationCompleted = null
            onMultiTouchStateChanged = null
            nodes = emptyList()
            contextWords = emptyList()
            isLoading = false
            cumulativeRotation = 0f
            liveRotationDelta = 0f
            highlightedNodeIndices.clear()
            selectedNodeIndex = -1
        }

        override fun onDetachedFromWindow() {
            cleanup()
            super.onDetachedFromWindow()
        }

        companion object {
            private const val TAG = "SemGraph.Overlay"
            const val CROSS_FADE_DURATION = 400L
            private const val SWIPE_DOWN_THRESHOLD = 80f
            private const val NEOLOGISM_ANGLE_THRESHOLD = 5.0 // degrees
            private const val SELECTION_FLASH_DURATION = 350L
        }
    }
