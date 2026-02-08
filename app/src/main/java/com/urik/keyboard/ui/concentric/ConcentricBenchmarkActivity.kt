package com.urik.keyboard.ui.concentric

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.urik.keyboard.R

/**
 * Standalone Activity for Phase 0 performance benchmarking.
 * Hosts a ConcentricBenchmarkView (SurfaceView) fullscreen
 * with an FPS overlay.
 *
 * Accessible from Settings > "Concentric Benchmark (Phase 0)".
 * No dependencies on the keyboard service or any semantic engine.
 */
class ConcentricBenchmarkActivity : AppCompatActivity() {

    private var benchmarkView: ConcentricBenchmarkView? = null

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
            text = getString(R.string.concentric_benchmark_info)
            setTextColor(0xFFffc4a3.toInt())
            textSize = 14f
            setPadding(24, 16, 24, 16)
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
            1f, // fill remaining space
        ))

        setContentView(root)
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
        benchmarkView = null
        super.onDestroy()
    }
}
