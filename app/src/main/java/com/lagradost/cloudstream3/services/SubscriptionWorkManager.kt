package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.preference.PreferenceManager
import androidx.core.net.toUri
import androidx.work.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllSubscriptions
import com.lagradost.cloudstream3.utils.DataStoreHelper.getDub
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

const val SUBSCRIPTION_CHANNEL_ID = "cloudstream3.subscriptions"
const val SUBSCRIPTION_WORK_NAME = "work_subscription"
const val SUBSCRIPTION_CHANNEL_NAME = "Subscriptions"
const val SUBSCRIPTION_CHANNEL_DESCRIPTION = "Notifications for new episodes on subscribed shows"
const val SUBSCRIPTION_NOTIFICATION_ID = 938712897 // Random unique

fun sourceNotificationPreferenceKey(providerName: String, contentType: String): String =
    "source_notifications_${providerName.hashCode()}_$contentType"

class SubscriptionWorkManager(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    companion object {
        fun enqueuePeriodicWork(context: Context?) {
            if (context == null) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicSyncDataWork =
                PeriodicWorkRequest.Builder(SubscriptionWorkManager::class.java, 6, TimeUnit.HOURS)
                    .addTag(SUBSCRIPTION_WORK_NAME)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SUBSCRIPTION_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicSyncDataWork
            )

            // Uncomment below for testing

//            val oneTimeSyncDataWork =
//                OneTimeWorkRequest.Builder(SubscriptionWorkManager::class.java)
//                    .addTag(SUBSCRIPTION_WORK_NAME)
//                    .setConstraints(constraints)
//                    .build()
//
//            WorkManager.getInstance(context).enqueue(oneTimeSyncDataWork)
        }
    }

    private val progressNotificationBuilder =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(context.getString(R.string.subscription_in_progress_notification))
            .setSmallIcon(com.google.android.gms.cast.framework.R.drawable.quantum_ic_refresh_white_24)
            .setProgress(0, 0, true)

    private val updateNotificationBuilder =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateProgress(max: Int, progress: Int, indeterminate: Boolean) {
        notificationManager.notify(
            SUBSCRIPTION_NOTIFICATION_ID, progressNotificationBuilder
                .setProgress(max, progress, indeterminate)
                .build()
        )
    }

    override suspend fun doWork(): Result {
        try {
//        println("Update subscriptions!")
            context.createNotificationChannel(
                SUBSCRIPTION_CHANNEL_ID,
                SUBSCRIPTION_CHANNEL_NAME,
                SUBSCRIPTION_CHANNEL_DESCRIPTION
            )

            val foregroundInfo = if (SDK_INT >= 29)
                ForegroundInfo(
                    SUBSCRIPTION_NOTIFICATION_ID,
                    progressNotificationBuilder.build(),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
                ) else ForegroundInfo(SUBSCRIPTION_NOTIFICATION_ID, progressNotificationBuilder.build(),)
            setForeground(foregroundInfo)

            // Load installed extensions before scanning their first-page feeds.
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context)
            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context, false)

            val subscriptions = getAllSubscriptions()
            val sourcePreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val seenItems = context.getSharedPreferences("source_notification_seen", Context.MODE_PRIVATE)
            val providers = APIHolder.apis.withLock { APIHolder.apis.toList() }

            providers.forEach { api ->
                try {
                    if (!api.hasMainPage) return@forEach
                    val suffix = api.name.hashCode()
                    val sourceEnabled = sourcePreferences.getBoolean(
                        sourceNotificationPreferenceKey(api.name, "enabled"), true
                    )
                    if (!sourceEnabled) return@forEach
                    val episodesEnabled = sourcePreferences.getBoolean(
                        sourceNotificationPreferenceKey(api.name, "episodes"), true
                    )
                    val moviesEnabled = sourcePreferences.getBoolean(
                        sourceNotificationPreferenceKey(api.name, "movies"), true
                    )
                    if (!episodesEnabled && !moviesEnabled) return@forEach
                    val baselineKey = "baseline_$suffix"
                    val hasBaseline = seenItems.getBoolean(baselineKey, false)
                    var receivedAnyPage = false

                    api.mainPage.take(3).forEach pageRequestLoop@ { pageRequest ->
                        try {
                            val page = withTimeoutOrNull(25_000) {
                                api.getMainPage(
                                    1,
                                    MainPageRequest(pageRequest.name, pageRequest.data, pageRequest.horizontalImages)
                                )
                            } ?: return@pageRequestLoop
                            receivedAnyPage = true

                            page.items.flatMap { it.list }.forEach { item ->
                                val itemKey = "item_${suffix}_${item.url.hashCode()}"
                                val previousCount = seenItems.getString(itemKey, null)?.toIntOrNull()
                                val episodeCount = when (item) {
                                    is AnimeSearchResponse -> item.episodes.values.maxOrNull()
                                    is TvSeriesSearchResponse -> item.episodes
                                    else -> null
                                }
                                val isMovie = item is MovieSearchResponse || item.type?.isMovieType() == true

                                val isSubscribedSeries = !isMovie && subscriptions.any {
                                    it.apiName == api.name && it.url == item.url
                                }
                                val notificationText = when {
                                    isMovie && moviesEnabled && hasBaseline && previousCount == null ->
                                        "New movie available from ${api.name}"
                                    !isMovie && !isSubscribedSeries && episodesEnabled && episodeCount != null &&
                                        hasBaseline && previousCount != null && episodeCount > previousCount ->
                                        "New episodes: $previousCount → $episodeCount"
                                    !isMovie && !isSubscribedSeries && episodesEnabled && episodeCount != null &&
                                        hasBaseline && previousCount == null ->
                                        "New series or episodes available from ${api.name}"
                                    else -> null
                                }

                                if (notificationText != null) {
                                    val notificationId = ("${api.name}|${item.url}|$notificationText").hashCode()
                                    val intent = Intent(context, MainActivity::class.java).apply {
                                        data = item.url.toUri()
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }.putExtra(MainActivity.API_NAME_EXTRA_KEY, api.name)
                                    val pendingIntent = PendingIntentCompat.getActivity(context, notificationId, intent, 0, false)
                                    val notification = updateNotificationBuilder
                                        .setContentTitle(item.name)
                                        .setContentText(notificationText)
                                        .setContentIntent(pendingIntent)
                                        .build()
                                    notificationManager.notify(notificationId, notification)
                                }

                                val storedCount = episodeCount ?: previousCount ?: 0
                                seenItems.edit().putString(itemKey, storedCount.toString()).apply()
                            }
                        } catch (t: Throwable) {
                            logError(t)
                        }
                    }
                    if (receivedAnyPage) seenItems.edit().putBoolean(baselineKey, true).apply()
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            if (subscriptions.isEmpty()) return Result.success()

            val max = subscriptions.size
            var progress = 0
            updateProgress(max, progress, true)

            subscriptions.amap { savedData ->
                try {
                    val id = savedData.id ?: return@amap null
                    val api = getApiFromNameNull(savedData.apiName) ?: return@amap null

                    // Reasonable timeout to prevent having this worker run forever.
                    val response = withTimeoutOrNull(60_000) {
                        api.load(savedData.url) as? EpisodeResponse
                    } ?: return@amap null

                    val dubPreference =
                        getDub(id) ?: if (
                            context.getApiDubstatusSettings().contains(DubStatus.Dubbed)
                        ) {
                            DubStatus.Dubbed
                        } else {
                            DubStatus.Subbed
                        }

                    val latestEpisodes = response.getLatestEpisodes()
                    val latestPreferredEpisode = latestEpisodes[dubPreference]

                    val (shouldUpdate, latestEpisode) = if (latestPreferredEpisode != null) {
                        val latestSeenEpisode =
                            savedData.lastSeenEpisodeCount[dubPreference] ?: Int.MIN_VALUE
                        val shouldUpdate = latestPreferredEpisode > latestSeenEpisode
                        shouldUpdate to latestPreferredEpisode
                    } else {
                        val latestEpisode = latestEpisodes[DubStatus.None] ?: Int.MIN_VALUE
                        val latestSeenEpisode =
                            savedData.lastSeenEpisodeCount[DubStatus.None] ?: Int.MIN_VALUE
                        val shouldUpdate = latestEpisode > latestSeenEpisode
                        shouldUpdate to latestEpisode
                    }

                    DataStoreHelper.updateSubscribedData(
                        id,
                        savedData,
                        response
                    )

                    val notificationPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                    val sourceNotificationsEnabled = notificationPreferences.getBoolean(
                        sourceNotificationPreferenceKey(api.name, "enabled"), true
                    )
                    val episodeNotificationsEnabled = notificationPreferences.getBoolean(
                        sourceNotificationPreferenceKey(api.name, "episodes"), true
                    )

                    if (shouldUpdate && sourceNotificationsEnabled && episodeNotificationsEnabled) {
                        val updateHeader = savedData.name
                        val updateDescription = txt(
                            R.string.subscription_episode_released,
                            latestEpisode,
                            savedData.name
                        ).asString(context)

                        val intent = Intent(context, MainActivity::class.java).apply {
                            data = savedData.url.toUri()
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }.putExtra(MainActivity.API_NAME_EXTRA_KEY, api.name)

                        val pendingIntent =
                            PendingIntentCompat.getActivity(context, 0, intent, 0, false)

                        val poster = ioWork {
                            savedData.posterUrl?.let { url ->
                                context.getImageBitmapFromUrl(
                                    url,
                                    savedData.posterHeaders
                                )
                            }
                        }

                        val updateNotification =
                            updateNotificationBuilder.setContentTitle(updateHeader)
                                .setContentText(updateDescription)
                                .setContentIntent(pendingIntent)
                                .setLargeIcon(poster)
                                .build()

                        notificationManager.notify(id, updateNotification)
                    }

                    // You can probably get some issues here since this is async but it does not matter much.
                    updateProgress(max, ++progress, false)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

            return Result.success()
        } catch (t: Throwable) {
            logError(t)
            // ye, while this is not correct, but because gods know why android just crashes
            // and this causes major battery usage as it retries it inf times. This is better, just
            // in case android decides to be android and fuck us
            return Result.success()
        }
    }
}