package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.preference.PreferenceManager

object DownloadTargetPreference {
    const val KEY = "preferred_download_target"
    const val ASK = "ask"
    const val IN_APP = "in_app"
    const val ONE_DM = "one_dm"
    const val EXTERNAL = "external"

    fun get(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, ASK) ?: ASK

    fun set(context: Context, value: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(KEY, value).apply()
    }
}
