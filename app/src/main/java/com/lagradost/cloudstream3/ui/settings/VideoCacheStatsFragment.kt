package com.lagradost.cloudstream3.ui.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.utils.VideoCacheStats
import java.io.File

class VideoCacheStatsFragment : Fragment() {
    private var contentLayout: LinearLayout? = null
    private var playbackLiveView: TextView? = null
    private var downloadLiveView: TextView? = null
    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficRefresh = object : Runnable {
        override fun run() {
            val context = this@VideoCacheStatsFragment.context ?: return
            playbackLiveView?.text = "Live playback traffic (this app session): ${Formatter.formatShortFileSize(context, VideoCacheStats.getLivePlaybackBytes())}"
            downloadLiveView?.text = "Live internal download traffic (this app session): ${Formatter.formatShortFileSize(context, VideoCacheStats.getLiveDownloadBytes())}"
            trafficHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val scroll = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 32)
        }
        contentLayout = layout
        scroll.addView(layout)
        renderPage()
        return scroll
    }

    override fun onResume() {
        super.onResume()
        renderPage()
        trafficHandler.removeCallbacks(trafficRefresh)
        trafficHandler.post(trafficRefresh)
    }

    override fun onPause() {
        trafficHandler.removeCallbacks(trafficRefresh)
        super.onPause()
    }

    override fun onDestroyView() {
        trafficHandler.removeCallbacks(trafficRefresh)
        playbackLiveView = null
        downloadLiveView = null
        contentLayout = null
        super.onDestroyView()
    }

    private fun addText(text: String, size: Float = 15f, bold: Boolean = false) {
        val view = TextView(requireContext()).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 12, 0, 12)
        }
        contentLayout?.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        val button = Button(requireContext()).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
        contentLayout?.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })
    }

    private fun renderPage() {
        val layout = contentLayout ?: return
        layout.removeAllViews()
        val context = requireContext()
        val cacheDirectory = File(context.filesDir, "video_cache")
        val diskBytes = cacheDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val playbackView = TextView(context).apply { textSize = 15f; setPadding(0, 12, 0, 12) }
        val downloadView = TextView(context).apply { textSize = 15f; setPadding(0, 12, 0, 12) }
        playbackLiveView = playbackView
        downloadLiveView = downloadView
        layout.addView(playbackView)
        layout.addView(downloadView)
        addText(VideoCacheStats.getSummary(context), 15f)
        addText(getString(R.string.video_cache_disk_usage, Formatter.formatShortFileSize(context, diskBytes)), 14f, true)
        addButton(getString(R.string.smart_video_cache_stats_reset)) {
            AlertDialog.Builder(context)
                .setTitle(R.string.smart_video_cache_stats_reset)
                .setMessage(R.string.video_cache_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reset_btn) { _, _ ->
                    VideoCacheStats.clear(context)
                    renderPage()
                }
                .show()
        }

        val entries = CS3IPlayer.getVideoCacheEntries(context)
        addText(getString(R.string.video_cache_manage_episodes), 19f, true)
        if (entries.isEmpty()) {
            addText(getString(R.string.video_cache_no_entries), 14f)
        } else {
            entries.forEach { entry ->
                addButton("${entry.episodeLabel}\n${entry.sourceLabel} • ${Formatter.formatShortFileSize(context, entry.bytes)}") {
                    AlertDialog.Builder(context)
                        .setTitle(entry.episodeLabel)
                        .setMessage(R.string.video_cache_delete_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.video_cache_delete_episode) { _, _ ->
                            CS3IPlayer.clearVideoCacheEpisode(context, entry.sourceId, entry.episodeId)
                            renderPage()
                        }
                        .setNeutralButton(R.string.video_cache_delete_source) { _, _ ->
                            CS3IPlayer.clearVideoCacheSource(context, entry.sourceId)
                            renderPage()
                        }
                        .show()
                }
            }
        }

        addText(getString(R.string.video_cache_manage_sources), 19f, true)
        entries.groupBy { it.sourceId }.values.forEach { sourceEntries ->
            val first = sourceEntries.first()
            val bytes = sourceEntries.sumOf { it.bytes }
            addButton("${first.sourceLabel} • ${Formatter.formatShortFileSize(context, bytes)}") {
                AlertDialog.Builder(context)
                    .setTitle(first.sourceLabel)
                    .setMessage(R.string.video_cache_delete_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.video_cache_delete_source) { _, _ ->
                        CS3IPlayer.clearVideoCacheSource(context, first.sourceId)
                        renderPage()
                    }
                    .show()
            }
        }
    }
}
