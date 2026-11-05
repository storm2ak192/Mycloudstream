package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Sends the selected extractor URL directly to an installed 1DM variant,
 * preserving the provider's request headers when supported by 1DM.
 */
class DownloadWith1DMAction : VideoClickAction() {
    override val name = txt("Download with 1DM")

    override val oneSource = true

    override val sourceTypes: Set<ExtractorLinkType> = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.M3U8,
        ExtractorLinkType.DASH
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val selected = index?.let { result.links.getOrNull(it) }
            ?: result.links.singleOrNull()
            ?: return

        val headers = (if (selected.referer.isBlank()) emptyMap() else mapOf("referer" to selected.referer)) + selected.headers
        val packages = listOf(
            "idm.internet.download.manager",
            "idm.internet.download.manager.plus",
            "idm.internet.download.manager.adm.lite"
        )
        for (packageName in packages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(selected.url)).apply {
                    setClassName(packageName, "idm.internet.download.manager.Downloader")
                    putExtra("extra_filename", selected.name)
                    putExtra(Intent.EXTRA_TITLE, selected.name)
                    putExtra("headers", Bundle().apply {
                        headers.forEach { (key, value) -> putString(key, value) }
                    })
                }
                launch(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Continue with the next installed 1DM variant.
            }
        }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, selected.url)
            putExtra(Intent.EXTRA_TITLE, selected.name)
        }
        launch(Intent.createChooser(sendIntent, "Choose a download app"))
    }
}
