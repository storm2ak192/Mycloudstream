package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Generic integration for any installed app that advertises ACTION_VIEW for video/*.
 * The package name is persisted as the action's sourcePlugin component, making each
 * discovered app independently selectable as the preferred external player.
 */
class GenericExternalPlayerPackage(
    packageId: String,
    label: String
) : OpenInAppAction(
    appName = txt(label),
    packageName = packageId
) {
    init {
        sourcePlugin = "external:$packageId"
    }

    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val selected = index?.let { result.links.getOrNull(it) }
            ?: result.links.singleOrNull()
            ?: throw IllegalArgumentException("Choose a server before opening the external player")
        intent.setDataAndType(selected.url.toUri(), "video/*")
        intent.putExtra(Intent.EXTRA_TITLE, video.name)
        intent.putExtra("title", video.name)
        intent.putExtra("name", video.name)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
