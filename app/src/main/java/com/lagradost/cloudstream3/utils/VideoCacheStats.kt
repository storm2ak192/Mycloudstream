package com.lagradost.cloudstream3.utils

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import java.security.MessageDigest

/** Byte counts observed by the app's playback and native download data sources. */
object VideoCacheStats {
    private const val PREFS = "video_traffic_stats"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

    fun cacheKey(sourceId: String, episodeId: String, resourceKey: String) =
        "cs3cache-v1|$sourceId|$episodeId|$resourceKey"

    @Synchronized
    fun recordCacheLabels(context: Context, sourceId: String, episodeId: String, source: String, episode: String) {
        prefs(context).edit()
            .putString("cache_label_source_$sourceId", source)
            .putString("cache_label_episode_${sourceId}_$episodeId", episode)
            .apply()
    }

    fun cacheLabels(context: Context, sourceId: String, episodeId: String): Pair<String, String> {
        val p = prefs(context)
        return (p.getString("cache_label_source_$sourceId", "Unknown source") ?: "Unknown source") to
            (p.getString("cache_label_episode_${sourceId}_$episodeId", "Unknown episode") ?: "Unknown episode")
    }

    @Synchronized
    fun recordNetworkBytes(context: Context, bytes: Long, mediaUrl: String?) {
        if (bytes <= 0L) return
        val p = prefs(context)
        val host = runCatching { Uri.parse(mediaUrl).host }.getOrNull() ?: "unknown"
        val mediaKey = mediaUrl?.substringBefore("?")?.take(240) ?: "unknown"
        val hostKey = "host_${stableId(host)}"
        val mediaId = "media_${stableId(mediaKey)}"
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
        p.edit().putLong("playback_cache_bytes", p.getLong("playback_cache_bytes", 0L) + bytes).apply()
    }

    @Synchronized
    fun setDownloadLabel(context: Context, downloadId: Int?, label: String) {
        val id = downloadId?.toString() ?: "unknown"
        prefs(context).edit().putString("download_label_$id", label.take(240)).apply()
    }

    @Synchronized
    fun recordDownloadBytes(context: Context, bytes: Long, downloadId: Int?) {
        if (bytes <= 0L) return
        val p = prefs(context)
        val id = downloadId?.toString() ?: "unknown"
        val key = "download_bytes_$id"
        p.edit()
            .putLong("download_network_bytes", p.getLong("download_network_bytes", 0L) + bytes)
            .putLong(key, p.getLong(key, 0L) + bytes)
            .putString("download_label_$id", p.getString("download_label_$id", "Download #$id"))
            .apply()
    }

    fun getPlaybackNetworkBytes(context: Context) = prefs(context).getLong("playback_network_bytes", 0L)
    fun getPlaybackCachedBytes(context: Context) = prefs(context).getLong("playback_cache_bytes", 0L)

    fun getSummary(context: Context): String {
        val p = prefs(context)
        val fmt = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
        val hosts = p.all.entries.filter { it.key.startsWith("host_") && !it.key.endsWith("_label") && it.value is Long }
            .sortedByDescending { it.value as Long }.take(8).joinToString("\n") {
                "${p.getString("${it.key}_label", "Unknown source")}: ${fmt(it.value as Long)}"
            }
        val media = p.all.entries.filter { it.key.startsWith("media_") && !it.key.endsWith("_label") && it.value is Long }
            .sortedByDescending { it.value as Long }.take(8).joinToString("\n") {
                val label = p.getString("${it.key}_label", "Unknown media") ?: "Unknown media"
                "${label.substringAfterLast('/')}: ${fmt(it.value as Long)}"
            }
        val downloads = p.all.entries.filter { it.key.startsWith("download_bytes_") && it.value is Long }
            .sortedByDescending { it.value as Long }.take(8).joinToString("\n") {
                val id = it.key.removePrefix("download_bytes_")
                "${p.getString("download_label_$id", "Download #$id")}: ${fmt(it.value as Long)}"
            }
        return buildString {
            append("Playback from internet: ${fmt(getPlaybackNetworkBytes(context))}\n")
            append("Playback reused from cache: ${fmt(getPlaybackCachedBytes(context))}\n")
            append("Downloads transferred: ${fmt(p.getLong("download_network_bytes", 0L))}\n")
            append("\nPlayback sources:\n"); append(if (hosts.isBlank()) "No measured traffic yet" else hosts)
            append("\n\nPlayback videos/episodes (URL-based):\n"); append(if (media.isBlank()) "No measured traffic yet" else media)
            append("\n\nDownloads by item:\n"); append(if (downloads.isBlank()) "No measured downloads yet" else downloads)
            append("\n\nCounts are payload bytes observed by app data sources; headers and transport overhead are excluded.")
        }
    }

    @Synchronized
    fun clear(context: Context) {
        val p = prefs(context)
        val labels = p.all.filterKeys { it.startsWith("cache_label_") }
        p.edit().clear().apply()
        if (labels.isNotEmpty()) p.edit().apply {
            labels.forEach { (key, value) -> if (value is String) putString(key, value) }
        }
    }

    @Synchronized
    fun clearCacheLabels(context: Context) {
        val p = prefs(context)
        p.edit().apply { p.all.keys.filter { it.startsWith("cache_label_") }.forEach(::remove) }
    }
}
