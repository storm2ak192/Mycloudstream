package com.lagradost.cloudstream3.utils

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import java.util.Locale

/**
 * Stores only byte counts observed by Media3 transfer/cache callbacks.
 * Values are not estimates and are kept separate from download traffic.
 */
object VideoCacheStats {
    private const val PREFS = "video_traffic_stats"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordNetworkBytes(context: Context, bytes: Long, mediaUrl: String?) {
        if (bytes <= 0L) return
        val p = prefs(context)
        val host = runCatching { Uri.parse(mediaUrl).host }.getOrNull() ?: "unknown"
        val mediaKey = mediaUrl?.substringBefore("?")?.take(240) ?: "unknown"
        val hostKey = "host_${host.hashCode()}"
        val mediaId = "media_${mediaKey.hashCode()}"
        p.edit()
            .putLong("playback_network_bytes", p.getLong("playback_network_bytes", 0L) + bytes)
            .putLong(hostKey, p.getLong(hostKey, 0L) + bytes)
            .putString("${hostKey}_label", host)
            .putLong(mediaId, p.getLong(mediaId, 0L) + bytes)
            .putString("${mediaId}_label", mediaKey)
            .apply()
    }

    @Synchronized
    fun recordCachedBytes(context: Context, bytes: Long) {
        if (bytes <= 0L) return
        val p = prefs(context)
        p.edit().putLong(
            "playback_cache_bytes",
            p.getLong("playback_cache_bytes", 0L) + bytes
        ).apply()
    }

    fun getPlaybackNetworkBytes(context: Context): Long =
        prefs(context).getLong("playback_network_bytes", 0L)

    fun getPlaybackCachedBytes(context: Context): Long =
        prefs(context).getLong("playback_cache_bytes", 0L)

    fun getSummary(context: Context): String {
        val p = prefs(context)
        val formatter = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
        val hosts = p.all.entries
            .filter { it.key.startsWith("host_") && !it.key.endsWith("_label") && it.value is Long }
            .sortedByDescending { it.value as Long }
            .take(5)
            .joinToString("\n") { entry ->
                val label = p.getString("${entry.key}_label", "Unknown source")
                "$label: ${formatter(entry.value as Long)}"
            }
        val media = p.all.entries
            .filter { it.key.startsWith("media_") && !it.key.endsWith("_label") && it.value is Long }
            .sortedByDescending { it.value as Long }
            .take(5)
            .joinToString("\n") { entry ->
                val label = p.getString("${entry.key}_label", "Unknown media")
                "${label?.substringAfterLast('/')}: ${formatter(entry.value as Long)}"
            }
        return buildString {
            append("Playback data from internet: ${formatter(getPlaybackNetworkBytes(context))}\n")
            append("Playback data reused from cache: ${formatter(getPlaybackCachedBytes(context))}\n")
            append("Downloads: not measured by this playback counter\n")
            append("\nTop sources:\n")
            append(if (hosts.isBlank()) "No measured traffic yet" else hosts)
            append("\n\nTop episodes/videos:\n")
            append(if (media.isBlank()) "No measured traffic yet" else media)
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
