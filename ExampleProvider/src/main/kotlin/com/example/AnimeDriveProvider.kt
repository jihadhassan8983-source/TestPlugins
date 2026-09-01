package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.app

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.in"
    override var name = "AnimeDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article, .post-item, .item, .result-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = this.selectFirst("h2, h3, .title, .post-title")?.text() ?: a.attr("title").ifEmpty { a.text() }
        val href = a.attr("href")
        
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        
        if (title.isBlank() || href.isBlank()) return null

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .post-item, .item, .result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .entry-title")?.text() ?: "Unknown Title"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.entry-content p, div.the-content p")?.text()
        
        val epMap = mutableMapOf<Int, MutableList<Pair<String, String>>>()
        var currentEpNum = 1

        val elements = document.select("div.entry-content p, div.entry-content h3, div.entry-content div, div.entry-content li")
        
        for (element in elements) {
            val text = element.text()
            val epMatch = Regex("""(?:Episode|Ep)\s*0?(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (epMatch != null) {
                currentEpNum = epMatch.groupValues[1].toIntOrNull() ?: currentEpNum
            }
            
            element.select("a").forEach { a ->
                val href = a.attr("href")
                if (href.startsWith("http") && !href.contains("animedrive.in")) {
                    val linkText = a.text().ifEmpty { "Download Link" }
                    if (!href.contains("telegram") && !href.contains("whatsapp") && !href.contains("youtube")) {
                         epMap.getOrPut(currentEpNum) { mutableListOf() }.add(href to linkText)
                    }
                }
            }
        }

        val episodes = epMap.map { (epNum, links) ->
            val dataString = links.joinToString("|||") { "${it.first}:::${it.second}" }
            newEpisode(dataString) {
                this.name = "Episode $epNum"
                this.episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.episodes = if (episodes.isEmpty()) {
                val fallbackLinks = document.select("div.entry-content a")
                    .map { it.attr("href") to it.text() }
                    .filter { it.first.startsWith("http") && !it.first.contains(mainUrl) && !it.first.contains("telegram") }
                
                if (fallbackLinks.isNotEmpty()) {
                    val dataString = fallbackLinks.joinToString("|||") { "${it.first}:::${it.second}" }
                    listOf(newEpisode(dataString) {
                        this.name = "Movie / Batch"
                        this.episode = 1
                    })
                } else emptyList()
            } else episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val links = data.split("|||")
        links.forEach { linkData ->
            val parts = linkData.split(":::")
            if (parts.isNotEmpty()) {
                val url = parts[0]
                val sourceName = parts.getOrNull(1) ?: "Unknown Hoster"
                bypassAndExtract(url, sourceName, callback, subtitleCallback)
            }
        }
    }

    private suspend fun bypassAndExtract(
        url: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url

            when {
                finalUrl.contains("hubcloud") || finalUrl.contains("hubdrive") || finalUrl.contains("drivehub") -> {
                    extractHubCloud(finalUrl, sourceName, callback, subtitleCallback)
                }
                finalUrl.contains("pixeldrain.com") -> {
                    val id = finalUrl.substringAfter("u/").substringAfter("file/").substringBefore("?")
                    if (id.isNotBlank()) {
                        callback(newExtractorLink(
                            "Pixeldrain - $sourceName",
                            "Pixeldrain",
                            "https://pixeldrain.com/api/file/$id",
                            "",
                            Qualities.Unknown.value,
                            false
                        ))
                    }
                }
                else -> {
                    loadExtractor(finalUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractHubCloud(
        url: String, 
        sourceName: String, 
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val links = doc.select("a").map { it.attr("href") }.filter { it.startsWith("http") }
            val targetLink = links.find { it.contains("hubcloud") && (it.contains("download") || it.contains("id=")) } ?: url
            
            val doc2 = if (targetLink != url) app.get(targetLink).document else doc
            doc2.select("a.btn, a.button, a[href*='download'], a[href*='gpdl']").forEach { a ->
                val href = a.attr("href")
                val text = a.text().lowercase()
                
                if (href.startsWith("http") && !text.contains("login") && !text.contains("telegram")) {
                    if (text.contains("10gbps") || text.contains("fsl") || text.contains("download file") || text.contains("gpdl")) {
                         callback(newExtractorLink(
                             "HubCloud ($sourceName) - ${a.text().trim()}",
                             "HubCloud",
                             href,
                             "",
                             Qualities.Unknown.value,
                             false
                         ))
                    } else if (text.contains("gofile") || text.contains("pixeldrain") || text.contains("drive")) {
                         loadExtractor(href, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
