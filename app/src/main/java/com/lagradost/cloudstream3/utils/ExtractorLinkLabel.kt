package com.lagradost.cloudstream3.utils

/**
 * Human-readable server label with the best quality and language information exposed
 * by the provider. Providers should include dubbing/subtitle language in link.name
 * when the website exposes it; this helper preserves and highlights those labels.
 */
fun ExtractorLink.displayNameWithQuality(): String {
    val detectedQuality = Regex("(?i)(2160|1440|1080|720|576|480|360|240)p?")
        .find(url)?.value?.uppercase()
    val qualityLabel = if (quality != Qualities.Unknown.value) {
        Qualities.getStringByInt(quality)
    } else detectedQuality ?: "Quality unknown"

    val translation = Regex(
        "(?i)(مدبلج|مترجم|عربي|ياباني|إنجليزي|انجليزي|ترجمة عربية|بدون ترجمة|dubbed|subbed|subtitles|arabic|japanese|english)"
    ).find("$name $source")?.value

    return listOfNotNull(
        name.takeIf { it.isNotBlank() },
        qualityLabel,
        translation?.takeIf { !name.contains(it, ignoreCase = true) },
        source.takeIf { it.isNotBlank() && !name.contains(it, ignoreCase = true) }
    ).distinct().joinToString(" • ")
}
