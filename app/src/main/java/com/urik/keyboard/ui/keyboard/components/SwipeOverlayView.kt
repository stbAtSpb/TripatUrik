package com.urik.keyboard.ui.keyboard.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.urik.keyboard.theme.ThemeManager

class SwipeOverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        companion object {
            private const val MAX_PATH_POINTS = 500
            private const val MIN_DISTANCE_THRESHOLD_SQUARED = 25f
            private const val FADE_DURATION = 800L

            private const val ECG_CYCLE_MS = 1500L
            private const val ECG_CURSOR_BLINK_MS = 500L
            private const val ECG_LINE_LENGTH_DP = 80f
            private const val ECG_MARGIN_DP = 16f
            private const val ECG_TRANSITION_MS = 150L

            private const val LETTER_GLOW_DURATION_MS = 1500L
            private const val LETTER_FLASH_DURATION_MS = 200L
        }

        data class GlowingLetter(
            val char: Char,
            val x: Float,
            val y: Float,
            val timestamp: Long,
        )

        private val swipePath = Path()

        private val swipePaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 8f
                alpha = 180
            }

        private val startDotPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 200
            }

        private val currentDotPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                alpha = 160
            }

        private val shadowPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 12f
                alpha = 60
            }

        private val ecgPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = 3f
            }

        private val ecgCursorPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

        private val glowPaint =
            Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

        private val glowTextPaint =
            Paint().apply {
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

        private var isActive = false
        private val startPoint = PointF()
        private var hasStartPoint = false
        private val currentPoint = PointF()
        private var hasCurrentPoint = false

        private val pathPointsX = FloatArray(MAX_PATH_POINTS)
        private val pathPointsY = FloatArray(MAX_PATH_POINTS)
        private var pathPointCount = 0

        private var fadeAlpha = 1.0f
        private var pulseScale = 1.0f
        private var fadeAnimator: ValueAnimator? = null

        private var themeManager: ThemeManager? = null

        // ECG idle state
        private var isIdleActive = false
        private var ecgProgress = 0f
        private var ecgCursorVisible = true
        private var ecgAnimator: ValueAnimator? = null
        private var ecgCursorAnimator: ValueAnimator? = null
        private val ecgPath = Path()
        private var ecgBaseX = 0f
        private var ecgBaseY = 0f
        private var ecgLineLength = 0f
        private var ecgMargin = 0f

        // ECG → touch transition
        private var isTransitioning = false
        private var transitionProgress = 0f
        private var transitionTargetX = 0f
        private var transitionTargetY = 0f
        private var transitionAnimator: ValueAnimator? = null

        // Letter glow
        private val glowingLetters = mutableListOf<GlowingLetter>()

        fun setThemeManager(manager: ThemeManager) {
            themeManager = manager
        }

        fun resetColors() {
            colorsInitialized = false
        }

        private var pulseAnimator: ValueAnimator? = null

        private var colorsInitialized = false

        fun startIdleAnimation() {
            if (isIdleActive || isActive) return

            if (!colorsInitialized) {
                initializeColors()
                colorsInitialized = true
            }

            isIdleActive = true
            val density = resources.displayMetrics.density
            ecgMargin = ECG_MARGIN_DP * density
            ecgLineLength = ECG_LINE_LENGTH_DP * density
            ecgBaseX = ecgMargin
            ecgBaseY = ecgMargin + 10f * density

            ecgAnimator?.cancel()
            ecgAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ECG_CYCLE_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    addUpdateListener { animator ->
                        ecgProgress = animator.animatedValue as Float
                        invalidate()
                    }
                    start()
                }

            ecgCursorAnimator?.cancel()
            ecgCursorAnimator =
                ValueAnimator.ofInt(0, 1).apply {
                    duration = ECG_CURSOR_BLINK_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { animator ->
                        ecgCursorVisible = (animator.animatedValue as Int) == 1
                    }
                    start()
                }
        }

        fun stopIdleAnimation() {
            isIdleActive = false
            ecgAnimator?.cancel()
            ecgAnimator = null
            ecgCursorAnimator?.cancel()
            ecgCursorAnimator = null
        }

        fun startSwipe(point: PointF) {
            if (isIdleActive) {
                stopIdleAnimation()
                startTransitionToTouch(point)
                return
            }

            beginSwipeTrail(point)
        }

        private fun startTransitionToTouch(point: PointF) {
            isTransitioning = true
            transitionTargetX = point.x
            transitionTargetY = point.y
            transitionProgress = 0f

            transitionAnimator?.cancel()
            transitionAnimator =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ECG_TRANSITION_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animator ->
                        transitionProgress = animator.animatedValue as Float
                        invalidate()
                    }
                    addListener(
                        object : android.animation.Animator.AnimatorListener {
                            override fun onAnimationStart(animation: android.animation.Animator) {}
                            override fun onAnimationRepeat(animation: android.animation.Animator) {}
                            override fun onAnimationCancel(animation: android.animation.Animator) {
                                finishTransition(point)
                            }
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                finishTransition(point)
                            }
                        },
                    )
                    start()
                }
        }

        private fun finishTransition(point: PointF) {
            isTransitioning = false
            transitionAnimator = null
            beginSwipeTrail(point)
        }

        private fun beginSwipeTrail(point: PointF) {
            cleanupSwipeState()

            isActive = true
            startPoint.set(point.x, point.y)
            hasStartPoint = true
            currentPoint.set(point.x, point.y)
            hasCurrentPoint = true

            pathPointCount = 0
            pathPointsX[0] = point.x
            pathPointsY[0] = point.y
            pathPointCount = 1

            swipePath.reset()
            swipePath.moveTo(point.x, point.y)

            fadeAlpha = 1.0f
            pulseScale = 1.0f

            if (!colorsInitialized) {
                initializeColors()
                colorsInitialized = true
            }

            pulseAnimator =
                ValueAnimator.ofFloat(0.7f, 1.3f).apply {
                    duration = 800L
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    addUpdateListener { animator ->
                        pulseScale = animator.animatedValue as Float
                    }
                    start()
                }

            invalidate()
        }

        private fun initializeColors() {
            try {
                val theme = themeManager?.currentTheme?.value
                if (theme != null) {
                    swipePaint.color = theme.colors.swipePrimary
                    startDotPaint.color = theme.colors.swipePrimary
                    currentDotPaint.color = theme.colors.swipeSecondary
                    shadowPaint.color = 0x40000000
                    ecgPaint.color = theme.colors.swipePrimary
                    ecgCursorPaint.color = theme.colors.swipePrimary
                    glowPaint.color = theme.colors.swipePrimary
                    glowTextPaint.color = theme.colors.swipePrimary
                } else {
                    setDefaultColors()
                }
            } catch (_: Exception) {
                setDefaultColors()
            }
        }

        private fun setDefaultColors() {
            val primary = 0xFFd4d2a5.toInt()
            swipePaint.color = primary
            startDotPaint.color = primary
            currentDotPaint.color = 0xFFfcdebe.toInt()
            shadowPaint.color = 0x40000000
            ecgPaint.color = primary
            ecgCursorPaint.color = primary
            glowPaint.color = primary
            glowTextPaint.color = primary
        }

        fun updateSwipe(point: PointF) {
            if (!isActive) return

            if (pathPointCount > 0) {
                val lastX = pathPointsX[pathPointCount - 1]
                val lastY = pathPointsY[pathPointCount - 1]
                val distanceSquared = calculateDistanceSquared(lastX, lastY, point.x, point.y)

                if (distanceSquared > MIN_DISTANCE_THRESHOLD_SQUARED) {
                    if (pathPointCount < MAX_PATH_POINTS) {
                        currentPoint.set(point.x, point.y)
                        hasCurrentPoint = true

                        pathPointsX[pathPointCount] = point.x
                        pathPointsY[pathPointCount] = point.y
                        pathPointCount++

                        val controlX = (lastX + point.x) / 2
                        val controlY = (lastY + point.y) / 2
                        swipePath.quadTo(controlX, controlY, point.x, point.y)

                        invalidate()
                    }
                }
            }
        }

        fun endSwipe() {
            isActive = false
            startFadeAnimation()
        }

        fun highlightLetter(char: Char, position: PointF) {
            glowingLetters.add(GlowingLetter(char, position.x, position.y, System.currentTimeMillis()))
            invalidate()
        }

        private fun startFadeAnimation() {
            fadeAnimator?.cancel()

            fadeAnimator =
                ValueAnimator.ofFloat(1.0f, 0.0f).apply {
                    duration = FADE_DURATION
                    interpolator = DecelerateInterpolator()

                    addUpdateListener { animator ->
                        fadeAlpha = animator.animatedValue as Float
                        if (fadeAlpha <= 0f) {
                            cleanupSwipeState()
                        }
                        invalidate()
                    }

                    addListener(
                        object : android.animation.Animator.AnimatorListener {
                            override fun onAnimationStart(animation: android.animation.Animator) {}

                            override fun onAnimationRepeat(animation: android.animation.Animator) {}

                            override fun onAnimationCancel(animation: android.animation.Animator) {
                                cleanupSwipeState()
                            }

                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                cleanupSwipeState()
                            }
                        },
                    )

                    start()
                }
        }

        private fun cleanupSwipeState() {
            val animator = fadeAnimator
            fadeAnimator = null

            animator?.removeAllListeners()
            animator?.removeAllUpdateListeners()
            animator?.cancel()

            val pulse = pulseAnimator
            pulseAnimator = null
            pulse?.removeAllUpdateListeners()
            pulse?.cancel()

            pathPointCount = 0
            swipePath.reset()
            hasStartPoint = false
            hasCurrentPoint = false
            isActive = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (isIdleActive) {
                drawEcgIdle(canvas)
            }

            if (isTransitioning) {
                drawEcgTransition(canvas)
            }

            drawGlowingLetters(canvas)

            if (pathPointCount == 0 && !isActive) return
            if (fadeAlpha <= 0f) return

            drawSwipeTrail(canvas)
        }

        private fun drawEcgIdle(canvas: Canvas) {
            if (!colorsInitialized) {
                initializeColors()
                colorsInitialized = true
            }

            ecgPaint.alpha = 120
            ecgPath.reset()

            val y = ecgBaseY
            val startX = ecgBaseX
            val totalLen = ecgLineLength
            val density = resources.displayMetrics.density
            val peakHeight = 15f * density
            val peakWidth = 8f * density

            // QRS complex position based on progress
            val qrsCenter = totalLen * ecgProgress

            ecgPath.moveTo(startX, y)

            // Draw flat line up to QRS, then spike, then flat again
            val qrsStart = qrsCenter - peakWidth
            val qrsEnd = qrsCenter + peakWidth

            if (qrsStart > 0) {
                ecgPath.lineTo(startX + qrsStart, y)
            }

            // QRS spike: small down, big up, big down, small up
            if (qrsCenter in 0f..totalLen) {
                val clampedStart = qrsStart.coerceAtLeast(0f)
                ecgPath.moveTo(startX + clampedStart, y)
                ecgPath.lineTo(startX + (qrsCenter - peakWidth * 0.3f).coerceAtLeast(0f), y + 3f * density)
                ecgPath.lineTo(startX + qrsCenter, y - peakHeight)
                ecgPath.lineTo(startX + (qrsCenter + peakWidth * 0.3f).coerceAtMost(totalLen), y + peakHeight * 0.6f)
                ecgPath.lineTo(startX + qrsEnd.coerceAtMost(totalLen), y)
            }

            if (qrsEnd < totalLen) {
                ecgPath.lineTo(startX + totalLen, y)
            }

            canvas.drawPath(ecgPath, ecgPaint)

            // Blinking cursor at end of line
            if (ecgCursorVisible) {
                ecgCursorPaint.alpha = 160
                canvas.drawCircle(startX + totalLen + 4f * density, y, 3f * density, ecgCursorPaint)
            }
        }

        private fun drawEcgTransition(canvas: Canvas) {
            ecgPaint.alpha = (120 * (1f - transitionProgress)).toInt()

            val fromX = ecgBaseX + ecgLineLength
            val fromY = ecgBaseY
            val toX = transitionTargetX
            val toY = transitionTargetY

            val currentX = fromX + (toX - fromX) * transitionProgress
            val currentY = fromY + (toY - fromY) * transitionProgress

            ecgPath.reset()
            ecgPath.moveTo(ecgBaseX, ecgBaseY)
            ecgPath.lineTo(currentX, currentY)
            canvas.drawPath(ecgPath, ecgPaint)

            // Draw cursor dot at transition endpoint
            ecgCursorPaint.alpha = (160 * (1f - transitionProgress * 0.5f)).toInt()
            val density = resources.displayMetrics.density
            canvas.drawCircle(currentX, currentY, 3f * density, ecgCursorPaint)
        }

        private fun drawGlowingLetters(canvas: Canvas) {
            if (glowingLetters.isEmpty()) return

            val now = System.currentTimeMillis()
            val density = resources.displayMetrics.density
            val iterator = glowingLetters.iterator()

            while (iterator.hasNext()) {
                val glow = iterator.next()
                val elapsed = now - glow.timestamp

                if (elapsed > LETTER_GLOW_DURATION_MS) {
                    iterator.remove()
                    continue
                }

                val normalizedTime = elapsed.toFloat() / LETTER_GLOW_DURATION_MS
                val alpha = ((1f - normalizedTime) * 120).toInt().coerceIn(0, 255)

                // Flash scale: quick 1.0→1.3→1.0 in first 200ms
                val flashElapsed = elapsed.toFloat() / LETTER_FLASH_DURATION_MS
                val scale =
                    if (flashElapsed < 1f) {
                        if (flashElapsed < 0.5f) {
                            1f + 0.3f * (flashElapsed * 2f)
                        } else {
                            1.3f - 0.3f * ((flashElapsed - 0.5f) * 2f)
                        }
                    } else {
                        1f
                    }

                val radius = 16f * density * scale

                // Outer glow
                glowPaint.alpha = (alpha * 0.3f).toInt()
                canvas.drawCircle(glow.x, glow.y, radius * 1.5f, glowPaint)

                // Inner glow
                glowPaint.alpha = alpha
                canvas.drawCircle(glow.x, glow.y, radius, glowPaint)

                // Letter text
                glowTextPaint.alpha = (alpha * 1.5f).toInt().coerceAtMost(255)
                glowTextPaint.textSize = 14f * density * scale
                canvas.drawText(
                    glow.char.uppercase(),
                    glow.x,
                    glow.y + 5f * density * scale,
                    glowTextPaint,
                )
            }

            if (glowingLetters.isNotEmpty()) {
                invalidate()
            }
        }

        private fun drawSwipeTrail(canvas: Canvas) {
            val trailAlpha = (fadeAlpha * 180).toInt()
            val shadowAlpha = (fadeAlpha * 60).toInt()
            val startAlpha = (fadeAlpha * 200).toInt()
            val currentAlpha = (fadeAlpha * 160).toInt()

            try {
                if (pathPointCount > 1) {
                    shadowPaint.alpha = shadowAlpha
                    canvas.drawPath(swipePath, shadowPaint)
                }

                if (pathPointCount > 1) {
                    swipePaint.alpha = trailAlpha
                    canvas.drawPath(swipePath, swipePaint)
                }

                if (hasStartPoint) {
                    startDotPaint.alpha = startAlpha
                    val startRadius = 10f * fadeAlpha
                    canvas.drawCircle(startPoint.x, startPoint.y, startRadius, startDotPaint)

                    val ringAlpha = (startAlpha * 0.5f).toInt()
                    shadowPaint.alpha = ringAlpha
                    shadowPaint.strokeWidth = 2f
                    canvas.drawCircle(startPoint.x, startPoint.y, startRadius + 4f, shadowPaint)
                    shadowPaint.strokeWidth = 12f
                }

                if (isActive && hasCurrentPoint) {
                    currentDotPaint.alpha = currentAlpha
                    val currentRadius = 6f * pulseScale * fadeAlpha
                    canvas.drawCircle(currentPoint.x, currentPoint.y, currentRadius, currentDotPaint)
                }
            } catch (_: Exception) {
            }
        }

        private fun calculateDistanceSquared(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return dx * dx + dy * dy
        }

        override fun onDetachedFromWindow() {
            stopIdleAnimation()
            transitionAnimator?.cancel()
            transitionAnimator = null
            cleanupSwipeState()
            themeManager = null
            super.onDetachedFromWindow()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            colorsInitialized = false
        }
    }
