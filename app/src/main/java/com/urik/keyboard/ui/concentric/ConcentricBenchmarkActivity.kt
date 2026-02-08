package com.urik.keyboard.ui.concentric

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.urik.keyboard.R

/**
 * Phase 2a benchmark Activity.
 * Hosts ConcentricBenchmarkView with a mini letter keyboard at the bottom
 * for testing the async compute pipeline interactively.
 *
 * Type letters -> compute engine produces suggestions asynchronously ->
 * render thread picks them up via double-buffer -> bubbles update with animation.
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
            text = getString(R.string.concentric_benchmark_info_phase2a)
            setTextColor(0xFFffc4a3.toInt())
            textSize = 11f
            setPadding(24, 8, 24, 8)
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

        // Mini keyboard for testing
        val miniKeyboard = createMiniKeyboard()
        root.addView(miniKeyboard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        setContentView(root)
    }

    private fun createMiniKeyboard(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 8)
            setBackgroundColor(0xFF1a3d4f.toInt())
        }

        // Letter rows (AZERTY layout for French)
        val rows = listOf(
            "azertyuiop",
            "qsdfghjklm",
            "wxcvbn",
        )

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (char in row) {
                val btn = Button(this).apply {
                    text = char.uppercase()
                    textSize = 14f
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = 0
                    minimumHeight = 0
                    setOnClickListener {
                        benchmarkView?.onCharacterInput(char)
                    }
                }
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(1, 1, 1, 1)
                }
                rowLayout.addView(btn, params)
            }

            container.addView(rowLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        // Action row: BACKSPACE, SPACE, CLEAR
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val actionParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(2, 2, 2, 2)
        }

        actionRow.addView(
            Button(this).apply {
                text = "DEL"
                textSize = 12f
                setOnClickListener { benchmarkView?.onBackspace() }
            },
            actionParams,
        )

        actionRow.addView(
            Button(this).apply {
                text = "SPACE"
                textSize = 12f
                setOnClickListener { benchmarkView?.onCharacterInput(' ') }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                setMargins(2, 2, 2, 2)
            },
        )

        actionRow.addView(
            Button(this).apply {
                text = "CLEAR"
                textSize = 12f
                setOnClickListener {
                    benchmarkView?.onClearInput()
                    benchmarkView?.resetMetrics()
                }
            },
            actionParams,
        )

        container.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        return container
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
