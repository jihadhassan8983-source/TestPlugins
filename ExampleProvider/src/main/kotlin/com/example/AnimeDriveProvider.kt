package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeDriveProvider : MainAPI() {
    override var mainUrl = "https://animedrive.in"
    override var name = "AnimeDrive"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page/").document
        val home = document.select("article, .post-item, .item").mapNotNull { element ->
            val title = element.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .post-item, .item").mapNotNull { element ->
            val title = element.selectFirst("h2, h3, .title")?.text() ?: return@mapNotNull null
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .entry-title")?.text() ?: "Anime"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.entry-content p")?.text()

        val episodes = mutableListOf<Episode>()
        document.select("div.entry-content a").forEachIndexed { index, element ->
            val href = element.attr("href")
            if (href.startsWith("http") && !href.contains("animedrive.in") && !href.contains("telegram")) {
                episodes.add(newEpisode(href) {
                    this.name = "Episode ${index + 1}"
                    this.episode = index + 1
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.episodes = episodes
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        loadExtractor(data, subtitleCallback, callback)
        return true
    }
}
