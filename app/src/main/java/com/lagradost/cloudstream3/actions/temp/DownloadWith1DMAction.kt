package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/**
 * Sends the exact selected extractor URL to Android's share targets.
 * 1DM can receive the URL from the chooser without hard-coding a package id.
 * Custom request headers are not transferred by this generic share contract.
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

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, selected.url)
            putExtra(Intent.EXTRA_TITLE, selected.name)
        }
        launch(Intent.createChooser(sendIntent, "Download with"))
    }
}
