package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.services.sourceNotificationPreferenceKey

/**
 * Dynamic notification settings for every currently installed provider.
 * The worker reads the same per-provider preference keys.
 */
object SourceNotificationSettingsDialog {
    private val intervals = longArrayOf(15L, 30L, 60L, 120L)

    fun show(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val providers = APIHolder.apis.withLock { APIHolder.apis.toList() }
            .filter { it.hasMainPage }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }

        val currentInterval = preferences.getString(
            context.getString(R.string.source_notification_scan_interval_key), "60"
        )?.toLongOrNull()?.coerceIn(15L, 120L) ?: 60L
        var selectedInterval = intervals.indexOf(currentInterval).coerceAtLeast(0)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        val intervalButton = Button(context).apply {
            text = context.getString(
                R.string.source_notification_scan_interval_title
            ) + ": " + intervalLabel(context, intervals[selectedInterval])
            setOnClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.source_notification_scan_interval_title)
                    .setSingleChoiceItems(
                        intervals.map { intervalLabel(context, it) }.toTypedArray(),
                        selectedInterval
                    ) { dialog, which ->
                        selectedInterval = which
                        text = context.getString(R.string.source_notification_scan_interval_title) +
                            ": " + intervalLabel(context, intervals[selectedInterval])
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        root.addView(intervalButton)

        val controls = mutableListOf<Triple<String, CheckBox, CheckBox>>()
        val movieControls = mutableMapOf<String, CheckBox>()
        if (providers.isEmpty()) {
            root.addView(TextView(context).apply {
                text = context.getString(R.string.source_notifications_empty_summary)
                setPadding(0, 16, 0, 16)
            })
        } else {
            providers.forEach { provider ->
                root.addView(TextView(context).apply {
                    text = provider.name
                    textSize = 17f
                    setPadding(0, 18, 0, 4)
                })
                val enabled = CheckBox(context).apply {
                    text = context.getString(R.string.source_notifications_enabled, provider.name)
                    isChecked = preferences.getBoolean(
                        sourceNotificationPreferenceKey(provider.name, "enabled"), false
                    )
                }
                val episodes = CheckBox(context).apply {
                    text = context.getString(R.string.source_notifications_episodes, provider.name)
                    isChecked = preferences.getBoolean(
                        sourceNotificationPreferenceKey(provider.name, "episodes"), true
                    )
                }
                val movies = CheckBox(context).apply {
                    text = context.getString(R.string.source_notifications_movies, provider.name)
                    isChecked = preferences.getBoolean(
                        sourceNotificationPreferenceKey(provider.name, "movies"), true
                    )
                }
                root.addView(enabled)
                root.addView(episodes)
                root.addView(movies)
                controls.add(Triple(provider.name, enabled, episodes))
                // Store movie control separately with the provider name in a local map below.
                movieControls[provider.name] = movies
            }
        }

        val scroll = ScrollView(context).apply {
            addView(root, ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.source_notifications_category)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                preferences.edit {
                    putString(
                        context.getString(R.string.source_notification_scan_interval_key),
                        intervals[selectedInterval].toString()
                    )
                    controls.forEach { (name, enabled, episodes) ->
                        putBoolean(sourceNotificationPreferenceKey(name, "enabled"), enabled.isChecked)
                        putBoolean(sourceNotificationPreferenceKey(name, "episodes"), episodes.isChecked)
                        putBoolean(
                            sourceNotificationPreferenceKey(name, "movies"),
                            movieControls[name]?.isChecked ?: true
                        )
                    }
                }
                SubscriptionWorkManager.enqueuePeriodicWork(context, intervals[selectedInterval])
            }
            .show()
    }

    private fun intervalLabel(context: Context, minutes: Long): String {
        val index = intervals.indexOf(minutes).coerceAtLeast(0)
        return context.resources.getStringArray(R.array.source_notification_scan_intervals)[index]
    }
}
