package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Small independent settings editor for the two informational player overlays.
 * Settings are stored separately per overlay prefix.
 */
object PlayerOverlaySettings {
    private val colors = listOf(
        "White" to Color.WHITE,
        "Black" to Color.BLACK,
        "Yellow" to Color.YELLOW,
        "Cyan" to Color.CYAN,
        "Green" to Color.GREEN,
        "Red" to Color.RED,
        "Blue" to Color.BLUE
    )

    fun show(context: Context, prefix: String, title: String, rightSide: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val enabled = CheckBox(context).apply {
            text = "Show this group"
            isChecked = prefs.getBoolean("${prefix}_enabled", false)
        }
        val position = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val top = RadioButton(context).apply { text = "Top"; id = 1 }
        val bottom = RadioButton(context).apply { text = "Bottom"; id = 2 }
        position.addView(top)
        position.addView(bottom)
        position.check(if (prefs.getBoolean("${prefix}_top", true)) 1 else 2)

        var textColor = prefs.getInt("${prefix}_text_color", Color.WHITE)
        var backgroundColor = prefs.getInt("${prefix}_background_color", Color.BLACK)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        root.addView(enabled)
        root.addView(TextView(context).apply { text = "Position" })
        root.addView(position)

        fun addSeek(label: String, key: String, min: Int, max: Int, default: Int): SeekBar {
            val current = prefs.getInt("${prefix}_$key", default)
            val caption = TextView(context).apply { text = "$label: $current" }
            root.addView(caption)
            val seek = SeekBar(context).apply {
                this.max = max - min
                progress = (current - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                        caption.text = "$label: ${value + min}"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            root.addView(seek)
            return seek
        }

        val offset = addSeek("Horizontal offset", "offset", 0, 300, 0)
        val size = addSeek("Text size (sp)", "size", 10, 30, 14)
        val alpha = addSeek("Background opacity (%)", "alpha", 0, 100, 65)

        fun colorButton(label: String, initial: Int, onSelected: (Int) -> Unit): Button {
            return Button(context).apply {
                text = "$label: ${colors.firstOrNull { it.second == initial }?.first ?: "Custom"}"
                setOnClickListener {
                    val selected = colors.indexOfFirst { it.second == initial }.coerceAtLeast(0)
                    AlertDialog.Builder(context)
                        .setTitle(label)
                        .setSingleChoiceItems(colors.map { it.first }.toTypedArray(), selected) { dialog, which ->
                            onSelected(colors[which].second)
                            text = "$label: ${colors[which].first}"
                            dialog.dismiss()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        root.addView(colorButton("Text color", textColor) { textColor = it })
        root.addView(colorButton("Background color", backgroundColor) { backgroundColor = it })

        val scroll = ScrollView(context).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit {
                    putBoolean("${prefix}_enabled", enabled.isChecked)
                    putBoolean("${prefix}_top", position.checkedRadioButtonId != 2)
                    putInt("${prefix}_offset", offset.progress)
                    putInt("${prefix}_size", size.progress + 10)
                    putInt("${prefix}_alpha", alpha.progress)
                    putInt("${prefix}_text_color", textColor)
                    putInt("${prefix}_background_color", backgroundColor)
                }
            }
            .show()
    }
}
