package com.urik.keyboard.ui.concentric

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.urik.keyboard.R

/**
 * Standalone Activity for Phase 0+1 performance benchmarking.
 * Hosts a ConcentricBenchmarkView (SurfaceView) with control buttons
 * for dynamic bubble management and stress testing.
 */
class ConcentricBenchmarkActivity : AppCompatActivity() {

    private var benchmarkView: ConcentricBenchmarkView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stressTestRunning = false
    private var stressAddedIds = mutableListOf<Int>()

    // Words pool for stress test
    private val stressWords = listOf(
        "chat", "soleil", "lune", "mer", "vent", "feu", "eau", "terre",
        "amour", "paix", "joie", "vie", "nuit", "jour", "ciel", "temps",
        "rire", "chant", "doux", "fort", "bleu", "vert", "rose", "noir",
        "pain", "vin", "cafe", "fleur", "arbre", "pont", "rue", "lac",
        "ile", "roi", "reve", "jeu", "mot", "voix", "main", "pied",
        "coeur", "esprit", "monde", "route", "ville", "maison", "ecole", "livre",
    )
    private var stressWordIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.concentric_benchmark_title)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF2e1f1a.toInt())
        }

        // Info bar
        val infoBar = TextView(this).apply {
            text = getString(R.string.concentric_benchmark_info_phase1)
            setTextColor(0xFFffc4a3.toInt())
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(0xFF1a3d4f.toInt())
        }
        root.addView(infoBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        // Benchmark SurfaceView
        val view = ConcentricBenchmarkView(this)
        benchmarkView = view
        root.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        // Control buttons bar
        val buttonBar = createButtonBar()
        root.addView(buttonBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)
    }

    private fun createButtonBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 16)
            setBackgroundColor(0xFF1a3d4f.toInt())
        }

        val buttonParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(4, 0, 4, 0)
        }

        // Add bubble button
        bar.addView(
            Button(this).apply {
                text = "+1"
                textSize = 12f
                setOnClickListener { addRandomBubble() }
            },
            buttonParams,
        )

        // Add 5 bubbles
        bar.addView(
            Button(this).apply {
                text = "+5"
                textSize = 12f
                setOnClickListener { repeat(5) { addRandomBubble() } }
            },
            buttonParams,
        )

        // Stress test button
        bar.addView(
            Button(this).apply {
                text = "STRESS"
                textSize = 12f
                setOnClickListener { toggleStressTest(this) }
            },
            buttonParams,
        )

        // Reset button
        bar.addView(
            Button(this).apply {
                text = "RESET"
                textSize = 12f
                setOnClickListener {
                    stressTestRunning = false
                    stressAddedIds.clear()
                    benchmarkView?.resetToInitial()
                    benchmarkView?.resetMetrics()
                    Toast.makeText(context, "Reset to 30 bubbles", Toast.LENGTH_SHORT).show()
                }
            },
            buttonParams,
        )

        return bar
    }

    private fun addRandomBubble() {
        val ring = (0..2).random()
        val word = stressWords[stressWordIndex % stressWords.size]
        stressWordIndex++
        val id = benchmarkView?.addBubble(ring, word, priority = 0.5f) ?: return
        stressAddedIds.add(id)
    }

    private fun toggleStressTest(button: Button) {
        if (stressTestRunning) {
            stressTestRunning = false
            button.text = "STRESS"
            return
        }

        stressTestRunning = true
        button.text = "STOP"
        benchmarkView?.resetMetrics()

        // Add 10 bubbles/sec for 5 seconds = 50 bubbles total
        var added = 0
        val maxToAdd = 50
        val intervalMs = 100L // 1 bubble every 100ms = 10/sec

        val runnable = object : Runnable {
            override fun run() {
                if (!stressTestRunning || added >= maxToAdd) {
                    stressTestRunning = false
                    button.text = "STRESS"
                    val total = benchmarkView?.getBubbleCount() ?: 0
                    Toast.makeText(
                        this@ConcentricBenchmarkActivity,
                        "Stress done: $total bubbles, overlaps: ${benchmarkView?.lastOverlapCount}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                addRandomBubble()
                added++
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    override fun onDestroy() {
        stressTestRunning = false
        handler.removeCallbacksAndMessages(null)
        benchmarkView = null
        super.onDestroy()
    }
}
